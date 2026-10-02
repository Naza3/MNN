import json
from pathlib import Path
import struct
import unittest

from audit_apk import (elf_load_segments, suspicious_entry, validate_manifest,
                       validate_extraction_rules, dynamic_symbols, resolve_extraction_resources,
                       foreground_type_matches)

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

    def test_compiled_foreground_enum_is_exact(self):
        for value in ('specialUse', '0x40000000', '1073741824'):
            self.assertTrue(foreground_type_matches(value, 'specialUse'))
            self.assertEqual([], validate_manifest(manifest(service_type=value), LOCK['expected_apk'])[1])
        for value in ('0x40000001', 'specialUse|dataSync', '0x1', '0', None, '-1', '0x400000000'):
            self.assertFalse(foreground_type_matches(value, 'specialUse'))

    def test_backup_manifest_reference_resolves_optimized_path(self):
        dump = ('resource 0x7f150005 xml/0_resource_name_obfuscated\n'
                '  () (file) res/aB.xml type=XML\n'
                '  (v31) (file) res/cD.xml type=XML\n')
        result = resolve_extraction_resources('@ref/0x7f150005', dump)
        self.assertEqual(['res/aB.xml', 'res/cD.xml'], [v['path'] for v in result['variants']])
        for reference in ('@ref/0x7f150006', '@xml/local_api_data_extraction_rules'):
            with self.assertRaises(ValueError): resolve_extraction_resources(reference, dump)
        symbolic = dump.replace('0_resource_name_obfuscated', 'local_api_data_extraction_rules')
        self.assertEqual('0x7f150005', resolve_extraction_resources('@xml/local_api_data_extraction_rules', symbolic)['resource_id'])

    def test_backup_unknown_alias_missing_default_and_unsafe_paths_rejected(self):
        prefix = 'resource 0x7f150005 xml/rules\n'
        for value in ('  () @0x7f150006\n', '  (v31) (file) res/good.xml type=XML\n',
                      '  () (file) res/../bad.xml type=XML\n',
                      '  () (file) res/good.xml type=PNG\n',
                      '  () (file) res/good.xml type=XML\n  () (file) res/other.xml type=XML\n'):
            with self.assertRaises(ValueError): resolve_extraction_resources('@ref/0x7f150005', prefix + value)

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



if __name__=='__main__': unittest.main()
