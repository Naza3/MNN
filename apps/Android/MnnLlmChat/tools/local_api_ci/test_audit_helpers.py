import hashlib
import json
from pathlib import Path
import struct
import tempfile
import unittest
import zipfile

from audit_apk import (elf_load_segments, suspicious_entry, validate_manifest,
                       validate_extraction_rules, dynamic_symbols, extraction_reference_matches)
from prepare_prebuilt import extract_verified

LOCK = json.loads((Path(__file__).parent/'build-lock.json').read_text())


def elf_fixture(machine=183, alignment=16384):
    data = bytearray(120)
    data[:6] = b'\x7fELF\x02\x01'
    struct.pack_into('<H',data,18,machine)
    struct.pack_into('<Q',data,32,64)
    struct.pack_into('<HH',data,54,56,1)
    struct.pack_into('<IIQQQQQQ',data,64,1,5,0,0,0,120,120,alignment)
    return bytes(data)


def manifest(exported='false', service_type='specialUse'):
    expected=LOCK['expected_apk']
    permissions=''.join(f'<uses-permission android:name="android.permission.{name}" />'
                        for name in ['INTERNET','FOREGROUND_SERVICE','FOREGROUND_SERVICE_SPECIAL_USE','POST_NOTIFICATIONS'])
    return f'''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{expected['package']}" android:versionCode="831" android:versionName="0.8.3-localapi.1">
    <uses-sdk android:minSdkVersion="26" android:targetSdkVersion="35" />{permissions}
    <application android:allowBackup="false" android:fullBackupContent="false" android:dataExtractionRules="@xml/local_api_data_extraction_rules"><service android:name="{expected['service']}" android:exported="{exported}"
    android:foregroundServiceType="{service_type}"><property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
    android:value="Local inference" /></service></application></manifest>'''


class AuditHelpersTest(unittest.TestCase):
    def test_arm64_elf_segments(self):
        self.assertEqual(16384,elf_load_segments(elf_fixture())[0]['alignment'])

    def test_wrong_abi_rejected(self):
        with self.assertRaises(ValueError): elf_load_segments(elf_fixture(machine=62))

    def test_truncated_program_headers_rejected(self):
        with self.assertRaises(ValueError): elf_load_segments(elf_fixture()[:-1])

    def test_manifest_happy_path(self):
        self.assertEqual([],validate_manifest(manifest(),LOCK['expected_apk'])[1])

    def test_exported_service_rejected(self):
        self.assertTrue(any('non-exported' in x for x in validate_manifest(manifest(exported='true'),LOCK['expected_apk'])[1]))

    def test_time_limited_service_rejected(self):
        self.assertTrue(any('specialUse' in x for x in validate_manifest(manifest(service_type='dataSync'),LOCK['expected_apk'])[1]))

    def test_wrong_version_rejected(self):
        xml=manifest().replace('android:versionCode="831"','android:versionCode="830"')
        self.assertTrue(any('version_code' in x for x in validate_manifest(xml,LOCK['expected_apk'])[1]))

    def test_enabled_backup_rejected(self):
        xml=manifest().replace('android:allowBackup="false"','android:allowBackup="true"')
        self.assertTrue(any('disable App backup' in x for x in validate_manifest(xml,LOCK['expected_apk'])[1]))

    def test_missing_data_extraction_rules_rejected(self):
        xml=manifest().replace('android:dataExtractionRules="@xml/local_api_data_extraction_rules"','')
        self.assertTrue(any('extraction' in x for x in validate_manifest(xml,LOCK['expected_apk'])[1]))

    def test_actual_backup_rules_exclude_all_domains(self):
        domains=['root','sharedpref','database','file','external']
        excludes=''.join(f'<exclude domain="{domain}" path="."/>' for domain in domains)
        xml=f'<data-extraction-rules><cloud-backup>{excludes}</cloud-backup><device-transfer>{excludes}</device-transfer></data-extraction-rules>'
        self.assertEqual([],validate_extraction_rules(xml))
        self.assertTrue(validate_extraction_rules('<data-extraction-rules><cloud-backup/><device-transfer/></data-extraction-rules>'))
        self.assertTrue(validate_extraction_rules(xml.replace('<exclude domain="sharedpref" path="."/>','')))

    def test_backup_manifest_reference_cannot_point_at_other_file(self):
        dump='resource 0x7f150005 io.github.naza3.mnnchat:xml/local_api_data_extraction_rules\n'
        self.assertTrue(extraction_reference_matches('@ref/0x7f150005',dump))
        self.assertTrue(extraction_reference_matches('@xml/local_api_data_extraction_rules',dump))
        self.assertFalse(extraction_reference_matches('@ref/0x7f150006',dump))
        self.assertFalse(extraction_reference_matches('@xml/another_rules_file',dump))

    def test_mnn_abi_required_symbols_exclude_weak_imports(self):
        exports,required=dynamic_symbols('''
  1: 00000000 0 FUNC GLOBAL DEFAULT UND _ZN3MNN3fooEv
  2: 00000000 0 FUNC WEAK DEFAULT UND _ZN3MNN3barEv
  3: 00004000 4 FUNC GLOBAL DEFAULT 10 _ZN3MNN3bazEv
  4: 00000000 0 FUNC GLOBAL DEFAULT UND free@LIBC
''')
        self.assertEqual({'_ZN3MNN3fooEv'},required)
        self.assertEqual({'_ZN3MNN3bazEv'},exports)

    def test_models_and_credentials_detected_without_echoing_contents(self):
        self.assertEqual('model asset',suspicious_entry('assets/weights/model.mnn',b'model'))
        self.assertEqual('credential/key container',suspicious_entry('assets/debug.keystore',b'fixture'))
        self.assertEqual('private key material',suspicious_entry('assets/config.txt',b'-----BEGIN PRIVATE KEY-----'))
        self.assertIsNone(suspicious_entry('assets/model_market.json',b'{"name":"model catalog"}'))

    def test_verified_archive_extracts_only_expected_library(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary)
            archive=root/'fixture.zip'
            with zipfile.ZipFile(archive,'w') as zipped:
                zipped.writestr('libsherpa-mnn-jni.so',elf_fixture())
                zipped.writestr('../escape','must not extract')
            lock={'archive_sha256':hashlib.sha256(archive.read_bytes()).hexdigest(),
                  'member':'libsherpa-mnn-jni.so','url':'https://example.invalid/fixture.zip','source_provenance':'synthetic'}
            destination=root/'out/library.so'
            extract_verified(archive,lock,destination)
            self.assertEqual(elf_fixture(),destination.read_bytes())
            self.assertFalse((root/'escape').exists())

    def test_hash_mismatch_does_not_write_library(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary)
            archive=root/'fixture.zip'
            archive.write_bytes(b'not approved')
            with self.assertRaises(RuntimeError):
                extract_verified(archive,{'archive_sha256':'0'*64},root/'library.so')
            self.assertFalse((root/'library.so').exists())


if __name__=='__main__': unittest.main()
