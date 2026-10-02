#!/usr/bin/env python3
"""Freeze the latest official stable release once, separately from the App SHA."""
import argparse
import datetime
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import urllib.parse
import urllib.request

from engine_source import APP_ROOT as ROOT, ENGINE_PATHS, engine_paths, git, verify_engine_checkout

HERE = Path(__file__).resolve().parent


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + '\n')


def github_json(url):
    request = urllib.request.Request(url, headers={'Accept': 'application/vnd.github+json',
                                                    'User-Agent': 'MNN-local-api-release-build'})
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.load(response)


def latest_release(lock):
    api = lock['release_api']
    release = github_json(api + '/releases/latest')
    if release.get('draft') is not False or release.get('prerelease') is not False or not release.get('tag_name'):
        raise RuntimeError('Official latest release is not a published stable release')
    tag = release['tag_name']
    reference = github_json(api + '/git/ref/tags/' + urllib.parse.quote(tag, safe=''))
    obj = reference['object']
    tag_object = obj['sha']
    for _ in range(5):
        if obj['type'] == 'commit':
            break
        if obj['type'] != 'tag' or not re.fullmatch(r'[0-9a-f]{40}', obj['sha']):
            raise RuntimeError('Official release tag cannot be resolved to a commit')
        obj = github_json(api + '/git/tags/' + obj['sha'])['object']
    if obj['type'] != 'commit' or not re.fullmatch(r'[0-9a-f]{40}', obj['sha']):
        raise RuntimeError('Official release tag has no verifiable peeled commit')
    return {'id': release['id'], 'tag': tag, 'tag_object_sha': tag_object,
            'commit_sha': obj['sha'], 'published_at': release['published_at'],
            'url': release['html_url'], 'draft': False, 'prerelease': False}


def checkout_release(root, lock):
    if root.exists():
        raise RuntimeError('Refusing to reuse an existing independent engine source checkout')
    root.mkdir(parents=True)
    git(root, 'init', '--quiet')
    git(root, 'remote', 'add', 'origin', lock['upstream_url'])
    git(root, 'config', 'remote.origin.promisor', 'true')
    git(root, 'config', 'remote.origin.partialclonefilter', 'blob:none')
    git(root, 'fetch', '--quiet', '--depth=1', '--filter=blob:none', 'origin', lock['engine_base_sha'])
    # Never materialize restricted source areas. These are exclusion patterns,
    # not file reads; the public build must work without those directories.
    git(root, 'sparse-checkout', 'set', '--no-cone', '/*', '!/schema/private/', '!/source/internal/')
    git(root, 'checkout', '--quiet', '--detach', lock['engine_base_sha'])


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--report-dir', type=Path, required=True)
    args = parser.parse_args()
    lock = json.loads((HERE / 'build-lock.json').read_text())
    sha = lock['engine_base_sha']
    if not re.fullmatch(r'[0-9a-f]{40}', sha):
        raise RuntimeError('Invalid engine release SHA')
    if git(ROOT, 'status', '--porcelain', '--untracked-files=normal'):
        raise RuntimeError('App checkout is dirty; commit/review the inputs before recording provenance')
    source, install = engine_paths()
    report = {'checked_at_utc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
              'app_commit_sha': git(ROOT, 'rev-parse', 'HEAD'), 'engine_base_sha': sha,
              'engine_policy': 'latest_official_non_prerelease_release',
              'engine_source_root': str(source), 'engine_install_root': str(install),
              'lock_sha256': hashlib.sha256((HERE / 'build-lock.json').read_bytes()).hexdigest(),
              'toolchain_requested': lock['toolchain'], 'freshness_status': 'unverified'}
    report_path = args.report_dir / 'source-provenance.json'
    write_json(report_path, report)
    # Query once. Later releases or tag changes cannot float this job's inputs.
    release = latest_release(lock)
    report['official_release_at_start'] = release
    expected = lock['official_release']
    current = (release['tag'] == expected['tag'] and release['id'] == expected['id'] and
               release['tag_object_sha'] == expected['tag_object_sha'] and release['commit_sha'] == sha)
    report['freshness_status'] = 'current' if current else 'stale'
    write_json(report_path, report)
    if not current:
        raise RuntimeError('Locked engine release is stale or its tag moved; review the latest official release, '
                           'update the release lock, and rerun. Never use master or relabel a different commit.')
    checkout_release(source, lock)
    report['engine_tree_objects'] = verify_engine_checkout(source, lock)
    report['engine_root_tree'] = git(source, 'rev-parse', 'HEAD^{tree}')
    report['engine_source_matches_locked_upstream'] = True
    write_json(report_path, report)
    print(f"Official MNN release {release['tag']} at {sha}; App {report['app_commit_sha']}; frozen at job start")


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, OSError, ValueError, KeyError, subprocess.CalledProcessError, subprocess.TimeoutExpired) as error:
        print(f'PRECHECK FAILED: {error}', file=sys.stderr)
        sys.exit(1)
