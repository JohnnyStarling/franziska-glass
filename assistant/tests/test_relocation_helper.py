"""Exercise residual embedded BC names that exact-name JarJar rewriting misses."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[1]


class RelocationHelperTests(unittest.TestCase):
    def test_embedded_field_and_method_strings_use_integration_namespace(self):
        java = Path(os.environ["JAVA_HOME_BUILD"])/"bin"
        asm = ROOT/"deps/asm-9.10.1.jar"
        self.assertTrue(asm.is_file(), "fetch checksum-locked dependencies first")
        helper = ROOT/"glass-source/compatibility/tls15/android-probe/build-tools/RelocateBcStrings.java"
        self.assertEqual(helper.read_bytes(), (ROOT/"pixel-source/compatibility/tls15/android-probe/build-tools/RelocateBcStrings.java").read_bytes())
        (ROOT/"out").mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix="relocation-test-", dir=ROOT/"out") as temporary:
            out = Path(temporary)
            source = out/"Fixture.java"
            source.write_text("""
public final class Fixture {
    public static final String FILTER="org.bouncycastle.jcajce.CompositePublicKey|org.bouncycastle.jcajce.CompositePrivateKey";
    public static String message() { return "F2M disabled by org.bouncycastle.ec.disable"; }
    public static String unchanged() { return "org.example.unrelated"; }
}
""")
            subprocess.run([str(java/"javac"), "-d", str(out), str(source)], check=True)
            with zipfile.ZipFile(out/"input.jar", "w") as archive:
                archive.write(out/"Fixture.class", "Fixture.class")
            subprocess.run([str(java/"javac"), "-cp", str(asm), "-d", str(out), str(helper)], check=True)
            subprocess.run([str(java/"java"), "-cp", str(out)+":"+str(asm),
                            "lab.franziska.tls15probe.build.RelocateBcStrings",
                            str(out/"input.jar"), str(out/"output.jar")], check=True)
            with zipfile.ZipFile(out/"output.jar") as archive:
                data = archive.read("Fixture.class")
            self.assertNotIn(b"org.bouncycastle", data)
            self.assertNotIn(b"lab.franziska.tls15probe.shaded", data)
            self.assertIn(b"com.franziska.glass.compat.tls15.shaded.jcajce.CompositePublicKey|com.franziska.glass.compat.tls15.shaded.jcajce.CompositePrivateKey", data)
            self.assertIn(b"F2M disabled by com.franziska.glass.compat.tls15.shaded.ec.disable", data)
            self.assertIn(b"org.example.unrelated", data)


if __name__ == "__main__":
    unittest.main()
