"""Real-keytool regressions for selecting an existing CI signing identity.

All private keys in this suite are synthetic, ephemeral fixtures. The harness
never uses an inherited signing Secret or a repository keystore.
"""

import base64
import hashlib
import os
from pathlib import Path
import shutil
import stat
import subprocess
import sys
import tempfile
import unittest


HERE = Path(__file__).resolve().parent
SCRIPT = HERE / 'prepare_signing_key.py'
SECRET_ENV = 'MNN_SIGNING_KEYSTORE_BASE64'
ALIAS = 'mnn-local-api-ci-test'
PASSWORD = 'android'


class PrepareSigningKeyTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.keytool = shutil.which('keytool')
        if not cls.keytool:
            message = 'Real JDK keytool is required for signing-key regression'
            if os.environ.get('GITHUB_ACTIONS') == 'true' or os.environ.get('CI') == 'true':
                raise RuntimeError(message + '; this regression must run in CI')
            raise unittest.SkipTest(message)
        cls.temp = tempfile.TemporaryDirectory(prefix='mnn-prepare-signing-key-tests-')
        cls.addClassCleanup(cls.temp.cleanup)
        cls.root = Path(cls.temp.name)
        cls.first = cls.root / 'synthetic-first.p12'
        cls.second = cls.root / 'synthetic-second.p12'
        for path, name in [(cls.first, 'First'), (cls.second, 'Second')]:
            cls.run_keytool('-genkeypair', '-noprompt', '-storetype', 'PKCS12',
                            '-keystore', path, '-alias', ALIAS, '-storepass', PASSWORD,
                            '-keypass', PASSWORD, '-keyalg', 'RSA', '-keysize', '2048',
                            '-validity', '2', '-dname', 'CN=Synthetic ' + name + ' Fixture')
        cls.first_bytes = cls.first.read_bytes()
        cls.second_bytes = cls.second.read_bytes()
        cls.first_der = cls.certificate(cls.first)
        cls.second_der = cls.certificate(cls.second)
        cls.expected_sha256 = hashlib.sha256(cls.first_der).hexdigest()
        cls.other_sha256 = hashlib.sha256(cls.second_der).hexdigest()
        cls.first_secret = base64.b64encode(cls.first_bytes).decode('ascii')
        cls.second_secret = base64.b64encode(cls.second_bytes).decode('ascii')

        cls.wrong_alias = cls.root / 'synthetic-wrong-alias.p12'
        shutil.copyfile(cls.first, cls.wrong_alias)
        cls.run_keytool('-changealias', '-keystore', cls.wrong_alias, '-storepass', PASSWORD,
                        '-alias', ALIAS, '-destalias', 'another-synthetic-alias')

        cls.certificate_only = cls.root / 'synthetic-certificate-only.p12'
        public_der = cls.root / 'synthetic-public.der'
        public_der.write_bytes(cls.first_der)
        cls.run_keytool('-importcert', '-noprompt', '-storetype', 'PKCS12',
                        '-keystore', cls.certificate_only, '-storepass', PASSWORD,
                        '-alias', ALIAS, '-file', public_der)
        entry = cls.run_keytool('-list', '-v', '-keystore', cls.certificate_only,
                               '-storepass', PASSWORD, '-alias', ALIAS)
        if b'trustedCertEntry' not in entry or cls.certificate(cls.certificate_only) != cls.first_der:
            raise RuntimeError('Certificate-only fixture must expose the expected certificate without a private key')

        # Canonicality needs unused padding bits. A valid JKS copy makes its byte
        # length controllable through a harmless public-certificate alias.
        padded = cls.root / 'synthetic-canonical-padding.jks'
        cls.run_keytool('-importkeystore', '-noprompt', '-srckeystore', cls.first,
                        '-srcstorepass', PASSWORD, '-destkeystore', padded,
                        '-deststoretype', 'JKS', '-deststorepass', PASSWORD, '-destkeypass', PASSWORD)
        if len(padded.read_bytes()) % 3 == 0:
            cls.run_keytool('-importcert', '-noprompt', '-keystore', padded,
                            '-storepass', PASSWORD, '-alias', 'padding', '-file', public_der)
            if len(padded.read_bytes()) % 3 == 0:
                cls.run_keytool('-changealias', '-keystore', padded, '-storepass', PASSWORD,
                                '-alias', 'padding', '-destalias', 'paddingx')
        cls.padded_bytes = padded.read_bytes()
        cls.padded_secret = base64.b64encode(cls.padded_bytes).decode('ascii')
        if not cls.padded_secret.endswith('=') or cls.certificate(padded) != cls.first_der:
            raise RuntimeError('Canonicality fixture must be a valid matching identity with Base64 padding')

    @classmethod
    def run_keytool(cls, *arguments):
        result = subprocess.run([cls.keytool, '-J-Duser.language=en', '-J-Duser.country=US',
                                 *[str(arg) for arg in arguments]], capture_output=True, timeout=30)
        if result.returncode:
            raise RuntimeError('Synthetic keytool fixture command failed with status ' + str(result.returncode))
        return result.stdout

    @classmethod
    def certificate(cls, keystore):
        return cls.run_keytool('-exportcert', '-keystore', keystore,
                               '-storepass', PASSWORD, '-alias', ALIAS)

    def setUp(self):
        self.case = self.root / self.id().split('.')[-1]
        self.case.mkdir()
        self.cache = self.case / 'cache.p12'
        self.output = self.case / 'prepared.p12'

    def prepare(self, secret=None, cache=None, output=None, expected=None):
        environment = os.environ.copy()
        environment.pop(SECRET_ENV, None)
        if secret is not None:
            environment[SECRET_ENV] = secret
        result = subprocess.run([sys.executable, str(SCRIPT), '--cache-keystore',
                                 str(cache or self.cache), '--output', str(output or self.output),
                                 '--expected-sha256', expected or self.expected_sha256],
                                env=environment, capture_output=True, text=True,
                                timeout=30, umask=0o022)
        self.assert_no_secret_output(result, secret)
        return result

    def assert_no_secret_output(self, result, supplied_secret):
        logs = result.stdout + result.stderr
        for secret in (supplied_secret, self.first_secret, self.second_secret):
            if secret and len(secret.strip()) >= 8:
                self.assertFalse(secret in logs, 'CLI printed synthetic Secret material')
                if len(secret) > 160:
                    middle = len(secret) // 2
                    self.assertFalse(secret[middle:middle + 80] in logs,
                                     'CLI printed a fragment of synthetic private-key material')
        self.assertNotIn('BEGIN PRIVATE KEY', logs)
        self.assertNotIn('BEGIN ENCRYPTED PRIVATE KEY', logs)

    def assert_success(self, result, expected_bytes, output=None):
        target = output or self.output
        self.assertEqual(0, result.returncode, 'CLI rejected a valid synthetic signing identity')
        self.assertTrue(target.is_file(), 'CLI did not produce the validated output keystore')
        # Avoid assertEqual(bytes, bytes), whose failure rendering could dump private fixtures.
        self.assertTrue(target.read_bytes() == expected_bytes, 'CLI changed the selected keystore bytes')
        self.assertEqual(0o600, stat.S_IMODE(target.stat().st_mode), 'output keystore must be owner-only')
        self.assertEqual(self.expected_sha256, hashlib.sha256(self.certificate(target)).hexdigest())
        self.assertIn(self.expected_sha256, (result.stdout + result.stderr).lower(),
                      'successful preparation should report the verified public certificate fingerprint')
        entry = self.run_keytool('-list', '-v', '-keystore', target,
                                '-storepass', PASSWORD, '-alias', ALIAS)
        self.assertIn(b'PrivateKeyEntry', entry)

    def assert_rejected(self, result, output=None):
        self.assertNotEqual(0, result.returncode, 'CLI accepted an invalid or missing signing identity')
        self.assertFalse((output or self.output).exists(), 'failed validation left an output keystore')
        self.assertEqual([], list(self.case.glob('.verify-signing-*')),
                         'failed validation left a temporary private-key file')

    def test_secret_wins_over_a_different_cached_identity(self):
        shutil.copyfile(self.second, self.cache)
        self.assertNotEqual(self.expected_sha256, self.other_sha256)
        self.assert_success(self.prepare(secret=self.first_secret), self.first_bytes)
        self.assertTrue(self.cache.read_bytes() == self.second_bytes, 'selection changed the fallback cache')

    def test_secret_does_not_require_or_create_a_cache_file(self):
        self.assert_success(self.prepare(secret=self.first_secret), self.first_bytes)
        self.assertFalse(self.cache.exists(), 'Secret selection unexpectedly populated a cache')

    def test_absent_and_empty_secret_copy_the_existing_cache_with_private_mode(self):
        shutil.copyfile(self.first, self.cache)
        self.cache.chmod(0o644)
        for index, secret in enumerate((None, '')):
            with self.subTest(secret_state='absent' if secret is None else 'empty'):
                output = self.case / ('prepared-' + str(index) + '.p12')
                self.assert_success(self.prepare(secret=secret, output=output), self.first_bytes, output)
        self.assertTrue(self.cache.read_bytes() == self.first_bytes, 'fallback changed cached key bytes')
        self.assertEqual(0o644, stat.S_IMODE(self.cache.stat().st_mode), 'fallback should not change cache permissions')

    def test_missing_secret_and_cache_never_generate_a_new_identity(self):
        for secret in (None, ''):
            with self.subTest(secret_state='absent' if secret is None else 'empty'):
                self.assert_rejected(self.prepare(secret=secret))
                self.assertFalse(self.cache.exists(), 'missing input generated a replacement signing identity')

    def test_invalid_base64_secret_cannot_fall_back_to_valid_cache(self):
        shutil.copyfile(self.first, self.cache)
        malformed = ('NOT_BASE64_SECRET_SENTINEL_!', ' ', '雪',
                     self.first_secret[:40] + '\n' + self.first_secret[40:],
                     ' ' + self.first_secret)
        for index, secret in enumerate(malformed):
            with self.subTest(case=index):
                result = self.prepare(secret=secret)
                self.assert_rejected(result)
                self.assertIn('base64', (result.stdout + result.stderr).lower())

    def test_decodable_noncanonical_base64_secret_is_rejected(self):
        shutil.copyfile(self.first, self.cache)
        alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/'
        padding = len(self.padded_secret) - len(self.padded_secret.rstrip('='))
        index = len(self.padded_secret) - padding - 1
        altered = alphabet[alphabet.index(self.padded_secret[index]) | 1]
        noncanonical = self.padded_secret[:index] + altered + self.padded_secret[index + 1:]
        self.assertTrue(base64.b64decode(noncanonical, validate=True) == self.padded_bytes,
                        'fixture must remain decodable to the identical valid key bytes')
        self.assertTrue(noncanonical != self.padded_secret, 'fixture must differ from canonical encoding')
        result = self.prepare(secret=noncanonical)
        self.assert_rejected(result)
        self.assertIn('base64', (result.stdout + result.stderr).lower())

    def test_valid_secret_with_wrong_certificate_cannot_fall_back(self):
        shutil.copyfile(self.first, self.cache)
        self.assert_rejected(self.prepare(secret=self.second_secret))
        self.assertTrue(self.cache.read_bytes() == self.first_bytes, 'rejected Secret changed the cached identity')

    def test_cached_wrong_certificate_is_rejected(self):
        shutil.copyfile(self.second, self.cache)
        self.assert_rejected(self.prepare())

    def test_invalid_keystore_from_secret_or_cache_is_rejected_without_echo(self):
        invalid = b'INVALID_PRIVATE_KEY_FIXTURE_SENTINEL_742'
        self.cache.write_bytes(invalid)
        result = self.prepare()
        self.assert_rejected(result)
        self.assertNotIn(invalid.decode('ascii'), result.stdout + result.stderr)
        shutil.copyfile(self.first, self.cache)
        result = self.prepare(secret=base64.b64encode(invalid).decode('ascii'))
        self.assert_rejected(result)
        self.assertNotIn(invalid.decode('ascii'), result.stdout + result.stderr)

    def test_required_alias_is_not_replaced_by_another_private_key_alias(self):
        shutil.copyfile(self.wrong_alias, self.cache)
        self.assert_rejected(self.prepare())
        shutil.copyfile(self.first, self.cache)
        wrong_secret = base64.b64encode(self.wrong_alias.read_bytes()).decode('ascii')
        self.assert_rejected(self.prepare(secret=wrong_secret))

    def test_matching_public_certificate_without_private_key_is_rejected(self):
        shutil.copyfile(self.certificate_only, self.cache)
        self.assert_rejected(self.prepare())
        shutil.copyfile(self.first, self.cache)
        public_only_secret = base64.b64encode(self.certificate_only.read_bytes()).decode('ascii')
        self.assert_rejected(self.prepare(secret=public_only_secret))

    def test_expected_certificate_fingerprint_must_be_a_full_hex_digest(self):
        shutil.copyfile(self.first, self.cache)
        for index, invalid in enumerate(('xyz', '0' * 63, 'g' * 64, '0' * 65)):
            with self.subTest(case=index):
                self.assert_rejected(self.prepare(expected=invalid))

    def test_existing_output_is_rejected_without_overwriting_the_previous_identity(self):
        self.output.write_bytes(self.second_bytes)
        self.output.chmod(0o600)
        result = self.prepare(secret=self.first_secret)
        self.assertNotEqual(0, result.returncode, 'CLI should refuse an existing output path')
        self.assertTrue(self.output.read_bytes() == self.second_bytes,
                        'preparation overwrote or deleted a previous signing identity')
        self.assertEqual([], list(self.case.glob('.verify-signing-*')))


if __name__ == '__main__':
    unittest.main()
