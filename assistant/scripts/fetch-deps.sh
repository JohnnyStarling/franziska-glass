#!/bin/sh
# Only public pinned Maven artifacts. No device, signing, Gateway or credential access.
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
exec python3 - "$ROOT" "$@" <<'PYFETCH'
import hashlib, os, pathlib, sys, urllib.request
root = pathlib.Path(sys.argv[1])
if sys.argv[2:] not in ([], ['--verify']):
    raise SystemExit('usage: fetch-deps.sh [--verify]')
verify = sys.argv[2:] == ['--verify']
artifacts = {'bcprov-jdk15to18-1.86.jar': 'org/bouncycastle/bcprov-jdk15to18/1.86/bcprov-jdk15to18-1.86.jar', 'bcutil-jdk15to18-1.86.jar': 'org/bouncycastle/bcutil-jdk15to18/1.86/bcutil-jdk15to18-1.86.jar', 'bctls-jdk15to18-1.86.jar': 'org/bouncycastle/bctls-jdk15to18/1.86/bctls-jdk15to18-1.86.jar', 'bcpkix-jdk15to18-1.86.jar': 'org/bouncycastle/bcpkix-jdk15to18/1.86/bcpkix-jdk15to18-1.86.jar', 'jarjar-1.18.1.jar': 'com/eed3si9n/jarjar/jarjar/1.18.1/jarjar-1.18.1.jar', 'asm-9.10.1.jar': 'org/ow2/asm/asm/9.10.1/asm-9.10.1.jar', 'asm-commons-9.10.1.jar': 'org/ow2/asm/asm-commons/9.10.1/asm-commons-9.10.1.jar', 'asm-tree-9.10.1.jar': 'org/ow2/asm/asm-tree/9.10.1/asm-tree-9.10.1.jar'}
pins = {}
for line in (root / 'deps/SHA256SUMS').read_text(encoding='ascii').splitlines():
    digest, name = line.split('  ')
    if len(digest) != 64 or any(c not in '0123456789abcdef' for c in digest) or name in pins:
        raise SystemExit('invalid dependency lock')
    pins[name] = digest
if set(pins) != set(artifacts):
    raise SystemExit('dependency lock inventory mismatch')
for name, path in artifacts.items():
    target = root / 'deps' / name
    if not target.exists():
        if verify:
            raise SystemExit('missing dependency: ' + name + '; run scripts/fetch-deps.sh')
        url = 'https://repo.maven.apache.org/maven2/' + path
        with urllib.request.urlopen(url, timeout=45) as response:
            if not response.geturl().startswith('https://repo.maven.apache.org/maven2/'):
                raise SystemExit('unexpected dependency redirect')
            blob = response.read(32 * 1024 * 1024 + 1)
        if len(blob) > 32 * 1024 * 1024 or hashlib.sha256(blob).hexdigest() != pins[name]:
            raise SystemExit('download checksum mismatch: ' + name)
        temporary = target.with_suffix('.jar.part')
        with temporary.open('xb') as output:
            output.write(blob)
        temporary.replace(target)
    if target.is_symlink() or not target.is_file() or hashlib.sha256(target.read_bytes()).hexdigest() != pins[name]:
        raise SystemExit('dependency checksum mismatch: ' + name + '; refusing to replace existing file')
    print('verified ' + name)
PYFETCH
