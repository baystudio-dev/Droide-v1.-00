#!/usr/bin/env python3
"""Run focused agent behavior regressions without invoking a remote model provider."""

import os
from pathlib import Path
import subprocess
import sys


def main():
    root = Path(__file__).resolve().parents[1]
    wrapper = root / ("gradlew.bat" if os.name == "nt" else "gradlew")
    selectors = (root / "tools/agent-behavior-tests.txt").read_text(encoding="utf-8").splitlines()
    command = [str(wrapper), ":app:testDebugUnitTest", *sys.argv[1:]]
    for selector in selectors:
        if selector.strip() and not selector.lstrip().startswith("#"):
            command.extend(["--tests", selector.strip()])
    return subprocess.call(command, cwd=root)


if __name__ == "__main__":
    sys.exit(main())
