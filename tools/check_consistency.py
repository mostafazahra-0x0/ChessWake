#!/usr/bin/env python3
"""Static consistency checks for ChessWake.

The project cannot be compiled in every environment it is edited in, so this
script approximates the parts of `kotlinc` that catch the most common breakage
between files:

  1. every `import com.mostafazahra.chesswake.<pkg>.<Name>` resolves to a top-level
     declaration named `Name` in a file that declares `package <pkg>`;
  2. every `R.string.x` / `R.drawable.x` / `R.raw.x` / `R.color.x` / `R.style.x`
     reference exists in `res/`;
  3. every `<string name="x">` placeholder count matches the number of arguments
     at its Kotlin call site (a cheap, conservative check).

It is deliberately conservative: anything it cannot parse is skipped rather than
reported. Exit code 1 means "something looks broken".
"""

from __future__ import annotations

import re
import sys
from collections import defaultdict
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
MAIN = REPO / "app/src/main"
SRC = MAIN / "java"
RES = MAIN / "res"

PACKAGE_RE = re.compile(r"^\s*package\s+([\w.]+)", re.MULTILINE)
IMPORT_RE = re.compile(r"^\s*import\s+(com\.mostafazahra\.chesswake\.[\w.]+)", re.MULTILINE)
DECL_RE = re.compile(
    r"^\s*(?:@\w+(?:\([^)]*\))?\s*)*"
    r"(?:public\s+|internal\s+|private\s+|abstract\s+|sealed\s+|open\s+|data\s+|value\s+|annotation\s+|enum\s+)*"
    r"(?:class|object|interface|fun|val|var|typealias)\s+"
    r"(?:<[^>]+>\s*)?([\w.]+)",
    re.MULTILINE,
)
R_REF_RE = re.compile(r"\bR\.(string|drawable|raw|color|style|mipmap|plurals)\.(\w+)")


def declared_names() -> dict[str, set[str]]:
    """package -> names declared at top level in that package."""
    table: dict[str, set[str]] = defaultdict(set)
    for path in SRC.rglob("*.kt"):
        text = path.read_text(encoding="utf-8")
        package_match = PACKAGE_RE.search(text)
        if not package_match:
            continue
        package = package_match.group(1)
        for match in DECL_RE.finditer(text):
            name = match.group(1).split(".").pop()
            table[package].add(name)
        # Extension properties/functions declared on other types, e.g.
        # `val ColorScheme.foo`, are not importable by name; ignore them.
    return table


def resource_names() -> dict[str, set[str]]:
    """resource type -> declared names."""
    table: dict[str, set[str]] = defaultdict(set)
    for path in RES.rglob("*"):
        if not path.is_file():
            continue
        bucket = path.parent.name.split("-")[0]
        if bucket in {"drawable", "raw", "mipmap", "color", "anim", "font", "xml"}:
            table[bucket].add(path.stem)
        if path.suffix.lower() != ".xml":
            continue
        text = path.read_text(encoding="utf-8")
        for match in re.finditer(r'<(string|color|style|plurals|string-array)\s+name="([^"]+)"', text):
            kind, name = match.group(1), match.group(2)
            table["string" if kind in {"string", "string-array"} else kind].add(name)
    return table


#: Classes the Android build generates, so they are never declared in a source file.
GENERATED = {"R", "BuildConfig", "BR", "DaggerChessWakeApplication_HiltComponents_SingletonC"}


def check_imports(decls: dict[str, set[str]]) -> list[str]:
    problems: list[str] = []
    for path in SRC.rglob("*.kt"):
        text = path.read_text(encoding="utf-8")
        for match in IMPORT_RE.finditer(text):
            fqcn = match.group(1)
            package, _, name = fqcn.rpartition(".")
            if not package or name in GENERATED:
                continue
            known = decls.get(package)
            if known is None:
                problems.append(f"{path}: unknown package {package} (import {fqcn})")
            elif name not in known:
                problems.append(f"{path}: {name} not declared in {package}")
    return problems


def check_resources(res: dict[str, set[str]]) -> list[str]:
    problems: list[str] = []
    for path in list(SRC.rglob("*.kt")) + list(RES.rglob("*.xml")) + [MAIN / "AndroidManifest.xml"]:
        if not path.exists():
            continue
        text = path.read_text(encoding="utf-8")
        for match in R_REF_RE.finditer(text):
            kind, name = match.group(1), match.group(2)
            if name not in res.get(kind, set()):
                problems.append(f"{path}: R.{kind}.{name} is not defined in res/")
        for match in re.finditer(r'@(string|drawable|color|style|mipmap|raw)/([\w.]+)', text):
            kind, name = match.group(1), match.group(2)
            if name.startswith("android:") or name in {"null", "transparent"}:
                continue
            if name not in res.get(kind, set()):
                problems.append(f"{path}: @{kind}/{name} is not defined in res/")
    return problems


def check_placeholders() -> list[str]:
    """Every %1$s in a string must be matched by an argument at the call site."""
    strings: dict[str, int] = {}
    for path in RES.rglob("values/strings.xml"):
        for match in re.finditer(r'<string name="([^"]+)"[^>]*>(.*?)</string>', path.read_text(), re.DOTALL):
            name, body = match.group(1), match.group(2)
            args = set(re.findall(r"%(\d+)\$", body))
            strings[name] = max((int(a) for a in args), default=0)

    problems: list[str] = []
    for path in SRC.rglob("*.kt"):
        text = path.read_text(encoding="utf-8")
        for match in re.finditer(r"(?:stringResource|getString)\(\s*R\.string\.(\w+)\s*((?:,[^()\n]*)?)\)", text):
            name, raw_args = match.group(1), match.group(2)
            required = strings.get(name)
            if required is None:
                continue
            given = 0 if not raw_args.strip() else len([a for a in raw_args.split(",") if a.strip()])
            if given != required:
                problems.append(
                    f"{path}: R.string.{name} needs {required} argument(s), call passes {given}",
                )
    return problems


def main() -> int:
    decls = declared_names()
    res = resource_names()
    problems = check_imports(decls) + check_resources(res) + check_placeholders()
    if problems:
        print(f"{len(problems)} problem(s):")
        for problem in sorted(set(problems)):
            print("  -", problem)
        return 1
    print("ok: imports, resource references and string placeholders all resolve")
    return 0


if __name__ == "__main__":
    sys.exit(main())
