#!/usr/bin/env python3
"""Host-only unsigned APK qualification. No ADB, devices, sensors or signing."""
import hashlib
import json
import os
from pathlib import Path
import re
import struct
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
ANDROID = "{http://schemas.android.com/apk/res/android}"


def require(ok, message):
    if not ok:
        raise SystemExit(message)


def dex_tables(data):
    require(data.startswith(b"dex\n"), "not DEX")
    count, offset = struct.unpack_from("<II", data, 56)
    strings = []
    for index in range(count):
        pos = struct.unpack_from("<I", data, offset + 4 * index)[0]
        while data[pos] & 128:
            pos += 1
        pos += 1
        # Namespace checks use ASCII; unrelated MUTF-8 strings need not decode fully.
        strings.append(data[pos:data.index(0, pos)].decode("utf-8", "replace"))
    count, offset = struct.unpack_from("<II", data, 64)
    types = [strings[struct.unpack_from("<I", data, offset + 4 * i)[0]] for i in range(count)]
    count, offset = struct.unpack_from("<II", data, 96)
    classes = [types[struct.unpack_from("<I", data, offset + 32 * i)[0]] for i in range(count)]
    return strings, classes


def main():
    require(len(sys.argv) == 3 and sys.argv[1] in ("glass", "pixel"),
            "usage: check-artifact.py glass|pixel BUILD_OUTPUT_DIR")
    role = sys.argv[1]
    output = Path(sys.argv[2]).resolve()
    apk = output / "candidate-unsigned.apk"
    sdk = Path(os.environ["ANDROID_SDK_ROOT"])
    java = Path(os.environ["JAVA_HOME_BUILD"]) / "bin/java"
    bt = sdk / "build-tools/36.0.0"
    app = "glass-client" if role == "glass" else "pixel-bridge"
    source = ROOT / (role + "-source")
    manifest = ET.parse(source / "android" / app / "AndroidManifest.xml").getroot()
    badging = subprocess.check_output([str(bt/"aapt"), "dump", "badging", str(apk)], text=True)
    output.joinpath("manifest-badging.txt").write_text(badging)
    package = re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging).groups()
    require(package == (manifest.attrib["package"], manifest.attrib[ANDROID+"versionCode"],
                        manifest.attrib[ANDROID+"versionName"]), "package/version differs")
    uses_sdk = manifest.find("uses-sdk")
    min_sdk = re.search(r"sdkVersion:'([^']+)'", badging).group(1)
    target_sdk = re.search(r"targetSdkVersion:'([^']+)'", badging).group(1)
    require((min_sdk, target_sdk) == (uses_sdk.attrib[ANDROID+"minSdkVersion"],
                                    uses_sdk.attrib[ANDROID+"targetSdkVersion"]), "SDK metadata differs")
    require("application-debuggable" in badging, "bootstrap requires debug/run-as")
    expected_permissions = sorted((p.attrib[ANDROID+"name"], p.attrib.get(ANDROID+"maxSdkVersion"))
                                  for p in manifest.findall("uses-permission"))
    actual_permissions = sorted((name, maximum or None) for name, maximum in
                                re.findall(r"uses-permission: name='([^']+)'(?: maxSdkVersion='([^']+)')?", badging))
    require(actual_permissions == expected_permissions, "permissions differ from audited manifest")
    notice_path = "compatibility/tls15/android-probe/assets/THIRD_PARTY_NOTICES.txt"
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        require([n for n in names if re.fullmatch(r"classes[0-9]*\.dex", n)] == ["classes.dex"],
                "single DEX required")
        require(not any(n.startswith("lib/") for n in names), "unexpected native library")
        require(not any(re.fullmatch(r"META-INF/.*\.(SF|RSA|DSA|EC)", n, re.I) for n in names),
                "unexpected JAR signature")
        notice = archive.read("assets/THIRD_PARTY_NOTICES.txt")
        require(notice == (source/notice_path).read_bytes(), "packaged notice differs")
        require(b"2000-2026 The Legion" in notice and b"Permission is hereby granted" in notice,
                "missing correct BC copyright/permission")
        dex = archive.read("classes.dex")
    strings, classes = dex_tables(dex)
    for value in strings:
        require(not any(old in value for old in ("org.bouncycastle", "org/bouncycastle/",
                                                 "lab.franziska.tls15probe.shaded")),
                "old BC class/string relocation namespace remains")
    prefix = "Lcom/franziska/glass/compat/tls15/shaded/"
    shaded = [c for c in classes if c.startswith(prefix)]
    require(shaded, "no relocated BC definitions")
    for name in ("BDS", "BDSTreeHash", "XMSSNode"):
        dotted = "com.franziska.glass.compat.tls15.shaded.crypto.signers.xmss." + name
        require(dotted in strings and "L"+dotted.replace(".", "/")+";" in classes,
                "reflective allowlist target is absent from DEX")
    require("Lcom/franziska/glass/compat/tls15/shaded/crypto/signers/xmss/XMSSUtil$CheckingStream;" in classes,
            "missing checked reflection path")
    subprocess.run([str(bt/"zipalign"), "-c", "4", str(apk)], check=True, stdout=subprocess.DEVNULL)
    verify = subprocess.run([str(java), "-jar", str(bt/"lib/apksigner.jar"), "verify", "--verbose", str(apk)],
                            capture_output=True, text=True)
    verify_output = verify.stdout + verify.stderr
    require(verify.returncode != 0 and
            ("Missing META-INF/MANIFEST.MF" in verify_output or "No signatures" in verify_output),
            "expected specifically unsigned APK, not a signature-tool failure")
    output.joinpath("unsigned-verification.txt").write_text(verify_output)
    mapping = output/(".tmp-"+role)/"R8_MAPPING.txt"
    result = dict(role=role, apk=str(apk.name), bytes=apk.stat().st_size,
                  sha256=hashlib.sha256(apk.read_bytes()).hexdigest(), package=package[0],
                  version_code=package[1], version_name=package[2], min_sdk=min_sdk,
                  target_sdk=target_sdk, debuggable=True, permissions=actual_permissions,
                  dex_count=1, dex_sha256=hashlib.sha256(dex).hexdigest(), dex_classes=len(classes),
                  shaded_bc_classes=len(shaded), runtime_notice_sha256=hashlib.sha256(notice).hexdigest(),
                  mapping_sha256=hashlib.sha256(mapping.read_bytes()).hexdigest(),
                  aligned=True, unsigned=True, native_libraries=0, relocation_checks="PASS",
                  device_verification="NOT_PERFORMED")
    output.joinpath("qualification.json").write_text(json.dumps(result, indent=2)+"\n")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
