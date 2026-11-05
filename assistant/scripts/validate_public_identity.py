#!/usr/bin/env python3
"""Validate a bounded canonical public export; normalize only its final PTY CRLF."""
import hashlib
import json
import re
import sys

FIELDS = ['schema', 'role', 'package', 'leaf_sha256', 'certificate_der_hex']


def parse_public_identity(raw, role, package):
    if not raw or len(raw) > 65536:
        raise ValueError('invalid public identity size')
    if raw.endswith(b'\r\n'):
        raw = raw[:-2] + b'\n'
    try:
        value = json.loads(raw.decode('ascii'))
    except (ValueError, UnicodeError) as error:
        raise ValueError('malformed public identity') from error
    if not isinstance(value, dict) or list(value) != FIELDS:
        raise ValueError('unexpected public identity fields/order')
    if type(value['schema']) is not int or value['schema'] != 1:
        raise ValueError('invalid public identity schema')
    if value['role'] != role or value['package'] != package:
        raise ValueError('wrong public identity role/package')
    pin, cert_hex = value['leaf_sha256'], value['certificate_der_hex']
    if not isinstance(pin, str) or not re.fullmatch(r'[0-9a-f]{64}', pin):
        raise ValueError('invalid leaf pin')
    if (not isinstance(cert_hex, str) or not re.fullmatch(r'[0-9a-f]{2,32768}', cert_hex)
            or len(cert_hex) % 2):
        raise ValueError('invalid public certificate encoding')
    if hashlib.sha256(bytes.fromhex(cert_hex)).hexdigest() != pin:
        raise ValueError('certificate/pin mismatch')
    expected = json.dumps(value, ensure_ascii=True, separators=(',', ':')) + '\n'
    if raw.decode('ascii') != expected:
        raise ValueError('public identity is not canonical')
    return value


def main():
    if len(sys.argv) != 3:
        raise ValueError('role and package required')
    value = parse_public_identity(sys.stdin.buffer.read(65537), *sys.argv[1:])
    sys.stdout.write(value['leaf_sha256'] + '\n')


if __name__ == '__main__':
    try:
        main()
    except ValueError as error:
        raise SystemExit(str(error))
