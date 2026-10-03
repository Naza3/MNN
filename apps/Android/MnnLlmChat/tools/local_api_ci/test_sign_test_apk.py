"""Real SDK signing regression with resource-only APKs and an ephemeral test key.

The audit JSON here is a synthetic gate fixture; no MNN/native/device audit is implied.
"""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


HERE = Path(__file__).resolve().parent


class TestApkSigning(unittest.TestCase):
    @staticmethod
    def missing_tools(message):
        if os.environ.get('GITHUB_ACTIONS') == 'true' or os.environ.get('CI') == 'true':
            raise RuntimeError(message + '; signing regression must run in CI')
        raise unittest.SkipTest(message)

    @classmethod
    def setUpClass(cls):
        sdk = os.environ.get('ANDROID_SDK_ROOT')
        if not sdk or not shutil.which('java') or not shutil.which('keytool'):
            cls.missing_tools('Real Android SDK and JDK are required for signing regression')
        cls.sdk = Path(sdk)
        lock = json.loads((HERE / 'build-lock.json').read_text())
        cls.tools = cls.sdk / 'build-tools' / lock['toolchain']['build_tools']
        platform = cls.sdk / 'platforms' / ('android-' + lock['toolchain']['compile_sdk']) / 'android.jar'
        if not platform.is_file() or any(not (cls.tools / tool).is_file() for tool in ['aapt2', 'apksigner', 'zipalign']):
            cls.missing_tools('Locked SDK packages are required for signing regression')
        cls.temp = tempfile.TemporaryDirectory(prefix='mnn-signing-regression-')
        cls.addClassCleanup(cls.temp.cleanup)
        cls.root = Path(cls.temp.name)
        manifest = cls.root / 'AndroidManifest.xml'
        manifest.write_text('''<manifest xmlns:android="http://schemas.android.com/apk/res/android"
            package="io.github.naza3.signingfixture" android:versionCode="1">
            <uses-sdk android:minSdkVersion="26" android:targetSdkVersion="35"/>
            <application android:label="Synthetic signing fixture"/>
            </manifest>''')
        cls.run_command([cls.tools / 'aapt2', 'link', '-I', platform, '--manifest', manifest,
                         '-o', cls.root / 'unaligned.apk'])
        cls.apk = cls.root / 'unsigned.apk'
        cls.run_command([cls.tools / 'zipalign', '-P', '16', '4', cls.root / 'unaligned.apk', cls.apk])
        cls.key = cls.root / 'test.p12'
        cls.run_command(['keytool', '-genkeypair', '-noprompt', '-storetype', 'PKCS12',
                         '-keystore', cls.key, '-alias', 'mnn-local-api-ci-test',
                         '-storepass', 'android', '-keypass', 'android', '-keyalg', 'RSA',
                         '-keysize', '2048', '-validity', '2', '-dname', 'CN=Synthetic CI Test'])

    @staticmethod
    def run_command(command):
        return subprocess.run([str(arg) for arg in command], capture_output=True, text=True,
                              timeout=30, check=True)

    def setUp(self):
        self.case = self.root / self.id().split('.')[-1]
        self.case.mkdir()
        self.report = self.case / 'reports'
        self.report.mkdir()
        self.output = self.case / 'output'
        self.audit(self.apk)

    def audit(self, apk, **changes):
        audit = {'static_package_audit': 'passed', 'signing': 'unsigned', 'errors': [],
                 'apk_sha256': hashlib.sha256(apk.read_bytes()).hexdigest()}
        audit.update(changes)
        (self.report / 'apk-audit.json').write_text(json.dumps(audit))

    def sign(self, apk=None, key=None, output=None):
        return subprocess.run(['bash', str(HERE / 'sign_test_apk.sh'), str(apk or self.apk),
                               str(key or self.key), str(output or self.output), str(self.report)],
                              capture_output=True, text=True, timeout=30)

    def assert_rejected(self, result):
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertFalse((self.output / 'MNN-Chat-API-arm64-ci-test.apk').exists())

    def test_real_signature_keeps_payload_and_reuses_identity(self):
        original = hashlib.sha256(self.apk.read_bytes()).digest()
        key_before = hashlib.sha256(self.key.read_bytes()).digest()
        result = self.sign()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        apk = self.output / 'MNN-Chat-API-arm64-ci-test.apk'
        verified = self.run_command([self.tools / 'apksigner', 'verify', '--verbose', '--print-certs', apk])
        self.assertIn('Verified using v2 scheme (APK Signature Scheme v2): true', verified.stdout)
        self.assertIn('Verified using v3 scheme (APK Signature Scheme v3): true', verified.stdout)
        self.run_command([self.tools / 'zipalign', '-c', '-P', '16', '4', apk])
        self.assertEqual(hashlib.sha256(apk.read_bytes()).hexdigest(),
                         apk.with_suffix('.apk.sha256').read_text().split()[0])
        report = json.loads((self.report / 'signed-test-apk.json').read_text())
        self.assertTrue(report['archive_payload_unchanged'])
        self.assertEqual(['v2', 'v3'], report['verified_signature_schemes'])
        self.assertEqual(original, hashlib.sha256(self.apk.read_bytes()).digest())
        self.assertEqual(key_before, hashlib.sha256(self.key.read_bytes()).digest())
        result = self.sign(output=self.case / 'second-build')
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(report['certificate_sha256'],
                         json.loads((self.report / 'signed-test-apk.json').read_text())['certificate_sha256'])
        self.assertEqual({'MNN-Chat-API-arm64-ci-test.apk', 'MNN-Chat-API-arm64-ci-test.apk.sha256'},
                         {path.name for path in self.output.iterdir()})

    def test_audited_hash_cannot_authorize_a_changed_apk(self):
        changed = self.case / 'changed.apk'
        changed.write_bytes(self.apk.read_bytes() + b'synthetic unaudited change')
        result = self.sign(apk=changed)
        self.assert_rejected(result)
        self.assertIn('does not match', result.stderr)

    def test_failed_audit_cannot_be_signed(self):
        self.audit(self.apk, static_package_audit='failed', errors=['synthetic failure'])
        self.assert_rejected(self.sign())

    def test_missing_key_never_creates_a_replacement_identity(self):
        missing = self.case / 'missing.p12'
        self.assert_rejected(self.sign(key=missing))
        self.assertFalse(missing.exists())

    def test_existing_signature_is_not_replaced(self):
        first = self.sign(output=self.case / 'already-signed')
        self.assertEqual(0, first.returncode, first.stdout + first.stderr)
        signed = self.case / 'already-signed/MNN-Chat-API-arm64-ci-test.apk'
        self.audit(signed)  # Even a false synthetic unsigned report cannot bypass the real check.
        result = self.sign(apk=signed)
        self.assert_rejected(result)
        self.assertIn('Refusing to replace', result.stderr)

    def test_key_cannot_be_inside_an_uploaded_directory(self):
        self.output.mkdir()
        inside = self.output / 'test.p12'
        shutil.copyfile(self.key, inside)
        result = self.sign(key=inside)
        self.assert_rejected(result)
        self.assertIn('outside all artifact/report directories', result.stderr)


if __name__ == '__main__':
    unittest.main()
