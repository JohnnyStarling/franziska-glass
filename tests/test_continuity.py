import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

REPO = Path(__file__).resolve().parent.parent


class CommunityContinuityTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="franziska-test-")
        self.addCleanup(self.temp.cleanup)
        self.workspace = Path(self.temp.name) / "workspace"
        self.run_cmd([sys.executable, str(REPO / "scripts/init-workspace.py"), "--workspace", str(self.workspace)])
        self.env = dict(os.environ, FRANZISKA_WORKSPACE=str(self.workspace))
        self.env.pop("FRANZISKA_SESSIONS", None)

    def run_cmd(self, args, **kwargs):
        p = subprocess.run(args, capture_output=True, text=True, **kwargs)
        self.assertEqual(p.returncode, 0, p.stderr)
        return p.stdout

    def test_fresh_workspace_is_portable_and_has_no_author_data(self):
        self.assertTrue((self.workspace / "IDENTITY.md").is_file())
        text = self.run_cmd([sys.executable, str(self.workspace / "continuity/recall.py"), "example project", "--json"], cwd=self.workspace, env=self.env)
        pack = json.loads(text)
        self.assertTrue(pack["search_hits"])
        self.assertIn("continuity/projects/example-project.md", pack["project_cards"])
        for hit in pack["search_hits"] + pack["semantic_hits"]:
            Path(hit["path"]).resolve().relative_to(self.workspace.resolve())

    def test_initializer_never_overwrites_existing_content(self):
        p = self.workspace / "USER.md"
        p.write_text("existing user data")
        result = subprocess.run([sys.executable, str(REPO / "scripts/init-workspace.py"), "--workspace", str(self.workspace)], capture_output=True, text=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(p.read_text(), "existing user data")

    def test_retrieval_finds_new_project_and_sparse_features(self):
        (self.workspace / "continuity/projects/skyline.md").write_text("# Skyline\n\nDecision: the copper telescope uses the eastern platform.")
        lexical = self.run_cmd([sys.executable, str(self.workspace / "continuity/search.py"), "copper telescope"], env=self.env)
        self.assertIn("skyline.md", lexical)
        out = self.run_cmd([sys.executable, str(self.workspace / "continuity/semantic_index.py"), "query", "copper telescope", "--rebuild", "--json"], env=self.env)
        self.assertTrue(any("skyline.md" in h["path"] for h in json.loads(out)["hits"]))
        index = json.loads((self.workspace / "continuity/SEMANTIC_INDEX.json").read_text())
        self.assertEqual(index["kind"], "sparse-hashed-ngram-v1")
        self.assertEqual(index["dim"], 4096)

    def test_no_raw_history_without_explicit_export_directory(self):
        out = self.run_cmd([sys.executable, str(self.workspace / "continuity/recall.py"), "previous decisions", "--sessions", "--json"], env=self.env)
        pack = json.loads(out)
        self.assertFalse(any(h["source"] in ("session", "telegram") for h in pack["session_search_hits"]))

    def test_code_default_root_is_the_initialized_workspace(self):
        clean = dict(os.environ)
        clean.pop("FRANZISKA_WORKSPACE", None)
        clean.pop("FRANZISKA_SESSIONS", None)
        out = self.run_cmd([sys.executable, str(self.workspace / "continuity/recall.py"), "example project", "--json"], cwd=self.temp.name, env=clean)
        self.assertTrue(json.loads(out)["search_hits"])


if __name__ == "__main__":
    unittest.main()
