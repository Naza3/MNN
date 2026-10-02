#!/usr/bin/env python3
"""Configure real App/TTS CMake entrypoints to verify isolated header/lib routing.

No App, MNN, or TTS source is compiled. Temporary empty include directories
exist only to inspect CMake's generated commands, not to produce any library.
"""
import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[4]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--cmake', type=Path, required=True)
    parser.add_argument('--report-dir', type=Path, required=True)
    args = parser.parse_args()
    report = {'coverage':'Real CMake configure-only root routing; no App/engine/native compilation',
              'cmake_version':subprocess.check_output([str(args.cmake),'--version'],text=True).splitlines()[0], 'cases':[]}
    with tempfile.TemporaryDirectory(prefix='mnn-engine-roots-') as temporary:
        root = Path(temporary)
        source = root/'official-release-inputs'
        include_paths = ['include', 'tools/audio/include', 'transformers/llm/engine/include',
                         'transformers/diffusion/engine/include', '3rd_party']
        for relative in include_paths:
            (source/relative).mkdir(parents=True,exist_ok=True)
        (source/'include/MNN').mkdir()
        (source/'include/MNN/Interpreter.hpp').write_text('// Configure-only file existence marker; never compiled\n')
        install = source/'project/android/build_64'
        env = os.environ.copy()
        env.update(MNN_ENGINE_SOURCE_ROOT=str(source), MNN_ENGINE_INSTALL_ROOT=str(install))
        try:
            for name, directory in [('app', ROOT/'apps/Android/MnnLlmChat/app/src/main/cpp'),
                                    ('tts', ROOT/'apps/frameworks/mnn_tts')]:
                build = root/(name+'-build')
                command = [str(args.cmake),'-G','Unix Makefiles','-S',str(directory),'-B',str(build),
                           '-DCMAKE_EXPORT_COMPILE_COMMANDS=ON', '-DMNN_ENGINE_SOURCE_ROOT:PATH='+str(source),
                           '-DMNN_ENGINE_INSTALL_ROOT:PATH='+str(install)]
                result = subprocess.run(command,env=env,capture_output=True,text=True)
                if result.returncode:
                    raise RuntimeError(name+' configure-only routing fixture failed: '+result.stderr[-3000:])
                cache = (build/'CMakeCache.txt').read_text()
                for key, value in [('MNN_ENGINE_SOURCE_ROOT', source), ('MNN_ENGINE_INSTALL_ROOT', install)]:
                    if key+':PATH='+str(value) not in cache:
                        raise RuntimeError(name+' root was not an explicit CMake cache input')
                entries = json.loads((build/'compile_commands.json').read_text())
                if not entries:
                    raise RuntimeError(name+' produced no compile-command routing evidence')
                required = include_paths if name == 'app' else include_paths[:3]
                for entry in entries:
                    for relative in required:
                        if str(source/relative) not in entry['command']:
                            raise RuntimeError(name+' omits official-release include root '+relative)
                    for relative in ('include', 'tools/audio/include', 'transformers/llm/engine/include'):
                        if str(ROOT/relative) in entry['command']:
                            raise RuntimeError(name+' leaked master engine headers into the release build')
                link = next(build.glob('CMakeFiles/*.dir/link.txt')).read_text()
                if str(install/'lib/libMNN.so') not in link:
                    raise RuntimeError(name+' link command did not select official-release libMNN')
                report['cases'].append({'name':name+'_source_and_install_routing','passed':True,
                                        'compile_commands':len(entries),'headers':required,'native_compiled':False})
                bad_env = dict(env, MNN_ENGINE_SOURCE_ROOT='relative-untrusted-root')
                bad = subprocess.run([str(args.cmake),'-S',str(directory),'-B',str(root/(name+'-bad'))],
                                     env=bad_env,capture_output=True,text=True)
                if not bad.returncode or 'must be an absolute engine source checkout' not in bad.stderr:
                    raise RuntimeError(name+' accepted an invalid engine source root')
                report['cases'].append({'name':name+'_invalid_source_root_rejected','passed':True})
            report['passed'] = True
        finally:
            args.report_dir.mkdir(parents=True,exist_ok=True)
            (args.report_dir/'engine-root-regression.json').write_text(json.dumps(report,indent=2)+'\n')
    print('Real CMake root-routing regression passed; no native library was compiled')


if __name__ == '__main__': main()
