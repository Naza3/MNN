import hashlib
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest

from audit_apk import validate_sherpa_provenance
from prepare_sherpa_sources import collect_archive_notices, verify_archive
from record_sherpa_build import verify_configuration


class SherpaSourcesTest(unittest.TestCase):
    def archive_fixture(self, path, link=False):
        with tarfile.open(path, 'w:gz') as archive:
            info = tarfile.TarInfo('fixture/LICENSE')
            if link:
                info.type = tarfile.SYMTYPE
                info.linkname = '../../outside'
                archive.addfile(info)
            else:
                data = b'Synthetic license fixture, not a binary or model'
                info.size = len(data)
                archive.addfile(info, io.BytesIO(data))
            # This must never be extracted, even when reading a valid notice.
            unsafe = tarfile.TarInfo('fixture/../../outside')
            unsafe.size = 3
            archive.addfile(unsafe, io.BytesIO(b'bad'))

    def test_verified_source_archive_notices_are_copied_without_extraction(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            archive = root/'fixture.tar.gz'
            self.archive_fixture(archive)
            dependency = {'name':'fixture', 'sha256':hashlib.sha256(archive.read_bytes()).hexdigest(), 'notices':['LICENSE']}
            verify_archive(archive, dependency)
            records = collect_archive_notices(archive, dependency, root/'notices')
            self.assertEqual(1, len(records))
            self.assertFalse((root/'outside').exists())
            self.assertIn(b'Synthetic license', (root/'notices/fixture--LICENSE').read_bytes())

    def test_wrong_source_bytes_fail_closed(self):
        with tempfile.TemporaryDirectory() as temp:
            archive = Path(temp)/'fixture.tar.gz'
            archive.write_bytes(b'unapproved source')
            with self.assertRaisesRegex(RuntimeError, 'hash mismatch'):
                verify_archive(archive, {'name':'fixture','sha256':'0'*64})

    def test_symlink_is_not_a_notice(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            archive = root/'fixture.tar.gz'
            self.archive_fixture(archive, link=True)
            with self.assertRaisesRegex(RuntimeError, 'Invalid notice'):
                collect_archive_notices(archive, {'name':'fixture','notices':['LICENSE']}, root/'notices')

    def test_missing_required_notice_fails(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            archive = root/'fixture.tar.gz'
            self.archive_fixture(archive)
            with self.assertRaises(KeyError):
                collect_archive_notices(archive, {'name':'fixture','notices':['NOTICE']}, root/'notices')

    def source_configuration(self):
        result = {'CMAKE_GENERATOR':'Unix Makefiles','BUILD_SHARED_LIBS':'OFF','CMAKE_POSITION_INDEPENDENT_CODE':'ON',
                  'ANDROID_ABI':'arm64-v8a','ANDROID_STL':'c++_shared',
                  'SHERPA_MNN_ENABLE_JNI':'ON','CMAKE_TLS_VERIFY':'ON',
                  'CMAKE_CXX_FLAGS':'-DEIGEN_MPL2_ONLY', 'SHERPA_MNN_BUILD_C_API_EXAMPLES':'OFF'}
        for feature in ('TTS','SPEAKER_DIARIZATION','BINARY','C_API','WEBSOCKET','PORTAUDIO','PYTHON','TESTS','CHECK'):
            result['SHERPA_MNN_ENABLE_'+feature]='OFF'
        return result

    def test_source_configuration_preserves_asr_and_restricts_unused_dependencies(self):
        self.assertEqual('ON', verify_configuration(self.source_configuration())['SHERPA_MNN_ENABLE_JNI'])

    def test_enabling_unused_tts_is_not_silently_allowed(self):
        cache = self.source_configuration()
        cache['SHERPA_MNN_ENABLE_TTS']='ON'
        with self.assertRaisesRegex(RuntimeError, 'SHERPA_MNN_ENABLE_TTS'):
            verify_configuration(cache)

    def test_eigen_license_scope_must_be_explicit(self):
        cache = self.source_configuration()
        cache['CMAKE_CXX_FLAGS']=''
        with self.assertRaisesRegex(RuntimeError, 'MPL2-only'):
            verify_configuration(cache)

    def test_provenance_rejects_old_engine_and_old_source_record(self):
        lock=json.loads((Path(__file__).parent/'build-lock.json').read_text())
        record={'source_kind':'built_from_locked_source', 'library_sha256':'sherpa-hash',
                'mnn_library_sha256':'mnn-hash', 'engine_base_sha':lock['engine_base_sha'],
                'source_root':lock['sherpa']['source_root'], 'source_tree_sha':lock['sherpa']['source_tree_sha'],
                'source_inputs_report_sha256':'inputs-hash'}
        self.assertEqual([],validate_sherpa_provenance(record,lock,'sherpa-hash','mnn-hash','inputs-hash'))
        self.assertTrue(validate_sherpa_provenance(record,lock,'sherpa-hash','changed-mnn','inputs-hash'))
        self.assertTrue(validate_sherpa_provenance(record,lock,'sherpa-hash','mnn-hash','changed-inputs'))
        record['source_tree_sha']='unreviewed-source'
        self.assertTrue(validate_sherpa_provenance(record,lock,'sherpa-hash','mnn-hash','inputs-hash'))

    def test_lock_pins_six_sources_and_twelve_required_jni_methods(self):
        lock = json.loads((Path(__file__).parent/'build-lock.json').read_text())['sherpa']
        self.assertEqual(6, len(lock['dependencies']))
        self.assertEqual(12, len(set(lock['required_jni_exports'])))
        self.assertNotIn('url', lock)  # no opaque prebuilt fallback
        self.assertTrue(all(len(dependency['sha256']) == 64 for dependency in lock['dependencies']))


if __name__ == '__main__':
    unittest.main()
