#!/bin/sh
# Frozen direct Android build: unsigned output only; no network, devices or signing.
set -eu
umask 077
[ "$#" -eq 3 ] || { echo 'usage: build-frozen.sh SOURCE_ROOT glass|pixel FRESH_OUTPUT_DIR' >&2; exit 2; }
RELEASE=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
ROOT=$(CDPATH= cd -- "$1" && pwd)
TARGET=$2
case "$TARGET" in glass|pixel) ;; *) echo 'target must be glass or pixel' >&2; exit 2 ;; esac
OUT=$(python3 -c 'import pathlib,sys; print(pathlib.Path(sys.argv[1]).absolute())' "$3")
: "${JAVA_HOME_BUILD:?Set JAVA_HOME_BUILD to the JDK 21.0.11 home}"
: "${ANDROID_SDK_ROOT:?Set ANDROID_SDK_ROOT to the Android SDK root}"
JAVA_HOME=$JAVA_HOME_BUILD
export JAVA_HOME
unset JAVA_TOOL_OPTIONS _JAVA_OPTIONS JDK_JAVA_OPTIONS JAVA_OPTS R8_OPTS CLASSPATH
SDK=$ANDROID_SDK_ROOT
BT="$SDK/build-tools/36.0.0"
ANDROID_JAR="$SDK/platforms/android-36/android.jar"
# Install cmdline-tools;20.0, or explicitly point to that version's verified R8 jar.
R8_JAR=${R8_JAR_BUILD:-"$SDK/cmdline-tools/20.0/lib/r8.jar"}
TLS="$ROOT/compatibility/tls15"
DEPS="$RELEASE/deps"
BC_VERSION=1.86
for f in "$BT/aapt" "$BT/zipalign" "$ANDROID_JAR" "$R8_JAR" "$JAVA_HOME_BUILD/bin/javac" "$JAVA_HOME_BUILD/bin/java" "$JAVA_HOME_BUILD/bin/jar"; do
  test -f "$f" || { echo "missing tool: $f" >&2; exit 1; }
done
"$JAVA_HOME_BUILD/bin/javac" -version 2>&1 | grep '^javac 21[.]0[.]11$' >/dev/null || { echo 'JDK 21.0.11 required' >&2; exit 20; }
grep 'Pkg.Revision=36.0.0' "$BT/source.properties" >/dev/null || { echo 'Android build-tools 36.0.0 required' >&2; exit 20; }
python3 - "$ANDROID_JAR" "$R8_JAR" <<'PYTOOLS'
import hashlib, pathlib, sys
for path, digest in zip(sys.argv[1:], ['d9eb9da824d9e247a352f570f01e1169e725b2954bca9e283a71786c59b59f9a', 'a561da8b3d2419f3c0b1936546f6a756790112653f834563f65cebc4fce0bddd']):
    if hashlib.sha256(pathlib.Path(path).read_bytes()).hexdigest() != digest:
        raise SystemExit('toolchain hash mismatch: ' + pathlib.Path(path).name)
PYTOOLS
sh "$RELEASE/scripts/fetch-deps.sh" --verify
BC_CP="$DEPS/bcprov-jdk15to18-$BC_VERSION.jar:$DEPS/bcutil-jdk15to18-$BC_VERSION.jar:$DEPS/bctls-jdk15to18-$BC_VERSION.jar:$DEPS/bcpkix-jdk15to18-$BC_VERSION.jar"
JARJAR_CP="$DEPS/jarjar-1.18.1.jar:$DEPS/asm-9.10.1.jar:$DEPS/asm-commons-9.10.1.jar:$DEPS/asm-tree-9.10.1.jar"
test ! -e "$OUT" || exit 22
mkdir "$OUT"

build_one() {
  name=$1 min=$2 app=$3 apk=$4
  tmp="$OUT/.tmp-$name"
  test ! -e "$tmp" || { echo "Build staging already exists; reconcile before retry" >&2; exit 22; }
  mkdir "$tmp"
  mkdir "$tmp/classes" "$tmp/dex" "$tmp/merge" "$tmp/build-tools"
  "$BT/aapt" package -f -M "$app/AndroidManifest.xml" -A "$TLS/android-probe/assets" -S "$app/res" -I "$ANDROID_JAR" -F "$tmp/resources.apk"
  python3 - "$ROOT" "$TLS" "$app" "$tmp/sources.list" <<'PYSOURCES'
import pathlib, sys
root, tls, app, output = map(pathlib.Path, sys.argv[1:])
paths = sorted(p for directory in [root/'protocol/src', tls/'integration/src', app/'src'] for p in directory.rglob('*.java'))
paths.append(tls/'src/main/java/com/franziska/glass/compat/tls15/PinnedTlsTransport.java')
# javac argfile quoting also supports checkouts whose path contains spaces.
output.write_text(''.join('"' + str(p).replace('\\', '\\\\').replace('"', '\\"') + '"\n' for p in paths))
PYSOURCES
  "$JAVA_HOME_BUILD/bin/javac" -encoding UTF-8 -source 8 -target 8 -Xlint:-options -bootclasspath "$ANDROID_JAR" -classpath "$BC_CP" -d "$tmp/classes" @"$tmp/sources.list"
  "$JAVA_HOME_BUILD/bin/jar" cf "$tmp/classes.jar" -C "$tmp/classes" .
  for input in "$DEPS/bcprov-jdk15to18-$BC_VERSION.jar" "$DEPS/bcutil-jdk15to18-$BC_VERSION.jar" "$DEPS/bctls-jdk15to18-$BC_VERSION.jar" "$DEPS/bcpkix-jdk15to18-$BC_VERSION.jar" "$tmp/classes.jar"; do (cd "$tmp/merge" && "$JAVA_HOME_BUILD/bin/jar" xf "$input"); done
  find "$tmp/merge/META-INF" -type f \( -name '*.SF' -o -name '*.RSA' -o -name '*.DSA' -o -name 'module-info.class' \) -delete 2>/dev/null || true
  find "$tmp/merge" -name 'module-info.class' -delete
  "$JAVA_HOME_BUILD/bin/jar" cf "$tmp/unrelocated.jar" -C "$tmp/merge" .
  "$JAVA_HOME_BUILD/bin/java" -cp "$JARJAR_CP" com.eed3si9n.jarjar.Main process "$TLS/integration/relocation.rules" "$tmp/unrelocated.jar" "$tmp/relocated-raw.jar"
  "$JAVA_HOME_BUILD/bin/javac" -encoding UTF-8 -source 8 -target 8 -Xlint:-options -classpath "$DEPS/asm-9.10.1.jar" -d "$tmp/build-tools" "$TLS/android-probe/build-tools/RelocateBcStrings.java"
  "$JAVA_HOME_BUILD/bin/java" -cp "$tmp/build-tools:$DEPS/asm-9.10.1.jar" lab.franziska.tls15probe.build.RelocateBcStrings "$tmp/relocated-raw.jar" "$tmp/relocated.jar"
  if "$JAVA_HOME_BUILD/bin/jar" tf "$tmp/relocated.jar" | grep '^org/bouncycastle/' >/dev/null; then echo 'BC relocation failed' >&2; exit 30; fi
  "$JAVA_HOME_BUILD/bin/java" -Xmx1536m -XX:ActiveProcessorCount=2 -cp "$R8_JAR" com.android.tools.r8.R8 --release --min-api "$min" --lib "$ANDROID_JAR" --pg-conf "$TLS/integration/r8-rules.pro" --pg-map-output "$tmp/R8_MAPPING.txt" --output "$tmp/dex" "$tmp/relocated.jar"
  test "$(find "$tmp/dex" -name 'classes*.dex' | wc -l | tr -d ' ')" = 1 || { echo 'single dex required' >&2; exit 40; }
  cp "$tmp/resources.apk" "$tmp/unsigned.apk"
  (cd "$tmp/dex" && "$BT/aapt" add "$tmp/unsigned.apk" classes.dex >/dev/null)
  "$BT/zipalign" -f 4 "$tmp/unsigned.apk" "$tmp/aligned.apk"
  "$BT/zipalign" -c 4 "$tmp/aligned.apk"
  cp "$tmp/aligned.apk" "$OUT/$apk"

}

case "$TARGET" in
 glass) build_one glass 15 "$ROOT/android/glass-client" candidate-unsigned.apk ;;
 pixel) build_one pixel 23 "$ROOT/android/pixel-bridge" candidate-unsigned.apk ;;
 *) exit 2 ;;
esac
