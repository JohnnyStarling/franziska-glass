"""Host-only public-trust tests. All ADB/OpenSSL transport is mocked; no devices."""
import contextlib
import hashlib
import io
import json
import os
from pathlib import Path
import shlex
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
import public_bootstrap as b
from validate_public_identity import parse_public_identity

CERT = b"\x30\x00"  # synthetic public bytes, not a claimed X509 certificate
PIN = hashlib.sha256(CERT).hexdigest()


def export(role="glass"):
    return (json.dumps(dict(schema=1, role=role, package=b.ROLES[role][0],
                            leaf_sha256=PIN, certificate_der_hex=CERT.hex()),
                       separators=(",", ":")) + "\n").encode()


class Transport:
    def __init__(self, existing=None, wrong_role=False, bad_der=False, bad_readback=False):
        self.existing = existing
        self.wrong_role = wrong_role
        self.bad_der = bad_der
        self.bad_readback = bad_readback
        self.calls = []
        self.written = {}
        self.wanted = {role: b.trust_json(PIN, "192.168.10.1", 38443, role == "pixel").encode()
                       for role in b.ROLES}

    def __call__(self, argv, label, public_input=None, **kwargs):
        self.calls.append((argv, public_input))
        if public_input is not None:
            assert argv[1:] == ["x509", "-inform", "DER", "-outform", "DER"]
            return b"not DER" if self.bad_der else public_input
        assert argv[1] == "-P" and argv[2] == "5037"
        role = {"TEST_GLASS_USB": "glass", "TEST_PIXEL_USB": "pixel"}[argv[4]]
        if argv[5:] == ["get-state"]:
            return b"device\n"
        if argv[5:] == ["shell", "getprop ro.product.device"]:
            device = "wrong" if self.wrong_role and role == "pixel" else b.ROLES[role][1]
            return (device + "\n").encode()
        command = shlex.split(argv[6])
        assert command[:3] == ["run-as", b.ROLES[role][0], "sh"] and command[3] == "-c"
        script = command[4]
        if b.WRITE_OK in script:
            # Validate exact quote-safe payload; we do not execute a remote shell.
            assert script == b.write_script(self.wanted[role].decode())
            self.written[role] = self.wanted[role]
            return (b.WRITE_OK + "\n").encode()
        if "identity-public.json" in script:
            return export(role) + (b.READ_OK + "\n").encode()
        prior = self.written.get(role, self.existing)
        if self.bad_readback and role in self.written:
            prior = b"wrong\n"
        return (prior if prior is not None else (b.ABSENT + "\n").encode()) + (b.READ_OK + "\n").encode()


class BootstrapTests(unittest.TestCase):
    def setUp(self):
        self.env = dict(GLASSLAB_ENABLE_PUBLIC_BOOTSTRAP="OWNER_VERIFIED_USB_RUN_AS",
                        GLASSLAB_ADB_BIN=sys.executable,
                        GLASSLAB_GLASS_SERIAL="TEST_GLASS_USB",
                        GLASSLAB_PIXEL_SERIAL="TEST_PIXEL_USB",
                        GLASSLAB_GLASS_ENDPOINT="192.168.10.1",
                        GLASSLAB_PIXEL_BIND="192.168.10.1",
                        GLASSLAB_OPENSSL_BIN="/test/mock-openssl")

    def run_main(self, transport, changes=None):
        env = self.env | (changes or {})
        output = io.StringIO()
        with patch.dict(os.environ, env, clear=True), patch.object(b, "run_bounded", transport), contextlib.redirect_stdout(output):
            b.main()
        return output.getvalue()

    def test_bootstrap_writes_only_public_trust_and_default_port(self):
        transport = Transport()
        output = self.run_main(transport)
        self.assertEqual(set(transport.written), {"glass", "pixel"})
        self.assertIn("public_writes=2", output)
        self.assertIn("network_pair=NOT_TESTED", output)
        self.assertTrue(all(data is None or data == CERT for _, data in transport.calls))

    def test_idempotent_after_interrupted_first_write(self):
        t = Transport()
        t.written["glass"] = t.wanted["glass"]
        output = self.run_main(t)
        self.assertIn("public_writes=1", output)

    def test_conflicting_prior_trust_refused_before_any_write(self):
        t = Transport(existing=b'{"schema":1}\n')
        with self.assertRaisesRegex(ValueError, "explicit revoke"):
            self.run_main(t)
        self.assertEqual(t.written, {})

    def test_wrong_pixel_role_prevents_both_writes(self):
        t = Transport(wrong_role=True)
        with self.assertRaisesRegex(ValueError, "wrong physical role"):
            self.run_main(t)
        self.assertEqual(t.written, {})

    def test_canonical_x509_validation_is_enforced_before_writes(self):
        t = Transport(bad_der=True)
        with self.assertRaisesRegex(ValueError, "canonical X509 DER"):
            self.run_main(t)
        self.assertEqual(t.written, {})

    def test_readback_failure_is_not_reported_as_success(self):
        t = Transport(bad_readback=True)
        with self.assertRaisesRegex(ValueError, "readback differs"):
            self.run_main(t)
        self.assertEqual(set(t.written), {"glass"})

    def test_invalid_inputs_never_invoke_transport(self):
        cases = [
            {"GLASSLAB_ENABLE_PUBLIC_BOOTSTRAP": ""},
            {"GLASSLAB_ADB_BIN": "relative/adb"},
            {"GLASSLAB_GLASS_SERIAL": "192.168.1.2:5555"},
            {"GLASSLAB_GLASS_SERIAL": "emulator-5554"},
            {"GLASSLAB_GLASS_SERIAL": "x;touch bad"},
            {"GLASSLAB_PIXEL_SERIAL": "TEST_GLASS_USB"},
            {"GLASSLAB_GLASS_ENDPOINT": "100.64.0.1"},
            {"GLASSLAB_GLASS_ENDPOINT": "example.invalid"},
            {"GLASSLAB_PIXEL_BIND": "192.168.10.2"},
            {"GLASSLAB_LINK_PORT": "0"},
            {"GLASSLAB_LINK_PORT": "06543"},
            {"GLASSLAB_ADB_SERVER_PORT": "65536"},
        ]
        for case in cases:
            with self.subTest(case=case):
                t = Transport()
                with self.assertRaises(ValueError):
                    self.run_main(t, case)
                self.assertEqual(t.calls, [])

    def test_ipv4_boundaries_and_canonical_form(self):
        for address in ["10.0.0.1", "172.16.0.1", "172.31.255.254", "192.168.0.1"]:
            self.assertEqual(b.private_ipv4(address), address)
        for address in ["172.15.255.255", "172.32.0.0", "127.0.0.1", "8.8.8.8",
                        "100.64.0.1", "::1", "010.0.0.1", "10.0.0.1\n", None]:
            with self.subTest(address=address), self.assertRaises(ValueError):
                b.private_ipv4(address)

    def test_trust_record_rejects_noncanonical_pin_port_role(self):
        for pin, port, server in [(PIN.upper(), 38443, False), (PIN, True, False),
                                  (PIN, 65536, False), (PIN, 38443, 1)]:
            with self.assertRaises(ValueError):
                b.trust_json(pin, "192.168.10.1", port, server)

    def test_remote_command_preserves_entire_script_as_one_argument(self):
        script = b.write_script(b.trust_json(PIN, "192.168.10.1", 38443, False))
        parsed = shlex.split(b.remote_command(b.ROLES["glass"][0], script))
        self.assertEqual(parsed, ["run-as", b.ROLES["glass"][0], "sh", "-c", script])
        self.assertNotIn("identity-private.der", script)

    def test_marker_is_mandatory(self):
        with self.assertRaises(ValueError):
            b.public_response(export())
        self.assertEqual(b.public_response(export() + (b.READ_OK + "\r\n").encode()), export())


class PublicIdentityTests(unittest.TestCase):
    def test_valid_and_final_pty_crlf(self):
        self.assertEqual(parse_public_identity(export(), "glass", b.ROLES["glass"][0])["leaf_sha256"], PIN)
        self.assertEqual(parse_public_identity(export()[:-1] + b"\r\n", "glass", b.ROLES["glass"][0])["leaf_sha256"], PIN)

    def test_pin_mismatch_and_extra_private_fields_rejected(self):
        value = json.loads(export())
        value["leaf_sha256"] = "0" * 64
        with self.assertRaisesRegex(ValueError, "pin mismatch"):
            parse_public_identity((json.dumps(value, separators=(",", ":")) + "\n").encode(),
                                  "glass", b.ROLES["glass"][0])
        value = json.loads(export())
        value["private_key"] = "not permitted"
        with self.assertRaises(ValueError):
            parse_public_identity((json.dumps(value, separators=(",", ":")) + "\n").encode(),
                                  "glass", b.ROLES["glass"][0])

    def test_noncanonical_and_duplicate_records_rejected(self):
        raw = export()
        for bad in [b"", raw + b"x", raw.replace(b'"schema":1', b'"schema":true'),
                    raw.replace(b'"schema":1', b'"schema":1,"schema":1'),
                    raw.replace(b":", b": "), b"x" * 65537,
                    raw.replace(b'"glass"', b'"pixel"')]:
            with self.subTest(raw=bad[:40]), self.assertRaises(ValueError):
                parse_public_identity(bad, "glass", b.ROLES["glass"][0])


if __name__ == "__main__":
    unittest.main()
