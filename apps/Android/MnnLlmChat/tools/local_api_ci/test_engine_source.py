import os
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import engine_source
import preflight


class EngineSourceTest(unittest.TestCase):
    def test_source_install_and_app_checkouts_cannot_be_mixed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for source, install in [(engine_source.APP_ROOT, engine_source.APP_ROOT/'project/android/build_64'),
                                    (root, root/'wrong-install'), (Path('relative'), Path('relative/build'))]:
                with patch.dict(os.environ, {'MNN_ENGINE_SOURCE_ROOT':str(source), 'MNN_ENGINE_INSTALL_ROOT':str(install)}):
                    with self.assertRaises(RuntimeError): engine_source.engine_paths()
            with patch.dict(os.environ, {'MNN_ENGINE_SOURCE_ROOT':str(root), 'MNN_ENGINE_INSTALL_ROOT':str(root/'project/android/build_64')}):
                self.assertEqual((root, root/'project/android/build_64'), engine_source.engine_paths())

    def test_actual_git_checkout_is_detached_exact_and_refuses_dirty_or_reuse(self):
        # Actual Git fixture, no network, engine sources, build tools, or native stubs.
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            origin, checkout = root/'origin', root/'checkout'
            origin.mkdir()
            (origin/'CMakeLists.txt').write_text('# Synthetic checkout identity fixture\n')
            subprocess.run(['git','init','-q',str(origin)],check=True)
            subprocess.run(['git','-C',str(origin),'add','.'],check=True)
            subprocess.run(['git','-C',str(origin),'-c','user.name=CI Fixture','-c','user.email=fixture@example.invalid','commit','-qm','Fixture'],check=True)
            sha = engine_source.git(origin,'rev-parse','HEAD')
            lock = {'upstream_url':str(origin), 'engine_base_sha':sha}
            preflight.checkout_release(checkout,lock)
            self.assertEqual(sha,engine_source.git(checkout,'rev-parse','HEAD'))
            result = subprocess.run(['git','-C',str(checkout),'symbolic-ref','-q','HEAD'],capture_output=True)
            self.assertNotEqual(0,result.returncode)
            with patch.object(engine_source,'ENGINE_PATHS',('CMakeLists.txt',)):
                engine_source.verify_engine_checkout(checkout,lock)
                with self.assertRaisesRegex(RuntimeError,'reuse'):preflight.checkout_release(checkout,lock)
                (checkout/'CMakeLists.txt').write_text('# Dirty source\n')
                with self.assertRaisesRegex(RuntimeError,'modifications'):engine_source.verify_engine_checkout(checkout,lock)
                with self.assertRaisesRegex(RuntimeError,'locked'):engine_source.verify_engine_checkout(checkout,dict(lock,engine_base_sha='b'*40))

    def test_actual_native_cache_and_header_commands_are_cross_checked(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            app, engine = root/'app-checkout', root/'release-checkout'
            install = engine/'project/android/build_64'
            modules = [(app/'apps/Android/MnnLlmChat/app', app/'apps/Android/MnnLlmChat/app/src/main/cpp'),
                       (app/'apps/frameworks/mnn_tts/android', app/'apps/frameworks/mnn_tts')]
            paths = ['include','tools/audio/include','transformers/llm/engine/include',
                     'transformers/diffusion/engine/include','3rd_party']
            command = 'c++ ' + ' '.join('-I'+str(engine/p) for p in paths)
            for module, home in modules:
                build = module/'.cxx/Release/fixture/arm64-v8a'
                build.mkdir(parents=True)
                (build/'CMakeCache.txt').write_text('\n'.join([
                    'MNN_ENGINE_SOURCE_ROOT:PATH='+str(engine), 'MNN_ENGINE_INSTALL_ROOT:PATH='+str(install),
                    'CMAKE_HOME_DIRECTORY:INTERNAL='+str(home), 'ANDROID_ABI:STRING=arm64-v8a']))
                (build/'compile_commands.json').write_text(json.dumps([{'command':command}]))
            with patch.object(engine_source,'APP_ROOT',app), patch.object(engine_source,'git',return_value='a'*40):
                self.assertEqual(2,len(engine_source.verify_app_native_roots(engine,install)))
                commands = modules[0][0]/'.cxx/Release/fixture/arm64-v8a/compile_commands.json'
                commands.write_text(json.dumps([{'command':command+' -I'+str(app/'include')}]))
                with self.assertRaisesRegex(RuntimeError,'App-checkout engine headers'):
                    engine_source.verify_app_native_roots(engine,install)
                commands.write_text(json.dumps([{'command':'c++ -I'+str(app/'include')}]))
                with self.assertRaisesRegex(RuntimeError,'lacks release headers'):
                    engine_source.verify_app_native_roots(engine,install)
                commands.write_text(json.dumps([{'command':command}]))
                cache = commands.parent/'CMakeCache.txt'
                cache.write_text(cache.read_text().replace(str(install),str(app/'project/android/build_64')))
                with self.assertRaisesRegex(RuntimeError,'mixed App/release'):
                    engine_source.verify_app_native_roots(engine,install)
