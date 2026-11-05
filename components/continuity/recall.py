#!/usr/bin/env python3
"""Build an evidence pack for a memory/project/history question.

The output is meant for the assistant before answering the user: what to read first,
what facts were found, and whether bulky/private sources are needed.
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path


from paths import workspace_root

ROOT = workspace_root()
CONTINUITY = ROOT / "continuity"


STARTUP_CHAIN = [
    "continuity/BOOTSTRAP.md",
    "continuity/PINNED.md",
    "continuity/HANDOFF.md",
    "continuity/PROJECTS.md",
    "continuity/RECALL_PROTOCOL.md",
]


TRIGGER_HINTS = {
    "history": ["remember", "вспомни", "остановились", "раньше", "делали", "решили"],
    "project": ["project", "проект", "card", "state", "map", "bridge", "мост"],
    "correction": ["упустила", "ошиб", "неверно", "коллиз", "contradiction"],
    "preference": ["хочу", "нужно", "важно", "никогда", "правило", "предпоч"],
    "infrastructure": ["backup", "бэкап", "routes", "ssh", "signal", "imessage", "telegram"],
    "openclaw": ["openclaw", "active memory", "active-memory", "session", "gateway"],
}


@dataclass
class Hit:
    score: float
    source: str
    path: str
    excerpt: str


def classify(query: str) -> list[str]:
    low = query.lower()
    classes = []
    for name, hints in TRIGGER_HINTS.items():
        if any(h in low for h in hints):
            classes.append(name)
    return classes or ["general"]


def project_card_candidates(query: str) -> list[str]:
    """Discover the user's project cards rather than shipping personal mappings."""
    terms = set(re.findall(r"[\w-]{3,}", query.casefold()))
    candidates = []
    for card in sorted((CONTINUITY / "projects").glob("*.md")):
        title = next((line for line in card.read_text(encoding="utf-8").splitlines() if line.startswith("# ")), "")
        labels = set(re.findall(r"[\w]{3,}", (card.stem.replace("-", " ") + " " + title).casefold()))
        if terms & labels:
            candidates.append(str(card.relative_to(ROOT)))
    return candidates


def run_search(query: str, limit: int, sessions: bool) -> list[Hit]:
    cmd = ["python3", str(CONTINUITY / "search.py")]
    if sessions:
        cmd.append("--sessions")
    cmd.extend([query, "--limit", str(limit)])
    proc = subprocess.run(cmd, cwd=ROOT, text=True, capture_output=True, check=False)
    if proc.returncode != 0:
        raise RuntimeError("Continuity search failed: " + proc.stderr.strip())
    hits: list[Hit] = []
    current: dict[str, str | float] | None = None
    for line in proc.stdout.splitlines():
        if line.startswith("[") and "] " in line:
            if current:
                hits.append(Hit(**current))
            score_text, rest = line.split("] ", 1)
            score = float(score_text.strip("[]"))
            parts = rest.split(" ", 1)
            current = {
                "score": score,
                "source": parts[0],
                "path": parts[1] if len(parts) > 1 else "",
                "excerpt": "",
            }
        elif current is not None and line.strip():
            current["excerpt"] = (str(current.get("excerpt") or "") + " " + line.strip()).strip()
    if current:
        hits.append(Hit(**current))
    return hits


def run_semantic(query: str, limit: int) -> list[dict]:
    cmd = ["python3", str(CONTINUITY / "semantic_index.py"), "query", query, "--limit", str(limit), "--json"]
    proc = subprocess.run(cmd, cwd=ROOT, text=True, capture_output=True, check=False)
    if proc.returncode != 0:
        return []
    try:
        data = json.loads(proc.stdout)
    except Exception:
        return []
    return data.get("hits", [])


def freshness(path: str) -> str:
    p = ROOT / path if not path.startswith("/") else Path(path)
    if not p.exists():
        return "missing"
    seconds = datetime.now(timezone.utc).timestamp() - p.stat().st_mtime
    if seconds < 3600:
        return f"{int(seconds / 60)}m"
    if seconds < 86400:
        return f"{seconds / 3600:.1f}h"
    return f"{seconds / 86400:.1f}d"


def confidence(hits: list[Hit], cards: list[str]) -> str:
    if not hits:
        return "none"
    top = hits[0].score
    has_curated = any(hit.source in {"continuity", "continuity-project", "long-term"} for hit in hits[:3])
    if top >= 20 and (cards or has_curated):
        return "high"
    if top >= 10:
        return "medium"
    return "low"


def recommended_action(classes: list[str], confidence_level: str) -> str:
    if confidence_level in {"none", "low"}:
        return "Search deeper with --sessions or inspect raw evidence before answering."
    if "correction" in classes:
        return "Check COLLISIONS.md and update it if the new evidence contradicts durable memory."
    if "history" in classes:
        return "Answer from handoff/project/daily evidence and name uncertainty if sources disagree."
    if "project" in classes:
        return "Read the selected project card before giving project state or next steps."
    return "Use the top evidence, keep the answer concise, and avoid raw private log quotes."


def build_pack(query: str, limit: int, include_sessions: bool) -> dict:
    classes = classify(query)
    cards = project_card_candidates(query)
    required_reads = list(STARTUP_CHAIN)
    for card in cards:
        if card not in required_reads:
            required_reads.append(card)

    hits = run_search(query, limit=limit, sessions=False)
    semantic_hits = run_semantic(query, limit=min(limit, 6))
    session_hits: list[Hit] = []
    need_deep = any(c in classes for c in ["history", "correction"]) or len(hits) < 3
    if include_sessions or need_deep:
        session_hits = run_search(query, limit=min(limit, 5), sessions=True)

    confidence_level = confidence(hits, cards)
    return {
        "query": query,
        "classes": classes,
        "confidence": confidence_level,
        "recommended_action": recommended_action(classes, confidence_level),
        "required_read_order": required_reads,
        "project_cards": cards,
        "search_hits": [hit.__dict__ for hit in hits],
        "semantic_hits": semantic_hits,
        "session_search_hits": [hit.__dict__ for hit in session_hits],
        "answer_rules": [
            "Prefer pinned/project/handoff facts over raw memory unless newer evidence contradicts them.",
            "If evidence conflicts, name the conflict and update COLLISIONS.md before treating it as settled.",
            "Do not paste private Telegram/session logs; summarize only the necessary evidence.",
            "If evidence is weak, say so explicitly.",
        ],
        "source_freshness_tokens": {path: freshness(path) for path in required_reads},
    }


def print_markdown(pack: dict) -> None:
    print(f"# Recall Pack\n")
    print(f"Query: {pack['query']}")
    print(f"Classes: {', '.join(pack['classes'])}")
    print(f"Confidence: {pack['confidence']}")
    print(f"Recommended action: {pack['recommended_action']}")
    print("\n## Required Read Order")
    for path in pack["required_read_order"]:
        print(f"- {path}")
    if pack["project_cards"]:
        print("\n## Project Cards")
        for path in pack["project_cards"]:
            print(f"- {path}")
    print("\n## Top Evidence")
    for hit in pack["search_hits"]:
        print(f"- [{hit['score']:.2f}] {hit['source']} {hit['path']}")
        print(f"  {hit['excerpt'][:500]}")
    if pack["semantic_hits"]:
        print("\n## Semantic Evidence")
        for hit in pack["semantic_hits"][:5]:
            print(f"- [{hit['score']:.4f}] {hit['source']} {hit['path']}")
            print(f"  {hit['excerpt'][:500]}")
    if pack["session_search_hits"]:
        print("\n## Deep Evidence")
        for hit in pack["session_search_hits"][:5]:
            print(f"- [{hit['score']:.2f}] {hit['source']} {hit['path']}")
            print(f"  {hit['excerpt'][:500]}")
    print("\n## Answer Rules")
    for rule in pack["answer_rules"]:
        print(f"- {rule}")
    print("\n## Source Freshness")
    for path, value in pack["source_freshness_tokens"].items():
        print(f"- {path}: {value}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("query", nargs="+")
    parser.add_argument("--limit", type=int, default=6)
    parser.add_argument("--sessions", action="store_true")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()

    pack = build_pack(" ".join(args.query), args.limit, args.sessions)
    if args.json:
        print(json.dumps(pack, ensure_ascii=False, indent=2))
    else:
        print_markdown(pack)


if __name__ == "__main__":
    main()
