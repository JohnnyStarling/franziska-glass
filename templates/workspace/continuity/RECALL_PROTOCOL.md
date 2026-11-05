# Recall protocol

1. Identify whether the question is about current work, earlier decisions, preferences or a correction.
2. Search the continuity corpus; read the highest-relevance project/pinned/handoff sources.
3. Use source evidence, not a plausible reconstruction. If evidence conflicts, record and resolve the correction.
4. Update the current project record and handoff when work changes.
5. Rebuild the sparse index after corpus changes: python3 continuity/semantic_index.py build.

The search implementation is lexical plus local hashed character-ngram vectors, not a neural embedding service. Optional legacy conversation exports require an explicit FRANZISKA_SESSIONS directory.
