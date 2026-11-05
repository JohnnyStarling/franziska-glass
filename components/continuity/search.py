#!/usr/bin/env python3
"""Search Franziska continuity evidence locally.

This is intentionally simple and dependency-free: lexical retrieval with
recency/source weighting and compact excerpts. It is a bridge toward deeper
hybrid graph+semantic memory.
"""

from __future__ import annotations

import argparse
import json
import math
import re
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Iterable


from paths import workspace_root, sessions_root

WORKSPACE = workspace_root()
SESSIONS = sessions_root()


WORD_RE = re.compile(r"[A-Za-zА-Яа-яЁё0-9_]{2,}")

SEMANTIC_ALIASES = {
    "memory": {"память", "памяти", "контекст", "continuity", "recall", "handoff", "вспомни"},
    "continuity": {"память", "контекст", "бесшов", "перезапуск", "compaction", "компактац"},
    "telegram": {"телеграм", "тг", "сообщения", "переписка", "bridge", "мост"},
    "bridge": {"мост", "fallback", "signal", "imessage", "telegram", "уведомление"},
    "backup": {"бэкап", "резерв", "копия", "mac", "mini", "encrypted"},
    "project": {"проект", "карточка", "card", "state", "next", "action"},
    "avatar": {"аватар", "prat", "cam", "портрет", "video", "видео"},
    "map": {"карта", "яндекс", "yandex", "routes", "miniapp"},
    "collision": {"коллизия", "ошибка", "исправление", "contradiction", "упустила"},
    "openclaw": {"active-memory", "active", "memory", "session", "gateway", "dmScope", "plugin"},
}


@dataclass
class Doc:
    source: str
    path: str
    text: str
    timestamp: float | None = None
    weight: float = 1.0


def words(text: str) -> list[str]:
    return [w.lower() for w in WORD_RE.findall(text)]


def expand_terms(query_terms: set[str]) -> set[str]:
    expanded = set(query_terms)
    for key, aliases in SEMANTIC_ALIASES.items():
        cluster = {key, *aliases}
        if query_terms & cluster:
            expanded |= cluster
    return expanded


def flatten(value) -> str:
    if value is None:
        return ""
    if isinstance(value, str):
        return value
    if isinstance(value, (int, float, bool)):
        return str(value)
    if isinstance(value, list):
        return " ".join(flatten(v) for v in value)
    if isinstance(value, dict):
        return " ".join(flatten(v) for v in value.values())
    return str(value)


def excerpt(text: str, terms: set[str], width: int = 420) -> str:
    clean = " ".join(text.split())
    low = clean.lower()
    positions = [low.find(term) for term in terms if low.find(term) >= 0]
    if not positions:
        return clean[:width]
    start = max(0, min(positions) - width // 3)
    return clean[start : start + width]


def load_markdown(paths: Iterable[Path], source: str, weight: float) -> Iterable[Doc]:
    for path in paths:
        try:
            text = path.read_text(encoding="utf-8", errors="ignore")
        except Exception:
            continue
        yield Doc(source=source, path=str(path), text=text, weight=weight)


def load_telegram() -> Iterable[Doc]:
    if SESSIONS is None:
        return
    path = SESSIONS / "sessions.json.telegram-messages.json"
    if not path.exists():
        return
    for line in path.read_text(encoding="utf-8", errors="ignore").splitlines():
        if not line.strip():
            continue
        try:
            row = json.loads(line)
        except Exception:
            continue
        msg = (((row.get("node") or {}).get("sourceMessage")) or {})
        text = msg.get("text") or msg.get("caption") or ""
        if not text:
            continue
        mid = msg.get("message_id")
        ts = msg.get("date")
        date = ""
        if isinstance(ts, (int, float)):
            date = datetime.fromtimestamp(ts, timezone.utc).isoformat()
        yield Doc(
            source="telegram",
            path=f"{path}#{mid}",
            text=f"{date} User: {text}",
            timestamp=ts if isinstance(ts, (int, float)) else None,
            weight=1.25,
        )


def load_sessions(limit: int = 2000) -> Iterable[Doc]:
    if SESSIONS is None:
        return
    count = 0
    for path in sorted(SESSIONS.glob("*.jsonl.codex-app-server.json")):
        for line in path.read_text(encoding="utf-8", errors="ignore").splitlines():
            if count >= limit:
                return
            if not line.strip():
                continue
            try:
                row = json.loads(line)
            except Exception:
                continue
            text = flatten(row)
            if len(text.strip()) < 20:
                continue
            count += 1
            yield Doc(source="session", path=str(path), text=text, weight=0.75)


def all_docs(include_sessions: bool) -> list[Doc]:
    docs: list[Doc] = []
    docs.extend(load_markdown((WORKSPACE / "continuity").glob("*.md"), "continuity", 2.0))
    docs.extend(load_markdown((WORKSPACE / "continuity" / "projects").glob("*.md"), "continuity-project", 2.2))
    docs.extend(load_markdown([WORKSPACE / "MEMORY.md"], "long-term", 1.8))
    docs.extend(load_markdown((WORKSPACE / "memory").glob("*.md"), "daily", 1.3))
    docs.extend(load_markdown((WORKSPACE / "notes").glob("*.md"), "notes", 1.0))
    docs.extend(load_markdown((WORKSPACE / "audits").glob("*.md"), "audit", 1.0))
    docs.extend(load_telegram() or [])
    if include_sessions:
        docs.extend(load_sessions())
    return docs


def score_doc(doc: Doc, query_terms: set[str], now: float) -> float:
    tokens = words(doc.text)
    if not tokens:
        return 0.0
    counts = {}
    for token in tokens:
        counts[token] = counts.get(token, 0) + 1
    matched = [term for term in query_terms if term in counts]
    if not matched:
        return 0.0
    tf = sum(1.0 + math.log(counts[t]) for t in matched)
    coverage = len(matched) / max(1, len(query_terms))
    recency = 1.0
    if doc.timestamp:
        age_days = max(0.0, (now - doc.timestamp) / 86400)
        recency += 0.25 / (1.0 + age_days / 14.0)
    return doc.weight * tf * (0.5 + coverage) * recency


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("query", nargs="+")
    parser.add_argument("--limit", type=int, default=8)
    parser.add_argument("--sessions", action="store_true", help="include bulky session transcripts")
    args = parser.parse_args()

    query = " ".join(args.query)
    query_terms = expand_terms(set(words(query)))
    now = datetime.now(timezone.utc).timestamp()
    ranked = []
    for doc in all_docs(include_sessions=args.sessions):
        score = score_doc(doc, query_terms, now)
        if score > 0:
            ranked.append((score, doc))
    ranked.sort(key=lambda item: item[0], reverse=True)

    print(f"query: {query}")
    print(f"hits: {len(ranked)}")
    for score, doc in ranked[: args.limit]:
        print()
        print(f"[{score:.2f}] {doc.source} {doc.path}")
        print(excerpt(doc.text, query_terms))


if __name__ == "__main__":
    main()
