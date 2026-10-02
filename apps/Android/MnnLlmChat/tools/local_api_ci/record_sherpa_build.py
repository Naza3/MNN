#!/usr/bin/env python3
"""Record actual source-built Sherpa inputs, link closure, and generated output."""
import argparse
import datetime
import json
import os
from pathlib import Path
import subprocess

from audit_apk import dynamic_symbols, elf_load_segments
from prepare_sherpa_sources import sha256
from engine_source import verified_engine_paths

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[4]


def read_cache(path):
    values = {}
    for line in path.read_text().splitlines():
        if not line or line.startswith(('#', '//')) or '=' not in line or ':' not in line.split('=', 1)[0]:
            continue
        key, value = line.split('=', 1)
        values[key.split(':', 1)[0]] = value
    return values


def verify_configuration(cache):
    expected = {'CMAKE_GENERATOR':'Unix Makefiles', 'BUILD_SHARED_LIBS':'OFF', 'CMAKE_POSITION_INDEPENDENT_CODE':'ON',
                'ANDROID_ABI':'arm64-v8a', 'ANDROID_STL':'c++_shared',
                'SHERPA_MNN_ENABLE_JNI':'ON', 'CMAKE_TLS_VERIFY':'ON'}
    for feature in ('TTS', 'SPEAKER_DIARIZATION', 'BINARY', 'C_API', 'WEBSOCKET',
                    'PORTAUDIO', 'PYTHON', 'TESTS', 'CHECK'):
        expected['SHERPA_MNN_ENABLE_' + feature] = 'OFF'
    expected['SHERPA_MNN_BUILD_C_API_EXAMPLES'] = 'OFF'
    for key, value in expected.items():
        if cache.get(key) != value:
            raise RuntimeError(f'Unexpected Sherpa build option {key}: {cache.get(key)}')
    if '-DEIGEN_MPL2_ONLY' not in cache.get('CMAKE_CXX_FLAGS', '').split():
        raise RuntimeError('Eigen MPL2-only constraint is missing')
    return {key: cache[key] for key in sorted(cache) if key.startswith(
        ('SHERPA_', 'ANDROID_', 'CMAKE_CXX_FLAGS', 'CMAKE_SHARED_LINKER_FLAGS', 'MNN_LIB_DIR', 'CMAKE_TLS_VERIFY', 'BUILD_SHARED_LIBS'))}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--build-dir', type=Path, required=True)
    parser.add_argument('--library', type=Path, required=True)
    parser.add_argument('--report-dir', type=Path, required=True)
    args = parser.parse_args()
    config = json.loads((HERE/'build-lock.json').read_text())
    lock = config['sherpa']
    engine_root, engine_install = verified_engine_paths(args.report_dir)
    inputs = json.loads((args.report_dir/'sherpa-source-inputs.json').read_text())
    if inputs['status'] != 'verified':
        raise RuntimeError('Verified source inputs are missing')
    expected = {dependency['name'] for dependency in lock['dependencies']}
    actual = {path.name.removesuffix('-src') for path in (args.build_dir/'_deps').glob('*-src') if path.is_dir()}
    if actual != expected:
        raise RuntimeError(f'Unexpected fetched source closure: expected {sorted(expected)}, got {sorted(actual)}')
    options = verify_configuration(read_cache(args.build_dir/'CMakeCache.txt'))
    sdk = Path(os.environ['ANDROID_SDK_ROOT'])
    readelf = sdk/'ndk'/config['toolchain']['ndk']/'toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf'
    symbols = subprocess.check_output([str(readelf), '--dyn-syms', '--wide', str(args.library)], text=True)
    exports, _ = dynamic_symbols(symbols)
    missing = sorted(set(lock['required_jni_exports']) - exports)
    if missing:
        raise RuntimeError('Missing App ASR JNI methods: ' + ', '.join(missing))
    segments = elf_load_segments(args.library.read_bytes())
    if any(segment['alignment'] < 16384 or (segment['offset'] - segment['virtual_address']) % 16384 for segment in segments):
        raise RuntimeError('Built Sherpa JNI is not 16KiB aligned')
    # Keep actual link commands and archive hashes, not the complete build/cache.
    link_files = sorted(args.build_dir.glob('**/CMakeFiles/sherpa-mnn-jni.dir/link.txt'))
    if len(link_files) != 1:
        raise RuntimeError('Expected one auditable Sherpa JNI link command')
    link_text = link_files[0].read_text().replace(str(engine_root), '<official-release>').replace(str(ROOT), '<app-checkout>').replace(str(sdk), '<android-sdk>')
    (args.report_dir/'sherpa-link.txt').write_text(link_text)
    static_archives = [{'path': str(path.relative_to(args.build_dir)), 'sha256': sha256(path), 'size': path.stat().st_size}
                       for path in sorted(args.build_dir.glob('**/*.a'))]
    available = {Path(archive['path']).name for archive in static_archives}
    missing_archives = sorted(set(lock['required_static_archives']) - available)
    if missing_archives:
        raise RuntimeError('Missing expected static link inputs: ' + ', '.join(missing_archives))
    if any(name not in link_text for name in lock['required_static_archives']):
        raise RuntimeError('Sherpa JNI link command does not reference all expected static inputs')
    report = {'source_kind':'built_from_locked_source',
              'engine_base_sha':config['engine_base_sha'], 'source_root':lock['source_root'],
              'source_tree_sha':lock['source_tree_sha'],
              'app_commit_sha':subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),
              'built_at_utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),
              'library_sha256':sha256(args.library), 'load_segments':segments,
              'mnn_library_sha256':sha256(engine_install/'lib/libMNN.so'),
              'required_jni_exports':lock['required_jni_exports'],
              'source_inputs_report_sha256':sha256(args.report_dir/'sherpa-source-inputs.json'),
              'static_archives':static_archives, 'configuration':options,
              'upstream_source_patch':'OpenFST FetchContent PATCH_COMMAND from locked cmake/openfst.cmake; original archives preserved',
              'runtime_validation':'not_run: real-device ASR smoke still required'}
    (args.report_dir/'sherpa-provenance.json').write_text(json.dumps(report,indent=2)+'\n')
    print('Source-built Sherpa JNI verified: exact input closure, 12 App JNI exports, 16KiB alignment')


if __name__ == '__main__':
    main()
