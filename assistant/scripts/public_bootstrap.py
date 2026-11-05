#!/usr/bin/env python3
"""Explicit owner USB bootstrap: only public exports and exact-pin trust cross ADB.

Legacy PTY is supported for these public ASCII records, never for private bytes.
No private key/password/token is an input, output or command argument.
"""
import ipaddress
import json
import os
from pathlib import Path
import re
import selectors
import shlex
import shutil
import subprocess
import sys
import time

from validate_public_identity import parse_public_identity

READ_OK = '__GLASSLAB_PUBLIC_READ_OK__'
WRITE_OK = '__GLASSLAB_PUBLIC_WRITE_OK__'
ABSENT = '__GLASSLAB_PUBLIC_TRUST_ABSENT__'
PRIVATE_NETS = tuple(ipaddress.IPv4Network(n) for n in
                     ('10.0.0.0/8', '172.16.0.0/12', '192.168.0.0/16'))
ROLES = {'glass': ('lab.franziska.glass', 'glass-1'),
         'pixel': ('lab.franziska.pixelbridge', 'panther')}


def run_bounded(argv, label, public_input=None, seconds=15, cap=140000):
    # public_input is used only by host OpenSSL to validate the public certificate.
    # Every ADB invocation has /dev/null stdin; no input byte is sent to legacy PTY.
    process = subprocess.Popen(argv, stdin=subprocess.PIPE if public_input is not None else subprocess.DEVNULL,
                               stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    selector = selectors.DefaultSelector()
    buffers = {'out': bytearray(), 'err': bytearray()}
    try:
        input_offset = 0
        if public_input is not None:
            if not public_input or len(public_input) > 16384:
                raise ValueError('public certificate size exceeded')
            os.set_blocking(process.stdin.fileno(), False)
            selector.register(process.stdin, selectors.EVENT_WRITE, 'in')
        selector.register(process.stdout, selectors.EVENT_READ, 'out')
        selector.register(process.stderr, selectors.EVENT_READ, 'err')
        deadline = time.monotonic() + seconds
        while selector.get_map():
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise ValueError(label + ': command deadline exceeded')
            for key, unused in selector.select(remaining):
                if key.data == 'in':
                    try:
                        count = os.write(key.fileobj.fileno(), public_input[input_offset:input_offset + 4096])
                    except BlockingIOError:
                        continue
                    input_offset += count
                    if input_offset == len(public_input):
                        selector.unregister(key.fileobj)
                        key.fileobj.close()
                    continue
                chunk = os.read(key.fileobj.fileno(), 8192)
                if not chunk:
                    selector.unregister(key.fileobj)
                    continue
                buffers[key.data].extend(chunk)
                if sum(map(len, buffers.values())) > cap:
                    raise ValueError(label + ': bounded output exceeded')
        returncode = process.wait(timeout=max(.01, deadline - time.monotonic()))
        if returncode:
            raise ValueError(label + ': host command failed')
        return bytes(buffers['out'])
    finally:
        selector.close()
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=2)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=2)
        if process.stdin is not None and not process.stdin.closed:
            process.stdin.close()
        process.stdout.close()
        process.stderr.close()


def private_ipv4(value):
    if not isinstance(value, str) or not re.fullmatch(r'[0-9.]{7,15}', value):
        raise ValueError('one canonical private IPv4 literal required')
    try:
        address = ipaddress.IPv4Address(value)
    except ipaddress.AddressValueError as error:
        raise ValueError('invalid IPv4 literal') from error
    if str(address) != value or not any(address in net for net in PRIVATE_NETS):
        raise ValueError('only canonical RFC1918 IPv4 is supported')
    return value


def numeric_port(value):
    if not re.fullmatch(r'[1-9][0-9]{0,4}', value or '') or int(value) > 65535:
        raise ValueError('canonical port in 1..65535 required')
    return int(value)


def serial_value(value):
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]{0,127}', value or ''):
        raise ValueError('explicit USB role serial required')
    if value.startswith('emulator-'):
        raise ValueError('physical USB role serial required')
    return value


def remote_command(package, script):
    # Two real shell layers: adb receives exactly ONE command, whose sh -c payload
    # is shell-quoted as a whole. Embedded JSON quotes are never interpreted twice.
    return ' '.join(shlex.quote(p) for p in ['run-as', package, 'sh', '-c', script])


def read_script(public_identity):
    if public_identity:
        return ('test ! -L files/identity-private.der && test -s files/identity-private.der'
                ' && test ! -L files/identity-cert.der && test -s files/identity-cert.der'
                ' && test ! -L files/identity-public.json && test -f files/identity-public.json'
                ' && dd if=files/identity-public.json bs=65537 count=1 2>/dev/null'
                ' && echo ' + READ_OK)
    return ('test ! -L files/trust.json && if test -e files/trust.json; then'
            ' test -f files/trust.json && dd if=files/trust.json bs=4097 count=1 2>/dev/null;'
            ' else echo ' + ABSENT + '; fi && echo ' + READ_OK)


def write_script(trust_json):
    return ('umask 077 && test ! -L files/.trust.json.tmp && '
            'echo ' + shlex.quote(trust_json.rstrip('\n'))
            + ' > files/.trust.json.tmp && chmod 600 files/.trust.json.tmp'
            ' && mv files/.trust.json.tmp files/trust.json && echo ' + WRITE_OK)


def canonical_record(raw):
    if raw.endswith(b'\r\n'):
        return raw[:-2] + b'\n'
    return raw


def public_response(raw):
    for ending in ((READ_OK + '\r\n').encode(), (READ_OK + '\n').encode()):
        if raw.endswith(ending):
            return canonical_record(raw[:-len(ending)])
    raise ValueError('remote public read marker absent')


def trust_json(pin, address, port, server):
    if type(port) is not int or not 1 <= port <= 65535 or type(server) is not bool:
        raise ValueError('canonical trust port/role required')
    if not isinstance(pin, str) or not re.fullmatch(r'[0-9a-f]{64}', pin):
        raise ValueError('exact peer leaf pin required')
    private_ipv4(address)
    value = {'schema': 1, 'peer_leaf_sha256': pin,
             'bind' if server else 'endpoint_ipv4': address, 'port': port}
    return json.dumps(value, separators=(',', ':')) + '\n'


def main():
    env = os.environ
    if env.get('GLASSLAB_ENABLE_PUBLIC_BOOTSTRAP') != 'OWNER_VERIFIED_USB_RUN_AS':
        raise ValueError('owner public-bootstrap gate is CLOSED')
    adb = env.get('GLASSLAB_ADB_BIN', '')
    if not adb or not Path(adb).is_absolute() or not Path(adb).is_file() or not os.access(adb, os.X_OK):
        raise ValueError('explicit absolute executable ADB required')
    port = numeric_port(env.get('GLASSLAB_LINK_PORT', '38443'))
    adb_port = numeric_port(env.get('GLASSLAB_ADB_SERVER_PORT', '5037'))
    serials = {role: serial_value(env.get('GLASSLAB_' + role.upper() + '_SERIAL')) for role in ROLES}
    if serials['glass'] == serials['pixel']:
        raise ValueError('distinct explicit role serials required')
    endpoint = private_ipv4(env.get('GLASSLAB_GLASS_ENDPOINT'))
    bind = private_ipv4(env.get('GLASSLAB_PIXEL_BIND'))
    if endpoint != bind:
        raise ValueError('Glass endpoint must equal the direct Pixel bind address')
    openssl = env.get('GLASSLAB_OPENSSL_BIN') or shutil.which('openssl')
    if not openssl:
        raise ValueError('existing host OpenSSL required for public DER validation')

    def command(role, args):
        return run_bounded([adb, '-P', str(adb_port), '-s', serials[role], *args], role)

    exports, previous = {}, {}
    # Finish every role/source/trust preflight before the first public write.
    for role, (package, device) in ROLES.items():
        if canonical_record(command(role, ['get-state'])) != b'device\n':
            raise ValueError(role + ': selected USB device is offline')
        if canonical_record(command(role, ['shell', 'getprop ro.product.device'])) != (device + '\n').encode():
            raise ValueError(role + ': wrong physical role')
        public = public_response(command(role, ['shell', remote_command(package, read_script(True))]))
        exports[role] = parse_public_identity(public, role, package)
        cert = bytes.fromhex(exports[role]['certificate_der_hex'])
        canonical_der = run_bounded([openssl, 'x509', '-inform', 'DER', '-outform', 'DER'],
                                    'public X509 validation', cert, cap=20000)
        if canonical_der != cert:
            raise ValueError(role + ': certificate is not canonical X509 DER')
        previous[role] = public_response(command(role, ['shell', remote_command(package, read_script(False))]))

    wanted = {'glass': trust_json(exports['pixel']['leaf_sha256'], endpoint, port, False),
              'pixel': trust_json(exports['glass']['leaf_sha256'], bind, port, True)}
    for role in ROLES:
        if previous[role] not in ((ABSENT + '\n').encode(), wanted[role].encode()):
            raise ValueError(role + ': existing public trust differs; explicit revoke/re-pair required')
    written = 0
    for role, (package, unused) in ROLES.items():
        if previous[role] == wanted[role].encode():
            continue
        response = command(role, ['shell', remote_command(package, write_script(wanted[role]))])
        if canonical_record(response) != (WRITE_OK + '\n').encode():
            raise ValueError(role + ': remote public write marker absent')
        actual = public_response(command(role, ['shell', remote_command(package, read_script(False))]))
        if actual != wanted[role].encode():
            raise ValueError(role + ': public trust readback differs')
        written += 1
    print('PASS public-only bootstrap; public_writes=' + str(written)
          + '; exact readback verified; private keys remained on-device; network_pair=NOT_TESTED')


if __name__ == '__main__':
    try:
        main()
    except ValueError as error:
        # These messages are controlled validation labels, never device output or supplied values.
        print('SECURE_LINK_BLOCKED: ' + str(error), file=sys.stderr)
        sys.exit(64)
    except (OSError, subprocess.SubprocessError):
        print('SECURE_LINK_BLOCKED: host command failure; no private bytes transmitted', file=sys.stderr)
        sys.exit(64)
