#!/usr/bin/env python3
"""Create a fresh portable Franziska workspace; never overwrite existing data."""
import argparse
import shutil
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--workspace", required=True, type=Path)
    args = parser.parse_args()
    target = args.workspace.expanduser().resolve()
    if target.exists() and (not target.is_dir() or any(target.iterdir())):
        parser.error("Choose a new or empty directory; existing workspace content is never overwritten.")
    repo = Path(__file__).resolve().parent.parent
    shutil.copytree(repo / "templates/workspace", target, dirs_exist_ok=True)
    continuity = target / "continuity"
    for name in ("paths.py", "search.py", "semantic_index.py", "recall.py"):
        shutil.copy2(repo / "components/continuity" / name, continuity / name)
    (target / "memory").mkdir(exist_ok=True)
    (target / "notes").mkdir(exist_ok=True)
    print(target)
    print("Created fresh identity and continuity templates. No existing agent configuration was changed.")


if __name__ == "__main__":
    main()
