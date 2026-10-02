import hashlib
import json
from pathlib import Path
import struct
import tempfile
import unittest

from audit_apk import suspicious_entry
from pem_scan import private_key_markers
from public_fixture import validate_fixture_proof


def dex_fixture(*values):
    # Structural string-table fixture. verify_pem_scanner.py separately uses D8.
    count = len(values)
    data = bytearray(112 + count * 4)
    data[:8] = b'dex\n035\0'
    struct.pack_into('<II', data, 36, 112, 0x12345678)
    struct.pack_into('<II', data, 56, count, 112)
    for index, value in enumerate(values):
        struct.pack_into('<I', data, 112 + index * 4, len(data))
        size = len(value)
        while size >= 128:
            data.append((size & 127) | 128)
            size >>= 7
        data.append(size)
        data.extend(value + b'\0')
    struct.pack_into('<I', data, 32, len(data))
    return bytes(data)


class PemScanTest(unittest.TestCase):
    def test_only_exact_dex_format_literals_are_safe(self):
        header = b'-----BEGIN PRIVATE KEY-----\n'
        self.assertIsNone(suspicious_entry('classes.dex', dex_fixture(header)))
        self.assertEqual('private key material', suspicious_entry('assets/text.txt', header))
        self.assertEqual('private key material', suspicious_entry('classes.dex', dex_fixture(header + b'malformed')))

    def test_exception_is_exact_and_does_not_skip_remaining_strings(self):
        public = b'-----BEGIN PRIVATE KEY-----\nUHVibGljIGZpeHR1cmU=\n-----END PRIVATE KEY-----'
        known = hashlib.sha256(public).hexdigest()
        self.assertIsNone(suspicious_entry('classes.dex', dex_fixture(public), known))
        self.assertEqual('private key material', suspicious_entry('classes.dex', dex_fixture(public)))
        self.assertEqual('private key material', suspicious_entry('assets/text.txt', public, known))
        self.assertEqual('private key material', suspicious_entry('classes.dex', dex_fixture(public + b'X'), known))
        unknown = public.replace(b'UHVib', b'AHVib')
        self.assertEqual('private key material', suspicious_entry('classes.dex', dex_fixture(public, unknown), known))
        self.assertEqual('private key material', suspicious_entry('classes.dex', dex_fixture(public + unknown), known))
        self.assertEqual('credential-like token', suspicious_entry('classes.dex', dex_fixture(public, b'sk-' + b'A' * 40), known))

    def test_dex_length_prefix_cannot_hide_token_boundary(self):
        token = b'sk-proj-' + b'A' * 40  # length 48 is an ASCII '0' byte
        self.assertEqual('credential-like token', suspicious_entry('classes.dex', dex_fixture(token)))

    def test_diagnostics_do_not_contain_material(self):
        value = b'-----BEGIN EC PRIVATE KEY-----\nSYNTHETIC_MARKER_BODY'
        records = private_key_markers(dex_fixture(value))
        self.assertEqual(1, len(records))
        self.assertFalse(records[0]['accepted'])
        self.assertNotIn('SYNTHETIC_MARKER_BODY', json.dumps(records))

    def test_bad_dex_bounds_cannot_activate_exception(self):
        data = bytearray(dex_fixture(b'-----BEGIN PRIVATE KEY-----'))
        struct.pack_into('<I', data, 32, 1)
        with self.assertRaises(ValueError): private_key_markers(data)

    def test_proof_and_actual_runtime_binary_must_match(self):
        lock = {'coordinate': 'io.netty:netty-handler:4.1.119.Final', 'binary_sha256': hashlib.sha256(b'known').hexdigest(),
                'source_sha256': 'reviewed-source', 'constant_sha256': 'reviewed-constant'}
        proof = dict(lock, inputs_verified=True, classification='public_upstream_tls_capability_test_fixture')
        with tempfile.TemporaryDirectory() as directory:
            jar = Path(directory) / 'caches/modules-2/files-2.1/io.netty/netty-handler/4.1.119.Final/hash/netty-handler-4.1.119.Final.jar'
            jar.parent.mkdir(parents=True)
            jar.write_bytes(b'known')
            validate_fixture_proof(proof, lock, directory)
            for key in ('coordinate', 'binary_sha256', 'source_sha256', 'constant_sha256'):
                with self.assertRaises(ValueError): validate_fixture_proof(dict(proof, **{key: 'wrong'}), lock, directory)
            jar.write_bytes(b'changed')
            with self.assertRaises(ValueError): validate_fixture_proof(proof, lock, directory)
