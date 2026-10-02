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


def manifest(expected, service_type='specialUse', resource='local_api_data_extraction_rules'):
    permissions = ''.join(f'<uses-permission android:name="android.permission.{name}"/>' for name in
                          ['INTERNET', 'FOREGROUND_SERVICE', 'FOREGROUND_SERVICE_SPECIAL_USE', 'POST_NOTIFICATIONS'])
    return f'''<manifest xmlns:android="http://schemas.android.com/apk/res/android"
        package="{expected['package']}" android:versionCode="{expected['version_code']}"
        android:versionName="{expected['version_name']}">
        <uses-sdk android:minSdkVersion="{expected['min_sdk']}" android:targetSdkVersion="{expected['target_sdk']}"/>
        {permissions}<application android:allowBackup="false" android:fullBackupContent="false"
        android:dataExtractionRules="@xml/{resource}">
        <service android:name="{expected['service']}" android:exported="false" android:foregroundServiceType="{service_type}">
        <property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" android:value="Synthetic local inference fixture"/>
        </service></application></manifest>'''


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
        ('compiled_hex_manifest', False, 'specialUse', 'good', True),
        ('optimized_names_and_paths', True, 'specialUse', 'good', True),
        ('combined_service_flags_rejected', True, 'specialUse|dataSync', 'good', False),
        ('wrong_referenced_xml_rejected', True, 'specialUse', 'wrong_reference', False),
        ('unsafe_qualified_xml_rejected', True, 'specialUse', 'bad_qualified', False),
        ('missing_zip_entry_keeps_diagnostics', True, 'specialUse', 'missing_entry', False),
    ]
    try:
        with tempfile.TemporaryDirectory(prefix='mnn-apk-metadata-') as temporary:
            for name, optimize, service_type, variant, success in cases:
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
                (root / 'AndroidManifest.xml').write_text(manifest(lock['expected_apk'], service_type, reference))
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
                raw_type = next(s['foreground_service_type'] for s in evidence['manifest']['services']
                                if s['name'] == lock['expected_apk']['service'])
                assert raw_type == ('0x40000001' if '|' in service_type else '0x40000000'), raw_type
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
                                        'errors': errors, 'manifest_fgs_raw': raw_type, 'resource': resource})
        report['passed'] = True
    finally:
        (args.report_dir / 'apk-metadata-regression.json').write_text(json.dumps(report, indent=2) + '\n')
    print(f"APK metadata regression passed: {len(report['cases'])} real SDK fixture cases")


if __name__ == '__main__':
    main()
