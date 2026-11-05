"""Dependency tampering must fail before any network request or build."""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class DependencyLockTests(unittest.TestCase):
    def test_corrupt_existing_jar_is_refused_without_redownload(self):
        out = ROOT / "out"
        out.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix="dep-test-", dir=out) as temporary:
            root = Path(temporary)
            (root/"scripts").mkdir()
            (root/"deps").mkdir()
            shutil.copyfile(ROOT/"scripts/fetch-deps.sh", root/"scripts/fetch-deps.sh")
            shutil.copyfile(ROOT/"deps/SHA256SUMS", root/"deps/SHA256SUMS")
            corrupt = root/"deps/bcprov-jdk15to18-1.86.jar"
            corrupt.write_bytes(b"not the pinned public jar")
            result = subprocess.run(["sh", str(root/"scripts/fetch-deps.sh")], capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("dependency checksum mismatch", result.stderr)
            self.assertEqual(corrupt.read_bytes(), b"not the pinned public jar")
            self.assertEqual(len(list((root/"deps").glob("*.jar"))), 1)

    def test_verify_only_missing_deps_does_not_fetch(self):
        out = ROOT / "out"
        out.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix="dep-test-", dir=out) as temporary:
            root = Path(temporary)
            (root/"scripts").mkdir()
            (root/"deps").mkdir()
            shutil.copyfile(ROOT/"scripts/fetch-deps.sh", root/"scripts/fetch-deps.sh")
            shutil.copyfile(ROOT/"deps/SHA256SUMS", root/"deps/SHA256SUMS")
            result = subprocess.run(["sh", str(root/"scripts/fetch-deps.sh"), "--verify"],
                                    capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("missing dependency", result.stderr)
            self.assertEqual(list((root/"deps").glob("*.jar")), [])


if __name__ == "__main__":
    unittest.main()
