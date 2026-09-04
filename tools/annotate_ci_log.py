#!/usr/bin/env python3
"""Turn a failed Gradle build into GitHub Actions check annotations.

Why this exists
---------------
Compile errors and test failures are printed to the CI log, which lives on a host
that is not reachable from every environment that has to debug the build.
Annotations are served by the ordinary REST API (`/check-runs/{id}/annotations`),
so re-emitting the interesting parts makes a red build readable from anywhere and
puts each message next to its file in the diff.

Two modes, run as two separate steps so that each gets its own annotation budget:

    # after `./gradlew ... 2>&1 | tee build.log`
    python3 tools/annotate_ci_log.py build.log
    python3 tools/annotate_ci_log.py --junit app/build/test-results/testDebugUnitTest

The log mode reports Kotlin/javac diagnostics. The `--junit` mode reports test
failures *with their assertion messages*, which the console log summarises down to
an exception class name - `expected:<23> but was:<24>` is the part worth reading.

GitHub allows only **10 annotations of each level per step**, which is fewer than
a broken build usually produces. So each mode emits at most 8 file-scoped
annotations (the ones worth clicking) plus one aggregate carrying *everything* it
found - the aggregate is what keeps a long list from being silently truncated.

Exit status is always 0: this runs *after* a failure and must not mask it.
"""

from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ElementTree
from pathlib import Path

#: File-scoped annotations before falling back to the aggregate alone.
MAX_FILE_ANNOTATIONS = 8
#: GitHub truncates very long annotation messages; stay well inside the limit.
MAX_MESSAGE_CHARS = 1000
MAX_AGGREGATE_CHARS = 6000

# Kotlin (K2 and the older frontend): e: file:///abs/path/Foo.kt:12:34 message
KOTLIN = re.compile(r"^[ew]: file://(?P<path>[^:]+):(?P<line>\d+):(?P<col>\d+) (?P<message>.*)$")
# javac / kotlinc-jvm style: /abs/path/Foo.java:12: error: message
JAVAC = re.compile(r"^(?P<path>[^\s:]+\.(?:java|kt|kts)):(?P<line>\d+):(?:\d+:)?\s*(?:error|warning):\s*(?P<message>.*)$")
# Gradle's own error blocks.
WHAT_WENT_WRONG = re.compile(r"^\* What went wrong:$")
# JUnit console summary: com.example.FooTest > some test FAILED
TEST_FAILED = re.compile(r"^(?P<test>\S+ > .+) FAILED\s*$")
# A Gradle execution failure line, e.g. "> Task :app:compileDebugKotlin FAILED"
TASK_FAILED = re.compile(r"^> Task (?P<task>:\S+) FAILED\s*$")
# A stack-trace frame: "at com.example.FooTest.some test(FooTest.kt:148)".
# Kotlin test names are backtick-quoted sentences, so the part before the paren
# routinely contains spaces.
FRAME = re.compile(r"\bat\s+([^\s(][^(]*)\(([\w]+\.kt):(\d+)\)")

PROJECT_PACKAGE = "com.mostafazahra.chesswake"
TEST_SOURCES = "app/src/test/java/"
MAIN_SOURCES = "app/src/main/java/"

# Runner checkouts live under /home/runner/work/<repo>/<repo>/; annotations link
# properly only when the path is relative to the workspace.
WORKSPACE_PREFIX = re.compile(r"^/(?:home/runner|Users/runner)/work/[^/]+/[^/]+/")


def relative(path: str) -> str:
    stripped = WORKSPACE_PREFIX.sub("", path)
    return stripped.lstrip("/") if stripped != path else path


def escape(message: str, limit: int = MAX_MESSAGE_CHARS) -> str:
    """Colons and newlines break the annotation command format."""
    text = " ".join(message.split()).replace("%", "%25").strip()
    if len(text) > limit:
        text = text[:limit] + " [truncated]"
    return text or "(no message)"


def command(level: str, message: str, path: str | None = None, line: int | None = None) -> str:
    props = []
    if path:
        props.append(f"file={relative(path)}")
    if line:
        props.append(f"line={line}")
    props.append("title=build failure")
    return f"::{level} {','.join(props)}::{message}"


def emit_all(kind: str, entries: list[tuple[str | None, int | None, str]]) -> None:
    """A few linked annotations, then one aggregate that cannot be truncated away."""
    for path, line, message in entries[:MAX_FILE_ANNOTATIONS]:
        print(command("error", escape(message), path=path, line=line))
    if not entries:
        return
    summary = "; ".join(
        f"{relative(path)}:{line} {message}" if path else message for path, line, message in entries
    )
    shown = min(len(entries), MAX_FILE_ANNOTATIONS)
    print(
        command(
            "error",
            escape(
                f"{len(entries)} {kind} ({shown} annotated individually): {summary}",
                MAX_AGGREGATE_CHARS,
            ),
        )
    )


# ---------------------------------------------------------------------------
# JUnit XML: the assertion messages the console log leaves out
# ---------------------------------------------------------------------------


def locate(classname: str, stack: str) -> tuple[str | None, int | None]:
    """The workspace-relative source file and line of the first project frame."""
    package_path = classname.rsplit(".", 1)[0].replace(".", "/") if "." in classname else ""
    for match in FRAME.finditer(stack):
        fqcn, file_name, line = match.groups()
        if not fqcn.startswith(PROJECT_PACKAGE):
            continue
        for prefix in (TEST_SOURCES, MAIN_SOURCES):
            candidate = f"{prefix}{package_path}/{file_name}"
            if Path(candidate).exists():
                return candidate, int(line)
        return None, None
    return None, None


def junit_failures(directory: Path) -> list[tuple[str | None, int | None, str]]:
    failures: list[tuple[str | None, int | None, str]] = []
    for report in sorted(directory.glob("*.xml")):
        try:
            root = ElementTree.parse(report).getroot()
        except ElementTree.ParseError:
            continue
        for case in root.iter("testcase"):
            for tag in ("failure", "error"):
                node = case.find(tag)
                if node is None:
                    continue
                classname = case.get("classname") or report.stem
                text = (node.text or "").strip()
                message = (node.get("message") or "").strip()
                if not message:
                    message = text.splitlines()[0] if text else node.get("type") or "failed"
                path, line = locate(classname, text)
                failures.append((path, line, f"{classname.rsplit('.', 1)[-1]} > {case.get('name')}: {message}"))
    return failures


# ---------------------------------------------------------------------------
# The captured Gradle log
# ---------------------------------------------------------------------------


def log_diagnostics(log_path: Path) -> int:
    lines = log_path.read_text(encoding="utf-8", errors="replace").splitlines()

    compile_errors: list[tuple[str, int, str]] = []
    warnings = 0
    test_failures: list[tuple[str | None, int | None, str]] = []
    tasks_failed: list[str] = []

    for index, raw in enumerate(lines):
        line = raw.rstrip()

        match = KOTLIN.match(line)
        if match and match.group("message"):
            if line.startswith("e:"):
                compile_errors.append((match.group("path"), int(match.group("line")), match.group("message")))
            else:
                warnings += 1
            continue

        match = JAVAC.match(line)
        if match:
            compile_errors.append((match.group("path"), int(match.group("line")), match.group("message")))
            continue

        match = TASK_FAILED.match(line)
        if match:
            tasks_failed.append(match.group("task"))
            continue

        match = TEST_FAILED.match(line)
        if match:
            cause = []
            for follow in lines[index + 1 : index + 6]:
                stripped = follow.strip()
                if not stripped:
                    break
                cause.append(stripped)
            test_failures.append((None, None, f"{match.group('test')} :: {' | '.join(cause)}"))
            continue

    emit_all("compile error(s)", [(path, line, message) for path, line, message in compile_errors])
    emit_all("test failure(s)", test_failures)

    if tasks_failed:
        print(command("error", escape("Failed Gradle task(s): " + ", ".join(dict.fromkeys(tasks_failed)))))

    if not compile_errors and not test_failures:
        # Neither: quote Gradle's own diagnosis, or the tail of the log.
        for index, line in enumerate(lines):
            if WHAT_WENT_WRONG.match(line):
                block = [entry.strip() for entry in lines[index + 1 : index + 40] if entry.strip()]
                stop = next((i for i, entry in enumerate(block) if entry.startswith("* Try:")), len(block))
                print(command("error", escape(" | ".join(block[: max(stop, 6)]) or "Gradle failed without a message", MAX_AGGREGATE_CHARS)))
                break
        else:
            tail = [entry.strip() for entry in lines[-40:] if entry.strip()]
            print(command("error", escape("Build failed with no recognisable Gradle diagnosis. Log tail: " + " | ".join(tail[-12:]), MAX_AGGREGATE_CHARS)))

    if warnings:
        print(f"::notice::{escape(f'{warnings} Kotlin warning(s) in the build log')}")
    return 0


def main(argv: list[str]) -> int:
    if "--junit" in argv:
        index = argv.index("--junit")
        directory = Path(argv[index + 1]) if len(argv) > index + 1 else Path("app/build/test-results/testDebugUnitTest")
        if not directory.is_dir():
            print(f"::notice::no JUnit reports under {directory}; the tests probably never ran")
            return 0
        failures = junit_failures(directory)
        if not failures:
            print(f"::notice::no test failures recorded in {directory}")
            return 0
        emit_all("test failure(s)", failures)
        return 0

    log_path = Path(argv[1]) if len(argv) > 1 else Path("build.log")
    if not log_path.exists():
        print(f"::notice::no build log at {log_path}; nothing to annotate")
        return 0
    return log_diagnostics(log_path)


if __name__ == "__main__":
    sys.exit(main(sys.argv))
