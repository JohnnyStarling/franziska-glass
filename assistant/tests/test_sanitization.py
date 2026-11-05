"""Host JVM tests for the disabled broker; no Android runtime or device operations."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
PKG = ROOT / "pixel-source/android/pixel-bridge/src/lab/franziska/pixelbridge"


class SanitizationTests(unittest.TestCase):
    def test_broker_request_and_credentials_fail_closed_on_host_jvm(self):
        java_home = os.environ.get("JAVA_HOME_BUILD")
        self.assertTrue(java_home, "Set JAVA_HOME_BUILD for the host-only Java regression")
        out = ROOT / "out"
        out.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix="broker-test-", dir=out) as tmp:
            tmp = Path(tmp)
            context = tmp / "Context.java"
            context.write_text("package android.content; public class Context {}\n")
            wire = tmp / "MyGlassWire.java"
            wire.write_text("package lab.franziska.pixelbridge; final class MyGlassWire { static final class Command {} }\n")
            test = tmp / "BrokerTest.java"
            test.write_text("""
package lab.franziska.pixelbridge;
import java.io.IOException;
public final class BrokerTest {
    interface Attempt { void run() throws Exception; }
    static void blocked(Attempt attempt) throws Exception {
        try { attempt.run(); throw new AssertionError("operation admitted"); }
        catch(IOException expected) {
            if(!"MYGLASS_UNCONFIGURED".equals(expected.getMessage())) throw expected;
            if(expected instanceof MyGlassApi.Failure && ((MyGlassApi.Failure)expected).httpStatus!=0)
                throw new AssertionError("network status on unconfigured broker");
        }
    }
    public static void main(String[] args) throws Exception {
        final char[] token="test-only-token-not-a-real-credential".toCharArray();
        final MyGlassApi api=new MyGlassApi(token);
        blocked(new Attempt(){public void run()throws Exception{api.heartbeat(null,false,"idle");}});
        blocked(new Attempt(){public void run()throws Exception{api.claim();}});
        blocked(new Attempt(){public void run()throws Exception{api.claim(false);}});
        blocked(new Attempt(){public void run()throws Exception{api.ack(null,"accepted",null);}});
        if(MyGlassCredentials.present(null)) throw new AssertionError("credential present");
        blocked(new Attempt(){public void run()throws Exception{MyGlassCredentials.save(null,token);}});
        for(char c:token) if(c!='\\0') throw new AssertionError("token not wiped");
        blocked(new Attempt(){public void run()throws Exception{MyGlassCredentials.load(null);}});
        blocked(new Attempt(){public void run()throws Exception{MyGlassCredentials.importPending(null);}});
        System.out.println("PASS broker API and credential paths fail closed; supplied token wiped");
    }
}
""")
            subprocess.run([str(Path(java_home)/"bin/javac"), "-d", str(tmp), str(context), str(wire),
                            str(PKG/"MyGlassApi.java"), str(PKG/"MyGlassCredentials.java"), str(test)], check=True)
            subprocess.run([str(Path(java_home)/"bin/java"), "-cp", str(tmp),
                            "lab.franziska.pixelbridge.BrokerTest"], check=True)

    def test_no_enrollment_controls_and_no_network_request_implementation(self):
        activity = (PKG/"MyGlassSetupActivity.java").read_text()
        self.assertNotIn("EditText", activity)
        self.assertNotIn("MyGlassCredentials.", activity)
        self.assertNotIn("setEnabled(this,true)", activity)
        api = (PKG/"MyGlassApi.java").read_text()
        self.assertNotIn("java.net", api)
        self.assertNotIn("https://", api)
        credentials = (PKG/"MyGlassCredentials.java").read_text()
        self.assertNotIn("pending.token", credentials)
        self.assertNotIn("updateAAD", credentials)
        worker = (PKG/"MyGlassWorker.java").read_text()
        self.assertIn("return false; // Broker is unconfigured", worker)
        self.assertIn("if(enabled) return false;", worker)

    def test_roles_separate_and_core_clean_defaults_preserved(self):
        for role, count in [("glass", 83), ("pixel", 102)]:
            source = ROOT/(role+"-source")
            self.assertEqual(len([p for p in source.rglob("*") if p.is_file()]), count)
            manifest = source/("android/glass-client/AndroidManifest.xml" if role=="glass"
                               else "android/pixel-bridge/AndroidManifest.xml")
            self.assertIn('android:debuggable="true"', manifest.read_text())
            notice = source/"compatibility/tls15/android-probe/assets/THIRD_PARTY_NOTICES.txt"
            self.assertIn("2000-2026 The Legion", notice.read_text())
            helper = source/"compatibility/tls15/android-probe/build-tools/RelocateBcStrings.java"
            self.assertIn('TO = "com.franziska.glass.compat.tls15.shaded"', helper.read_text())
        rel = "protocol/src/lab/franziska/protocol/AssistWire.java"
        self.assertNotEqual((ROOT/"glass-source"/rel).read_bytes(), (ROOT/"pixel-source"/rel).read_bytes())


if __name__ == "__main__":
    unittest.main()
