"""Independent, source-built official release identity and path guards."""
import json
import os
from pathlib import Path
import subprocess

HERE = Path(__file__).resolve().parent
APP_ROOT = HERE.parents[4]
ENGINE_PATHS = ('CMakeLists.txt', 'include', 'source', 'express', 'transformers',
                'tools', 'project/android', '3rd_party', 'schema/default', 'schema/current',
                'cmake', 'codegen', 'apps/frameworks/sherpa-mnn')


def git(root, *args):
    return subprocess.check_output(['git', '-C', str(root), *args], text=True, timeout=180).strip()


def engine_paths():
    source_value = os.environ.get('MNN_ENGINE_SOURCE_ROOT', '')
    install_value = os.environ.get('MNN_ENGINE_INSTALL_ROOT', '')
    source, install = Path(source_value), Path(install_value)
    if not source_value or not install_value or not source.is_absolute() or not install.is_absolute():
        raise RuntimeError('Set absolute MNN_ENGINE_SOURCE_ROOT and MNN_ENGINE_INSTALL_ROOT for the pinned release')
    source, install = source.resolve(), install.resolve()
    if source == APP_ROOT or source.is_relative_to(APP_ROOT) or APP_ROOT.is_relative_to(source):
        raise RuntimeError('Official release source checkout must be separate from the App checkout')
    if install != source / 'project/android/build_64':
        raise RuntimeError('Engine install root must belong to the same independent release checkout')
    return source, install


def verify_engine_checkout(root, lock):
    if git(root, 'rev-parse', 'HEAD') != lock['engine_base_sha']:
        raise RuntimeError('Independent engine checkout is not the locked official release commit')
    if git(root, 'status', '--porcelain', '--untracked-files=normal'):
        raise RuntimeError('Independent engine source checkout has unreviewed modifications')
    return {path: git(root, 'rev-parse', 'HEAD:' + path) for path in ENGINE_PATHS}


def verified_engine_paths(report_dir=None):
    source, install = engine_paths()
    lock = json.loads((HERE / 'build-lock.json').read_text())
    verify_engine_checkout(source, lock)
    if report_dir:
        report = json.loads((Path(report_dir) / 'source-provenance.json').read_text())
        if (report.get('engine_base_sha') != lock['engine_base_sha'] or
                report.get('engine_source_root') != str(source) or
                report.get('engine_install_root') != str(install) or
                report.get('freshness_status') != 'current'):
            raise RuntimeError('Engine source path does not match the frozen release provenance')
    return source, install


def verify_app_native_roots(source, install):
    """Check actual AGP CMake caches and compile commands, not just requested flags."""
    import hashlib
    records = []
    modules = [('app', APP_ROOT/'apps/Android/MnnLlmChat/app',
                APP_ROOT/'apps/Android/MnnLlmChat/app/src/main/cpp'),
               ('mnn_tts', APP_ROOT/'apps/frameworks/mnn_tts/android',
                APP_ROOT/'apps/frameworks/mnn_tts')]
    for name, module, cmake_home in modules:
        caches = sorted(p for p in (module/'.cxx').glob('**/CMakeCache.txt') if p.parent.name == 'arm64-v8a')
        if not caches:
            raise RuntimeError('No actual AGP native configuration found for ' + name)
        for cache_path in caches:
            cache = {}
            for line in cache_path.read_text().splitlines():
                if line and not line.startswith(('#', '//')) and ':' in line and '=' in line:
                    key, value = line.split('=', 1)
                    cache[key.split(':', 1)[0]] = value
            expected = {'MNN_ENGINE_SOURCE_ROOT': source, 'MNN_ENGINE_INSTALL_ROOT': install,
                        'CMAKE_HOME_DIRECTORY': cmake_home}
            if any(Path(cache.get(key, '')).resolve() != value.resolve() for key, value in expected.items()):
                raise RuntimeError('Actual native configuration mixed App/release source roots: ' + name)
            if cache.get('ANDROID_ABI') != 'arm64-v8a':
                raise RuntimeError('Unexpected actual native ABI for ' + name)
            commands_file = cache_path.parent/'compile_commands.json'
            commands = json.loads(commands_file.read_text())
            required = ['include', 'tools/audio/include', 'transformers/llm/engine/include']
            if name == 'app':
                required += ['transformers/diffusion/engine/include', '3rd_party']
            if not commands:
                raise RuntimeError('Native compile-command evidence is empty: ' + name)
            for command in commands:
                text = command.get('command') or ' '.join(command['arguments'])
                if any(str(source/path) not in text for path in required):
                    raise RuntimeError('Actual native compilation lacks release headers: ' + name)
                if any(str(APP_ROOT/path) in text for path in required):
                    raise RuntimeError('Actual native compilation used App-checkout engine headers: ' + name)
            records.append({'module': name, 'configuration': str(cache_path.parent.relative_to(module)),
                            'engine_commit_sha': git(source, 'rev-parse', 'HEAD'),
                            'compile_commands': len(commands), 'release_header_roots_verified': required,
                            'compile_commands_sha256': hashlib.sha256(commands_file.read_bytes()).hexdigest(),
                            'source_and_install_roots_matched': True})
    return records


if __name__ == '__main__':
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument('--report-dir', type=Path, required=True)
    arguments = parser.parse_args()
    verified_engine_paths(arguments.report_dir)
    print('Independent official-release source and install roots verified')
