"""Portable roots for the source-only community continuity package."""
import os
from pathlib import Path


def workspace_root() -> Path:
    value = os.environ.get("FRANZISKA_WORKSPACE")
    return Path(value).expanduser().resolve() if value else Path(__file__).resolve().parent.parent


def sessions_root() -> Path | None:
    """Raw-history import is opt-in and never defaults to a private host store."""
    value = os.environ.get("FRANZISKA_SESSIONS")
    return Path(value).expanduser().resolve() if value else None
