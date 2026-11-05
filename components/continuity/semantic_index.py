#!/usr/bin/env python3
"""Build and query a local semantic vector index for continuity memory.

This is dependency-free by design. It uses sparse hashed vectors over lexical
tokens and character n-grams, plus source weights. It is not a neural embedding
model, but it gives a real vector retrieval layer with cosine similarity and a
stable JSON interface that can later be backed by embeddings.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from pathlib import Path

import search as continuity_search


from paths import workspace_root

ROOT = workspace_root()
OUT = ROOT / "continuity" / "SEMANTIC_INDEX.json"
WORD_RE = re.compile(r"[A-Za-zА-Яа-яЁё0-9_]{2,}")
DIM = 4096


@dataclass
class Chunk:
    source: str
    path: str
    text: str
    timestamp: float | None
    weight: float
    vector: dict[str, float]
    norm: float


def stable_hash(text: str) -> int:
    return int.from_bytes(hashlib.blake2b(text.encode("utf-8"), digest_size=8).digest(), "big")


def words(text: str) -> list[str]:
    return [w.lower() for w in WORD_RE.findall(text)]


def features(text: str) -> dict[str, float]:
    tokens = continuity_search.expand_terms(set(words(text)))
    feats: dict[str, float] = {}
    for token in tokens:
        idx = str(stable_hash(f"w:{token}") % DIM)
        feats[idx] = feats.get(idx, 0.0) + 1.4
        padded = f" {token} "
        for n in (3, 4):
            for i in range(max(0, len(padded) - n + 1)):
                gram = padded[i : i + n]
                idx = str(stable_hash(f"c{n}:{gram}") % DIM)
                feats[idx] = feats.get(idx, 0.0) + 0.35
    return feats


def normalize(vec: dict[str, float]) -> tuple[dict[str, float], float]:
    norm = math.sqrt(sum(v * v for v in vec.values()))
    if norm <= 0:
        return vec, 0.0
    return {k: v / norm for k, v in vec.items()}, norm


def split_text(text: str, max_chars: int = 1200) -> list[str]:
    clean = "\n".join(line.rstrip() for line in text.splitlines() if line.strip())
    if len(clean) <= max_chars:
        return [clean]
    parts: list[str] = []
    current: list[str] = []
    size = 0
    for para in clean.split("\n"):
        if size + len(para) > max_chars and current:
            parts.append("\n".join(current))
            current = []
            size = 0
        current.append(para)
        size += len(para) + 1
    if current:
        parts.append("\n".join(current))
    return parts


def build_chunks(include_sessions: bool) -> list[Chunk]:
    chunks: list[Chunk] = []
    docs = continuity_search.all_docs(include_sessions=include_sessions)
    for doc in docs:
        for part in split_text(doc.text):
            vec, norm = normalize(features(part))
            if not vec:
                continue
            chunks.append(
                Chunk(
                    source=doc.source,
                    path=doc.path,
                    text=part[:1600],
                    timestamp=doc.timestamp,
                    weight=doc.weight,
                    vector=vec,
                    norm=norm,
                )
            )
    return chunks


def save_index(include_sessions: bool) -> dict:
    chunks = build_chunks(include_sessions=include_sessions)
    data = {
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "kind": "sparse-hashed-ngram-v1",
        "dim": DIM,
        "include_sessions": include_sessions,
        "count": len(chunks),
        "chunks": [asdict(chunk) for chunk in chunks],
    }
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
    return data


def load_or_build(include_sessions: bool, rebuild: bool) -> dict:
    if rebuild or not OUT.exists():
        return save_index(include_sessions)
    return json.loads(OUT.read_text(encoding="utf-8"))


def dot(a: dict[str, float], b: dict[str, float]) -> float:
    if len(a) > len(b):
        a, b = b, a
    return sum(v * b.get(k, 0.0) for k, v in a.items())


def search(query: str, limit: int, include_sessions: bool, rebuild: bool) -> list[dict]:
    data = load_or_build(include_sessions, rebuild)
    qvec, _ = normalize(features(query))
    now = datetime.now(timezone.utc).timestamp()
    ranked = []
    for chunk in data.get("chunks", []):
        score = dot(qvec, chunk["vector"]) * float(chunk.get("weight", 1.0))
        ts = chunk.get("timestamp")
        if isinstance(ts, (int, float)):
            age_days = max(0.0, (now - ts) / 86400)
            score *= 1.0 + 0.15 / (1.0 + age_days / 14.0)
        if score > 0:
            ranked.append((score, chunk))
    ranked.sort(key=lambda item: item[0], reverse=True)
    return [
        {
            "score": round(score, 4),
            "source": chunk["source"],
            "path": chunk["path"],
            "excerpt": " ".join(chunk["text"].split())[:700],
        }
        for score, chunk in ranked[:limit]
    ]


def main() -> None:
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="cmd", required=True)
    build = sub.add_parser("build")
    build.add_argument("--sessions", action="store_true")
    query = sub.add_parser("query")
    query.add_argument("query", nargs="+")
    query.add_argument("--limit", type=int, default=8)
    query.add_argument("--sessions", action="store_true")
    query.add_argument("--rebuild", action="store_true")
    query.add_argument("--json", action="store_true")
    args = parser.parse_args()

    if args.cmd == "build":
        data = save_index(args.sessions)
        print(OUT)
        print(json.dumps({k: data[k] for k in ("kind", "dim", "count", "include_sessions")}, ensure_ascii=False, indent=2))
        return

    hits = search(" ".join(args.query), args.limit, args.sessions, args.rebuild)
    if args.json:
        print(json.dumps({"query": " ".join(args.query), "hits": hits}, ensure_ascii=False, indent=2))
    else:
        print(f"query: {' '.join(args.query)}")
        print(f"semantic_hits: {len(hits)}")
        for hit in hits:
            print()
            print(f"[{hit['score']:.4f}] {hit['source']} {hit['path']}")
            print(hit["excerpt"])


if __name__ == "__main__":
    main()
