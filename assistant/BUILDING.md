# Build from exported source

The frozen recipe is direct **aapt → javac → JarJar → ASM string relocation → R8 → zipalign**. It is not a Gradle project. Build commands never discover devices, use ADB, sign, contact a Gateway or load owner credentials.

## Public prerequisites

Install a JDK **21.0.11**, Python 3 (stdlib), and Android SDK packages `platforms;android-36`, `build-tools;36.0.0`, `cmdline-tools;20.0`. Use the SDK manager's normal license workflow. Provide your own toolchain; no SDK/JDK binaries are redistributed here. Android compile stubs and the actual R8 jar are checksum locked in [toolchain-lock.json](toolchain-lock.json). R8 is **9.0.32**, not the unrelated D8 version.

Set absolute tool paths yourself; these are placeholders:

```sh
export JAVA_HOME_BUILD=/absolute/path/to/jdk-21.0.11
export ANDROID_SDK_ROOT=/absolute/path/to/android-sdk
# Only if your installed cmdline-tools 20.0 directory is named differently:
export R8_JAR_BUILD="$ANDROID_SDK_ROOT/cmdline-tools/20.0/lib/r8.jar"
```

The default R8 location is already `cmdline-tools/20.0/lib/r8.jar`. A mutable directory name such as `latest` is acceptable only as an explicit local override containing the **same checksum-locked jar**; it does not relax the version pin. The build clears Java option injection variables, uses source/target 8 and bounds R8 to 1536 MiB / two active processors. Shell, Python, JDK and SDK tools must be executable on your host.

## Download pinned public dependencies

From this `assistant/` directory:

```sh
sh scripts/fetch-deps.sh
sh scripts/fetch-deps.sh --verify
```

Exactly eight jars are fetched from HTTPS Maven Central and checked against [deps/SHA256SUMS](deps/SHA256SUMS). Downloads are validated **before** becoming usable files. Existing mismatched files fail closed; the script does not replace them or update the lock. No dependency updates or private Maven repositories are involved. The build itself is offline and requires the verified downloads already present.

## Build both roles

```sh
mkdir -p out
sh scripts/build-frozen.sh "$PWD/glass-source" glass "$PWD/out/glass-build"
sh scripts/build-frozen.sh "$PWD/pixel-source" pixel "$PWD/out/pixel-build"
```

Each fresh output directory contains `candidate-unsigned.apk` and a role-specific hidden staging directory with `R8_MAPPING.txt`, merged/relocated jars and one DEX. Existing output directories are refused. Choose a new output name to rerun; source roots are never written. Paths containing spaces are supported. No original private project checkout is needed.

The runtime BC jars remain version **1.86**. JarJar maps classes to `com.franziska.glass.compat.tls15.shaded`; the ASM pass maps dotted string constants into the same namespace. Original jar signatures/module descriptors are removed from the merged input; dependency notices are retained and the exact BC notice is packaged in both APKs.

## Host-only checks

```sh
python3 -m unittest discover -s tests -v
"$JAVA_HOME_BUILD/bin/javac" -d out tests/RelocationCheck.java
"$JAVA_HOME_BUILD/bin/java" -cp "out:out/glass-build/.tmp-glass/relocated.jar" RelocationCheck
"$JAVA_HOME_BUILD/bin/java" -cp "out:out/pixel-build/.tmp-pixel/relocated.jar" RelocationCheck
python3 scripts/check-artifact.py glass out/glass-build
python3 scripts/check-artifact.py pixel out/pixel-build
```

The Java classpath separator above is POSIX `:`. The bootstrap test transport is mocked; synthetic public certificate bytes in fixtures are not a certificate acceptance claim. The broker regression compiles/runs against tiny host stubs, never an Android device.

The artifact checker compares package/version/min/target/permission metadata to source, requires the debug flag, exact packaged notice, no native libraries/signatures, a single DEX, coherent BC namespaces and reachable XMSS reflective allowlist target definitions. The JVM reflection test exercises BC's actual checked deserialization roundtrip, not merely a string search.

## Signing is separate

These unsigned APKs cannot be installed until signed. A user/publisher must privately create a dedicated signing identity and sign each aligned APK using `apksigner` with **v1 and v2 enabled** (Glass requires v1), v3 disabled for the reference lineage; verify with `apksigner verify --verbose --print-certs` and publish artifact hashes/certificate fingerprints if distributing binaries. No existing owner's key is required or authorized by this build.

For a fresh personal signing identity, run locally (passwords are prompted, not supplied in arguments):

```sh
umask 077
mkdir -p "$HOME/.local/share/franziska-signing"
"$JAVA_HOME_BUILD/bin/keytool" -genkeypair \
  -keystore "$HOME/.local/share/franziska-signing/release.p12" \
  -storetype PKCS12 -alias franziska -keyalg RSA -keysize 3072 \
  -sigalg SHA256withRSA -validity 10000 \
  -dname "CN=Franziska community build"

"$ANDROID_SDK_ROOT/build-tools/36.0.0/apksigner" sign \
  --ks "$HOME/.local/share/franziska-signing/release.p12" --ks-key-alias franziska \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled false \
  --v4-signing-enabled false --out out/glass-signed.apk \
  out/glass-build/candidate-unsigned.apk

"$ANDROID_SDK_ROOT/build-tools/36.0.0/apksigner" sign \
  --ks "$HOME/.local/share/franziska-signing/release.p12" --ks-key-alias franziska \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled false \
  --v4-signing-enabled false --out out/pixel-signed.apk \
  out/pixel-build/candidate-unsigned.apk

"$ANDROID_SDK_ROOT/build-tools/36.0.0/apksigner" verify --verbose --print-certs out/glass-signed.apk
"$ANDROID_SDK_ROOT/build-tools/36.0.0/apksigner" verify --verbose --print-certs out/pixel-signed.apk
```

Keep the signing file outside the repository and retain it for your own updates. These commands are a user-run signing guide; they were not executed with the author's key or on devices during source qualification.

A different key cannot update a same-package installation already signed by somebody else. Do not routinely uninstall/clear existing app data or change package IDs to work around that: existing identity/trust/data migration needs an explicit plan. Package IDs are coupled to providers and bootstrap role checks.

Qualification was performed on macOS x86_64 with the locked toolchain and publicly fetched dependencies. Portability to other POSIX SDK/JDK installations is designed but not independently tested. ZIP timestamps/host packaging mean **byte-for-byte reproducibility is not claimed**.
