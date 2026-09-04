#!/usr/bin/env python3
"""Turn a captured Gradle log into GitHub Actions check annotations.

Why this exists
---------------
Kotlin compile errors, javac errors and JUnit failures are printed to the CI log,
which on GitHub lives on a host that is not always reachable from every
environment that has to debug the build. Annotations, by contrast, are served by
the ordinary REST API (`/check-runs/{id}/annotations`), so re-emitting the
interesting lines as `::error` commands makes a red build readable from anywhere
and puts the message next to the offending file in the diff view.

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

MAX_ANNOTATIONS = 50
MAX_MESSAGE_CHARS = 1200

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


def emit(level: str, message: str, path: str | None = None, line: int | None = None, col: int | None = None) -> None:
    props = []
    if path:
        props.append(f"file={relative(path)}")
    if line:
        props.append(f"line={line}")
    if col:
        props.append(f"col={col}")
    props.append(f"title={level} in build")
    # Colons and newlines break the annotation format.
    text = message.replace("%", "%25").replace("\r", " ").replace("\n", " ").strip()
    if len(text) > MAX_MESSAGE_CHARS:
        text = text[:MAX_MESSAGE_CHARS] + " [truncated]"
    prefix = f"::{level} {','.join(props)}::" if props else f"::{level}::"
    print(prefix + text)


def main(argv: list[str]) -> int:
    log_path = Path(argv[1]) if len(argv) > 1 else Path("build.log")
    if not log_path.exists():
        print(f"::notice::no build log at {log_path}; nothing to annotate")
        return 0

    lines = log_path.read_text(encoding="utf-8", errors="replace").splitlines()
    emitted = 0
    kotlin_errors = 0
    tasks_failed: list[str] = []
    tests_failed: list[str] = []

    for index, raw in enumerate(lines):
        line = raw.rstrip()

        match = KOTLIN.match(line)
        if match and match.group("message"):
            level = "error" if line.startswith("e:") else "warning"
            if level == "error":
                kotlin_errors += 1
            if emitted < MAX_ANNOTATIONS:
                emit(
                    level,
                    match.group("message"),
                    path=match.group("path"),
                    line=int(match.group("line")),
                    col=int(match.group("col")),
                )
                emitted += 1
            continue

        match = JAVAC.match(line)
        if match:
            if emitted < MAX_ANNOTATIONS:
                emit("error", match.group("message"), path=match.group("path"), line=int(match.group("line")))
                emitted += 1
            continue

        match = TASK_FAILED.match(line)
        if match:
            tasks_failed.append(match.group("task"))
            continue

        match = TEST_FAILED.match(line)
        if match:
            tests_failed.append(match.group("test"))
            # The cause is indented on the following lines.
            cause = []
            for follow in lines[index + 1 : index + 6]:
                stripped = follow.strip()
                if not stripped:
                    break
                cause.append(stripped)
            if emitted < MAX_ANNOTATIONS:
                emit("error", f"{match.group('test')} :: {' | '.join(cause)}")
                emitted += 1
            continue

    if kotlin_errors:
        emit(
            "error",
            f"{kotlin_errors} Kotlin compile error(s); the first {min(kotlin_errors, MAX_ANNOTATIONS)} are annotated individually.",
        )
    if tasks_failed:
        emit("error", "Failed Gradle task(s): " + ", ".join(dict.fromkeys(tasks_failed)))

    if not kotlin_errors and not tests_failed:
        # Not a compile or test failure: quote Gradle's own diagnosis so the
        # reason is still readable without the log host.
        for index, line in enumerate(lines):
            if WHAT_WENT_WRONG.match(line):
                block = [entry.strip() for entry in lines[index + 1 : index + 40] if entry.strip()]
                stop = next((i for i, entry in enumerate(block) if entry.startswith("* Try:")), len(block))
                emit("error", " | ".join(block[: max(stop, 6)]) or "Gradle failed without a message")
                break
        else:
            tail = [entry.strip() for entry in lines[-40:] if entry.strip()]
            emit("error", "Build failed with no recognisable Gradle diagnosis. Log tail: " + " | ".join(tail[-12:]))

    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
