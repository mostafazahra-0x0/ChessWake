#!/usr/bin/env python3
"""Turn a captured Gradle log into GitHub Actions check annotations.

Why this exists
---------------
Kotlin compile errors, javac errors and JUnit failures are printed to the CI log,
which lives on a host that is not reachable from every environment that has to
debug the build. Annotations are served by the ordinary REST API
(`/check-runs/{id}/annotations`), so re-emitting the interesting lines makes a red
build readable from anywhere and puts each message next to its file in the diff.

GitHub allows only **10 annotations of each level per step**, which is fewer than
a broken build usually produces. So this script emits at most 8 file-scoped
annotations (the ones worth clicking on) plus one aggregated annotation carrying
*every* error it found - the aggregate is what keeps a long error list from being
silently truncated.

Usage
-----
    ./gradlew assembleDebug testDebugUnitTest 2>&1 | tee build.log
    python3 tools/annotate_ci_log.py build.log

Exit status is always 0: this runs *after* a failure and must not mask it.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

#: File-scoped annotations to emit before falling back to the aggregate only.
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
# JUnit: com.example.FooTest > some test FAILED
TEST_FAILED = re.compile(r"^(?P<test>\S+ > .+) FAILED\s*$")
# A Gradle execution failure line, e.g. "> Task :app:compileDebugKotlin FAILED"
TASK_FAILED = re.compile(r"^> Task (?P<task>:\S+) FAILED\s*$")

# Runner checkouts live under /home/runner/work/<repo>/<repo>/; annotations link
# properly only when the path is relative to the workspace.
WORKSPACE_PREFIX = re.compile(r"^/(?:home/runner|Users/runner)/work/[^/]+/[^/]+/")


def relative(path: str) -> str:
    stripped = WORKSPACE_PREFIX.sub("", path)
    return stripped.lstrip("/") if stripped != path else path


def escape(message: str, limit: int = MAX_MESSAGE_CHARS) -> str:
    """Colons and newlines break the annotation command format."""
    text = message.replace("%", "%25").replace("\r", " ").replace("\n", " ").strip()
    if len(text) > limit:
        text = text[:limit] + " [truncated]"
    return text


def command(level: str, message: str, path: str | None = None, line: int | None = None) -> str:
    props = []
    if path:
        props.append(f"file={relative(path)}")
    if line:
        props.append(f"line={line}")
    props.append("title=build failure")
    return f"::{level} {','.join(props)}::{message}"


def main(argv: list[str]) -> int:
    log_path = Path(argv[1]) if len(argv) > 1 else Path("build.log")
    if not log_path.exists():
        print(f"::notice::no build log at {log_path}; nothing to annotate")
        return 0

    lines = log_path.read_text(encoding="utf-8", errors="replace").splitlines()

    compile_errors: list[tuple[str, int, str]] = []
    warnings: list[tuple[str, int, str]] = []
    test_failures: list[str] = []
    tasks_failed: list[str] = []

    for index, raw in enumerate(lines):
        line = raw.rstrip()

        match = KOTLIN.match(line)
        if match and match.group("message"):
            entry = (match.group("path"), int(match.group("line")), match.group("message"))
            (compile_errors if line.startswith("e:") else warnings).append(entry)
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
            test_failures.append(f"{match.group('test')} :: {' | '.join(cause)}")
            continue

    # 1) A few file-scoped annotations, because those link to the diff.
    for path, line, message in compile_errors[:MAX_FILE_ANNOTATIONS]:
        print(command("error", escape(message), path=path, line=line))

    # 2) Everything, aggregated, so the 10-per-step cap cannot hide errors.
    if compile_errors:
        summary = "; ".join(f"{relative(path)}:{line} {message}" for path, line, message in compile_errors)
        total = len(compile_errors)
        shown = min(total, MAX_FILE_ANNOTATIONS)
        print(
            command(
                "error",
                escape(f"{total} compile error(s) ({shown} annotated individually): {summary}", MAX_AGGREGATE_CHARS),
            )
        )

    for failure in test_failures[:MAX_FILE_ANNOTATIONS]:
        print(command("error", escape(failure)))
    if test_failures:
        print(command("error", escape(f"{len(test_failures)} test failure(s): " + "; ".join(test_failures), MAX_AGGREGATE_CHARS)))

    if tasks_failed:
        print(command("error", escape("Failed Gradle task(s): " + ", ".join(dict.fromkeys(tasks_failed)))))

    # 3) Neither compile nor test errors: quote Gradle's own diagnosis instead.
    if not compile_errors and not test_failures:
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
        print(f"::notice::{escape(f'{len(warnings)} Kotlin warning(s) in the build log', MAX_MESSAGE_CHARS)}")

    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
