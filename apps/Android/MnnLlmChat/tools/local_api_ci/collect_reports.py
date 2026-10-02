#!/usr/bin/env python3
"""Collect small diagnostics/notices without test stdout, models, credentials, or chats."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import shutil
import xml.etree.ElementTree as ET
import zipfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[4]
APP = HERE.parents[1]


def digest(data):
    return hashlib.sha256(data).hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--report-dir', type=Path, required=True)
    parser.add_argument('--gradle-home', type=Path, default=Path.home()/'.gradle')
    args = parser.parse_args()
    out = args.report_dir
    out.mkdir(parents=True, exist_ok=True)
    tests = []
    for file in sorted((APP/'app/build/test-results').glob('**/TEST-*.xml')):
        suite = ET.parse(file).getroot()
        tests.append({'suite': suite.get('name'), 'tests': int(suite.get('tests',0)),
                      'failures': int(suite.get('failures',0)), 'errors': int(suite.get('errors',0)),
                      'skipped': int(suite.get('skipped',0)),
                      'failed_cases': [case.get('name') for case in suite.findall('testcase')
                                       if case.find('failure') is not None or case.find('error') is not None]})
    (out/'test-summary.json').write_text(json.dumps(tests,indent=2)+'\n')
    lint = []
    for file in sorted((APP/'app/build/reports').glob('lint-results-*.xml')):
        for issue in ET.parse(file).getroot().findall('issue'):
            locations = [{'file': str(Path(n.get('file','')).relative_to(ROOT))
                          if Path(n.get('file','')).is_relative_to(ROOT) else Path(n.get('file','')).name,
                          'line': n.get('line')} for n in issue.findall('location')]
            lint.append({'id': issue.get('id'), 'severity': issue.get('severity'), 'locations': locations})
    (out/'lint-summary.json').write_text(json.dumps(lint,indent=2)+'\n')
    notice_dir = out/'notices'
    notice_dir.mkdir(exist_ok=True)
    records = []
    def save_notice(origin, name, data):
        filename = re.sub(r'[^A-Za-z0-9_.-]', '_', name)[:180]
        target = notice_dir/(digest(data)[:12]+'-'+filename)
        target.write_bytes(data)
        records.append({'origin': origin, 'file': target.name, 'sha256': digest(data)})
    for relative in ['LICENSE.txt','3rd_party/flatbuffers/LICENSE.txt','3rd_party/half/LICENSE.txt',
                     'apps/frameworks/sherpa-mnn/LICENSE','apps/frameworks/sherpa-mnn/NOTICE']:
        file = ROOT/relative
        if file.is_file():
            save_notice(relative,relative,file.read_bytes())
    # Preserve the verbatim license-bearing sections of header-only dependencies.
    for relative, start in [('3rd_party/imageHelper/stb_image.h','ALTERNATIVE A - MIT License'),
                            ('3rd_party/imageHelper/stb_image_resize.h','ALTERNATIVE A - MIT License'),
                            ('3rd_party/imageHelper/stb_image_write.h','ALTERNATIVE A - MIT License')]:
        file=ROOT/relative
        if file.is_file() and start in file.read_text(errors='replace'):
            save_notice(relative,relative+'.license.txt',(start+file.read_text(errors='replace').split(start,1)[1]).encode())
    for relative, lines in [('apps/frameworks/3rd_party/include/nlohmann/json.hpp',50),
                            ('apps/frameworks/mnn_tts/include/piper/uni_algo.hpp',-200)]:
        file=ROOT/relative
        if file.is_file():
            text=file.read_text(errors='replace').splitlines()
            save_notice(relative,relative+'.notice-excerpt.txt','\n'.join(text[:lines] if lines>0 else text[lines:]).encode())
    cache=args.gradle_home/'caches/modules-2/files-2.1'
    inventory_path=out/'dependency-inventory.json'
    inventory=json.loads(inventory_path.read_text()) if inventory_path.exists() else []
    for artifact in inventory:
        coordinate=':'.join(artifact[key] for key in ('group','name','version'))
        directory=cache/artifact['group']/artifact['name']/artifact['version']
        artifact['pom_licenses']=[]
        for pom in directory.glob('*/*.pom'):
            xml=ET.parse(pom).getroot()
            for license_node in xml.findall('.//{*}licenses/{*}license'):
                artifact['pom_licenses'].append({n.tag.split('}')[-1]:n.text for n in license_node})
        for file in directory.glob('*/*'):
            if file.name != artifact['filename'] or digest(file.read_bytes()) != artifact['sha256']:
                continue
            if file.suffix not in {'.jar','.aar'}: continue
            def collect_zip(source, origin):
                with zipfile.ZipFile(source) as archive:
                    for name in archive.namelist():
                        base=Path(name).name.lower()
                        if base.startswith(('license','notice','copying')):
                            save_notice(origin+'!/'+name,coordinate+'-'+name,archive.read(name))
                        if name=='classes.jar':
                            collect_zip(io.BytesIO(archive.read(name)),origin+'!/classes.jar')
            collect_zip(file,coordinate)
    inventory_path.write_text(json.dumps(inventory,indent=2)+'\n')
    (out/'notice-inventory.json').write_text(json.dumps(records,indent=2)+'\n')
    shutil.copyfile(HERE/'NOTICE-AUDIT.md',out/'NOTICE-AUDIT.md')
    shutil.copyfile(HERE/'build-lock.json',out/'build-lock.json')
    print(f'Recorded {len(tests)} test suites, {len(lint)} lint issues, {len(inventory)} dependencies, {len(records)} notice entries')


if __name__=='__main__':
    main()
