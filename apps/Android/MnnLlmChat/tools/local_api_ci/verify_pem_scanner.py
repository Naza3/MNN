#!/usr/bin/env python3
"""Exercise key scanning on actual D8 strings, with safe public/synthetic inputs."""
import argparse
import base64
import json
import os
from pathlib import Path
import subprocess
import tempfile
import urllib.request

from audit_apk import suspicious_entry
from pem_scan import private_key_markers
from public_fixture import verify_fixture_inputs, sha256

HERE = Path(__file__).resolve().parent


def run(*args):
    subprocess.run([str(x) for x in args], check=True, capture_output=True)


def synthetic_pem(label, der, newline='\n'):
    # A fixed, publicly specified test-only Ed25519 seed. No service/signing
    # credential is generated, loaded, used or delivered by these fixtures.
    body = base64.b64encode(der).decode()
    return (f'-----BEGIN {label}-----' + newline + body + newline + f'-----END {label}-----').encode()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--sdk', type=Path, required=True)
    parser.add_argument('--report-dir', type=Path, required=True)
    parser.add_argument('--input-dir', type=Path, help='Use previously downloaded exact locked JARs')
    args = parser.parse_args()
    lock = json.loads((HERE / 'build-lock.json').read_text())
    fixture_lock = lock['public_test_fixture']
    args.report_dir.mkdir(parents=True, exist_ok=True)
    report = {'coverage': 'Actual D8 synthetic/public-constant DEX tests, no App/native runtime coverage', 'cases': []}
    with tempfile.TemporaryDirectory(prefix='mnn-pem-regression-') as temporary:
        root = Path(temporary)
        inputs = {}
        for kind in ('binary', 'source'):
            url = fixture_lock[kind + '_url']
            if args.input_dir:
                data = (args.input_dir / url.rsplit('/', 1)[-1]).read_bytes()
            else:
                with urllib.request.urlopen(url, timeout=60) as response:
                    data = response.read()
            inputs[kind] = data
        known, proof = verify_fixture_inputs(inputs['binary'], inputs['source'], fixture_lock)
        (args.report_dir / 'public-test-fixture-provenance.json').write_text(json.dumps(proof, indent=2) + '\n')
        for kind in ('binary', 'source'):
            changed = dict(inputs)
            changed[kind] = inputs[kind] + b'changed'
            try:
                verify_fixture_inputs(changed['binary'], changed['source'], fixture_lock)
            except ValueError:
                report['cases'].append({'name': kind + '_hash_mismatch_rejected', 'passed': True})
            else:
                raise AssertionError('Mismatched source/binary activated fixture')
        synthetic_der = bytes.fromhex('302e020100300506032b657004220420') + bytes(range(32))
        unknown = synthetic_pem('PRIVATE KEY', synthetic_der)
        # Public, fixed mathematical test values only: SEC1 scalar 1, and the
        # textbook RSA example (61 * 53). They are never operational keys.
        ec_der = bytes.fromhex('30310201010420') + bytes(31) + b'\x01' + bytes.fromhex('a00a06082a8648ce3d030107')
        def integer(value):
            payload = value.to_bytes(max(1, (value.bit_length() + 7) // 8), 'big')
            if payload[0] & 128:
                payload = b'\0' + payload
            return b'\x02' + bytes([len(payload)]) + payload
        rsa_body = b''.join(integer(v) for v in (0, 3233, 17, 2753, 61, 53, 53, 49, 38))
        rsa_der = b'\x30' + bytes([len(rsa_body)]) + rsa_body
        mutated = bytearray(known)
        body_start = known.index(b'\n') + 1
        mutated[body_start] = ord('A') if mutated[body_start] != ord('A') else ord('B')
        cases = [
            ('header_only_constants', [b'-----BEGIN PRIVATE KEY-----\n', b'-----BEGIN RSA PRIVATE KEY-----\r\n'], None),
            ('exact_public_fixture', [known], None),
            ('mutated_public_fixture', [bytes(mutated)], 'private key material'),
            ('synthetic_pkcs8', [unknown], 'private key material'),
            ('synthetic_crlf', [synthetic_pem('PRIVATE KEY', synthetic_der, '\r\n')], 'private key material'),
            ('public_plus_unknown', [known, unknown], 'private key material'),
            ('public_plus_unknown_same_string', [known + b'\n' + unknown], 'private key material'),
            ('public_plus_token', [known, ('sk-proj-' + 'A' * 40).encode()], 'credential-like token'),
            ('malformed_payload', [b'-----BEGIN PRIVATE KEY-----\nnot-a-key'], 'private key material'),
            ('encrypted_payload', [synthetic_pem('ENCRYPTED PRIVATE KEY', synthetic_der)], 'private key material'),
            ('rsa_payload', [synthetic_pem('RSA PRIVATE KEY', rsa_der)], 'private key material'),
            ('ec_payload', [synthetic_pem('EC PRIVATE KEY', ec_der)], 'private key material'),
        ]
        java = Path(os.environ['JAVA_HOME']) / 'bin/javac'
        d8 = args.sdk / 'build-tools' / lock['toolchain']['build_tools'] / 'd8'
        report['d8_sha256'] = sha256(d8.read_bytes())
        try:
            for name, values, expected in cases:
                directory = root / name
                directory.mkdir()
                literals = ','.join(json.dumps(value.decode()) for value in values)
                source = 'public class Fixture { public static String[] values() { return new String[]{' + literals + '}; } }'
                (directory / 'Fixture.java').write_text(source)
                run(java, '--release', '8', '-d', directory, directory / 'Fixture.java')
                run(d8, '--min-api', '26', '--output', directory, directory / 'Fixture.class')
                dex = (directory / 'classes.dex').read_bytes()
                actual = suspicious_entry('classes.dex', dex, fixture_lock['constant_sha256'])
                assert actual == expected, name + ': wrong safe classification'
                markers = private_key_markers(dex, fixture_lock['constant_sha256'])
                assert markers, name + ': no marker exercised'
                if name == 'exact_public_fixture':
                    assert all(item['classification'] == 'public_upstream_tls_capability_test_fixture' for item in markers)
                    assert suspicious_entry('classes.dex', dex) == 'private key material', 'Missing proof must reject'
                if name == 'header_only_constants':
                    assert all(item['classification'] == 'header_only_format_literal' for item in markers)
                report['cases'].append({'name': name, 'passed': True, 'expected_failure': expected, 'markers': markers})
            report['passed'] = True
        finally:
            (args.report_dir / 'pem-scanner-regression.json').write_text(json.dumps(report, indent=2) + '\n')
    print(f"PEM scanner regression passed: {len(report['cases'])} cases; no key bodies logged")


if __name__ == '__main__':
    main()
