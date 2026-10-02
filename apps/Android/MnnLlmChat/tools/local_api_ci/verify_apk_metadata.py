#!/usr/bin/env python3
"""Real SDK regression for compiled flags and optimized backup XML references.

Builds tiny resource-only APK fixtures; does not compile App/native code or
claim runtime/device coverage. Negative controls exercise the same parser.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import zipfile

from audit_apk import audit_compiled_manifest

HERE = Path(__file__).resolve().parent


def manifest(expected, service_type='specialUse', resource='local_api_data_extraction_rules', chat_variant='good'):
    permissions = ''.join(f'<uses-permission android:name="android.permission.{name}"/>' for name in
                          ['INTERNET', 'FOREGROUND_SERVICE', 'FOREGROUND_SERVICE_SPECIAL_USE', 'POST_NOTIFICATIONS', 'WAKE_LOCK'])
    exported = 'android:exported="true"' if chat_variant == 'exported' else 'android:exported="false"'
    if chat_variant == 'implicit_export':
        exported = ''
    chat_type = {'wrong_type': 'dataSync', 'combined_type': 'specialUse|dataSync'}.get(chat_variant, 'specialUse')
    type_attribute = '' if chat_variant == 'missing_type' else f'android:foregroundServiceType="{chat_type}"'
    subtype = ('<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" '
               'android:value="Synthetic user-started on-device text generation fixture"/>')
    if chat_variant == 'missing_subtype':
        subtype = ''
    chat = (f'<service android:name="{expected["chat_service"]}" {exported} {type_attribute}>'
            f'{subtype}</service>') if chat_variant != 'missing' else ''
    return f'''<manifest xmlns:android="http://schemas.android.com/apk/res/android"
        package="{expected['package']}" android:versionCode="{expected['version_code']}"
        android:versionName="{expected['version_name']}">
        <uses-sdk android:minSdkVersion="{expected['min_sdk']}" android:targetSdkVersion="{expected['target_sdk']}"/>
        {permissions}<application android:allowBackup="false" android:fullBackupContent="false"
        android:dataExtractionRules="@xml/{resource}">
        <service android:name="{expected['service']}" android:exported="false" android:foregroundServiceType="{service_type}">
        <property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" android:value="Synthetic local inference fixture"/>
        </service>{chat}</application></manifest>'''


def rules(exclude_all=True):
    domains = ['root', 'sharedpref', 'database', 'file', 'external'] if exclude_all else ['root']
    exclusions = ''.join(f'<exclude domain="{domain}" path="."/>' for domain in domains)
    return f'<data-extraction-rules><cloud-backup>{exclusions}</cloud-backup><device-transfer>{exclusions}</device-transfer></data-extraction-rules>'


def run(*args):
    return subprocess.check_output([str(x) for x in args], text=True, stderr=subprocess.STDOUT)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--sdk', type=Path, required=True)
    parser.add_argument('--report-dir', type=Path, required=True)
    parser.add_argument('--platform', default='35', help='Fixture-only compile SDK; production remains locked to 35')
    parser.add_argument('--apkanalyzer', type=Path)
    args = parser.parse_args()
    lock = json.loads((HERE / 'build-lock.json').read_text())
    aapt2 = args.sdk / 'build-tools' / lock['toolchain']['build_tools'] / 'aapt2'
    analyzer = args.apkanalyzer or args.sdk / 'cmdline-tools/latest/bin/apkanalyzer'
    platform = args.sdk / 'platforms' / ('android-' + args.platform) / 'android.jar'
    output = args.report_dir / 'apk-metadata-regression'
    output.mkdir(parents=True, exist_ok=True)
    report = {'coverage': 'SDK-built resource-only synthetic APKs, no App/native/runtime validation',
              'aapt2_version': run(aapt2, 'version').strip(),
              'aapt2_sha256': hashlib.sha256(aapt2.read_bytes()).hexdigest(),
              'fixture_compile_sdk': args.platform,
              'platform_sha256': hashlib.sha256(platform.read_bytes()).hexdigest(), 'cases': []}
    cases = [
        ('compiled_hex_manifest', False, 'specialUse', 'good', 'good', None),
        ('optimized_names_and_paths', True, 'specialUse', 'good', 'good', None),
        ('combined_service_flags_rejected', True, 'specialUse|dataSync', 'good', 'good', 'Local API service must use specialUse'),
        ('missing_chat_service_rejected', True, 'specialUse', 'good', 'missing', 'Background chat foreground service is missing'),
        ('exported_chat_service_rejected', True, 'specialUse', 'good', 'exported', 'Background chat service must explicitly be non-exported'),
        ('implicit_chat_service_export_rejected', True, 'specialUse', 'good', 'implicit_export', 'Background chat service must explicitly be non-exported'),
        ('wrong_chat_service_type_rejected', True, 'specialUse', 'good', 'wrong_type', 'Background chat service must use specialUse'),
        ('combined_chat_service_flags_rejected', True, 'specialUse', 'good', 'combined_type', 'Background chat service must use specialUse'),
        ('missing_chat_service_type_rejected', True, 'specialUse', 'good', 'missing_type', 'Background chat service must use specialUse'),
        ('missing_chat_service_subtype_rejected', True, 'specialUse', 'good', 'missing_subtype', 'Background chat special-use service requires a subtype explanation'),
        ('wrong_referenced_xml_rejected', True, 'specialUse', 'wrong_reference', 'good', 'exclude all private domains'),
        ('unsafe_qualified_xml_rejected', True, 'specialUse', 'bad_qualified', 'good', '(v31)'),
        ('missing_zip_entry_keeps_diagnostics', True, 'specialUse', 'missing_entry', 'good', 'ZIP entry must exist exactly once'),
    ]
    try:
        with tempfile.TemporaryDirectory(prefix='mnn-apk-metadata-') as temporary:
            for name, optimize, service_type, variant, chat_variant, expected_error in cases:
                success = expected_error is None
                root = Path(temporary) / name
                (root / 'res/xml').mkdir(parents=True)
                (root / 'res/xml/local_api_data_extraction_rules.xml').write_text(rules())
                reference = 'local_api_data_extraction_rules'
                if variant == 'wrong_reference':
                    reference = 'permissive_rules'
                    (root / 'res/xml/permissive_rules.xml').write_text(rules(False))
                if variant == 'bad_qualified':
                    (root / 'res/xml-v31').mkdir()
                    (root / 'res/xml-v31/local_api_data_extraction_rules.xml').write_text(rules(False))
                (root / 'AndroidManifest.xml').write_text(manifest(lock['expected_apk'], service_type, reference, chat_variant))
                run(aapt2, 'compile', '--dir', root / 'res', '-o', root / 'resources.zip')
                apk = root / 'unoptimized.apk'
                run(aapt2, 'link', '-I', platform, '--manifest', root / 'AndroidManifest.xml', '-o', apk, root / 'resources.zip')
                if optimize:
                    run(aapt2, 'optimize', '--shorten-resource-paths', '--collapse-resource-names', '-o', root / 'optimized.apk', apk)
                    apk = root / 'optimized.apk'
                if variant == 'missing_entry':
                    damaged = root / 'missing-entry.apk'
                    with zipfile.ZipFile(apk) as source, zipfile.ZipFile(damaged, 'w') as target:
                        for member in source.infolist():
                            if not member.filename.startswith('res/'):
                                target.writestr(member, source.read(member))
                    apk = damaged
                evidence = {}
                try:
                    errors = audit_compiled_manifest(apk, analyzer, aapt2, output / name, lock['expected_apk'], evidence)
                except ValueError as error:
                    if variant != 'missing_entry':
                        raise
                    errors = [str(error)]
                    assert 'ZIP entry must exist exactly once' in str(error), error
                    for diagnostic in ['apk-manifest.xml', 'apk-resource-entries.json', 'apk-xml-resource-table.txt']:
                        assert (output / name / diagnostic).is_file(), diagnostic
                assert (not errors) == success, f'{name}: expected success={success}, errors={errors}'
                if expected_error:
                    assert any(expected_error in e for e in errors), f'{name}: wrong failure: {errors}'
                raw_type = next(s['foreground_service_type'] for s in evidence['manifest']['services']
                                if s['name'] == lock['expected_apk']['service'])
                assert raw_type == ('0x40000001' if '|' in service_type else '0x40000000'), raw_type
                chat = next((s for s in evidence['manifest']['services']
                             if s['name'] == lock['expected_apk']['chat_service']), None)
                if chat_variant == 'missing':
                    assert chat is None, chat
                else:
                    expected_type = {'wrong_type': '0x1', 'combined_type': '0x40000001', 'missing_type': None}.get(chat_variant, '0x40000000')
                    assert chat['foreground_service_type'] == expected_type, chat
                    expected_export = {'exported': 'true', 'implicit_export': None}.get(chat_variant, 'false')
                    assert chat['exported'] == expected_export, chat
                resource = evidence['data_extraction_resource']
                if optimize:
                    assert resource['resource_name'].endswith('0_resource_name_obfuscated'), resource
                    assert all(not v['path'].startswith('res/xml/') for v in resource['variants']), resource
                if variant == 'wrong_reference':
                    assert any('exclude all private domains' in e for e in errors), errors
                if variant == 'bad_qualified':
                    assert len(resource['variants']) == 2 and any('(v31)' in e for e in errors), errors
                if '|' in service_type:
                    assert any('specialUse' in e for e in errors), errors
                report['cases'].append({'name': name, 'passed': True, 'expected_audit_success': success,
                                        'apk_sha256': hashlib.sha256(apk.read_bytes()).hexdigest(),
                                        'errors': errors, 'manifest_fgs_raw': raw_type,
                                        'manifest_chat_service': chat, 'resource': resource})
        report['passed'] = True
    finally:
        (args.report_dir / 'apk-metadata-regression.json').write_text(json.dumps(report, indent=2) + '\n')
    print(f"APK metadata regression passed: {len(report['cases'])} real SDK fixture cases")


if __name__ == '__main__':
    main()
