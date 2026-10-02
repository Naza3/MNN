#!/usr/bin/env python3
"""Prefetch fixed source archives, preserving upstream FetchContent patches."""
import argparse
import datetime
import hashlib
import json
from pathlib import Path, PurePosixPath
import shutil
import subprocess
import tarfile
import tempfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[4]


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def verify_archive(path, dependency):
    actual = sha256(path)
    if actual != dependency['sha256']:
        raise RuntimeError(f"Source archive hash mismatch for {dependency['name']}: {actual}")
    return actual


def collect_archive_notices(archive_path, dependency, output):
    """Read exact regular members only. Never execute or extract arbitrary paths."""
    output.mkdir(parents=True, exist_ok=True)
    records = []
    with tarfile.open(archive_path, 'r:gz') as archive:
        members = archive.getmembers()
        roots = {PurePosixPath(member.name).parts[0] for member in members if PurePosixPath(member.name).parts}
        if len(roots) != 1:
            raise RuntimeError(f"Expected one source archive root for {dependency['name']}")
        root = next(iter(roots))
        for relative in dependency['notices']:
            member = archive.getmember(root + '/' + relative)
            if not member.isfile() or member.size > 4 * 1024 * 1024:
                raise RuntimeError(f"Invalid notice member in {dependency['name']}: {relative}")
            content = archive.extractfile(member).read()
            # Embedded source notices are preserved with the entire source file,
            # avoiding a guessed copyright/license boundary.
            filename = dependency['name'] + '--' + relative.replace('/', '__')
            (output / filename).write_bytes(content)
            records.append({'source_path': relative, 'file': filename,
                            'sha256': hashlib.sha256(content).hexdigest()})
    return records


def validate_source_lock(lock, root):
    source = root / lock['source_root']
    actual = subprocess.check_output(['git', 'rev-parse', 'HEAD:' + lock['source_root']],
                                     cwd=root, text=True).strip()
    if actual != lock['source_tree_sha']:
        raise RuntimeError('Unreviewed Sherpa source tree; update the source lock after review')
    for dependency in lock['dependencies']:
        text = (source / 'cmake' / dependency['cmake_file']).read_text()
        for expected in (dependency['url'], 'SHA256=' + dependency['sha256'], dependency['filename']):
            if expected not in text:
                raise RuntimeError(f"Source lock differs from upstream CMake declaration: {dependency['name']}")
    return source


def prepare_dependency(dependency, build_dir, report_dir):
    archive = build_dir / dependency['filename']
    if archive.exists():
        verify_archive(archive, dependency)
    else:
        with tempfile.TemporaryDirectory(prefix='mnn-sherpa-source-') as temporary:
            download = Path(temporary) / dependency['filename']
            subprocess.run(['curl', '--fail', '--show-error', '--location', '--proto', '=https',
                            '--proto-redir', '=https', '--tlsv1.2', '--retry', '3', '--max-time', '300',
                            dependency['url'], '--output', str(download)], check=True)
            verify_archive(download, dependency)
            shutil.copyfile(download, archive)
    notices = collect_archive_notices(archive, dependency, report_dir / 'sherpa-notices')
    sources = report_dir / 'sherpa-sources'
    sources.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(archive, sources / archive.name)
    return {'name': dependency['name'], 'url': dependency['url'], 'filename': archive.name,
            'sha256': sha256(archive), 'size': archive.stat().st_size, 'notices': notices,
            'source_copy': 'sherpa-sources/' + archive.name}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--build-dir', type=Path, required=True)
    parser.add_argument('--report-dir', type=Path, required=True)
    args = parser.parse_args()
    config = json.loads((HERE / 'build-lock.json').read_text())
    lock = config['sherpa']
    source_root = validate_source_lock(lock, ROOT)
    args.build_dir.mkdir(parents=True, exist_ok=True)
    args.report_dir.mkdir(parents=True, exist_ok=True)
    report = {'engine_base_sha': config['engine_base_sha'], 'source_root': lock['source_root'],
              'source_tree_sha': lock['source_tree_sha'], 'configuration': lock['configuration'],
              'prepared_at_utc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
              'dependencies': [], 'status': 'preparing'}
    source_notices = args.report_dir / 'sherpa-notices'
    source_notices.mkdir(parents=True, exist_ok=True)
    report['repository_notices'] = []
    for name in ('LICENSE', 'NOTICE'):
        source_notice = source_root / name
        content = source_notice.read_bytes()
        filename = 'sherpa-mnn--' + name
        (source_notices / filename).write_bytes(content)
        report['repository_notices'].append({'source_path': lock['source_root'] + '/' + name,
                                             'file': filename, 'sha256': hashlib.sha256(content).hexdigest()})
    report_path = args.report_dir / 'sherpa-source-inputs.json'
    try:
        for dependency in lock['dependencies']:
            report['dependencies'].append(prepare_dependency(dependency, args.build_dir, args.report_dir))
        report['status'] = 'verified'
    finally:
        report_path.write_text(json.dumps(report, indent=2) + '\n')
    print('Six pinned source archives and their notices verified; no binary prebuilt was downloaded')


if __name__ == '__main__':
    main()
