import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import preflight

LOCK = json.loads((preflight.HERE / 'build-lock.json').read_text())


def release_record():
    return dict(LOCK['official_release'], commit_sha=LOCK['engine_base_sha'],
                published_at='2026-07-23T02:13:32Z', draft=False, prerelease=False,
                url='https://github.com/alibaba/MNN/releases/tag/3.6.1')


class PreflightTest(unittest.TestCase):
    def run_check(self, report, dirty='', changes=None):
        release = release_record()
        release.update(changes or {})
        def git(root, *args):
            if args[0] == 'status': return dirty
            if args == ('rev-parse', 'HEAD'): return 'b' * 40
            if args == ('rev-parse', 'HEAD^{tree}'): return 'c' * 40
            raise AssertionError(args)
        source = Path('/tmp/synthetic-independent-official-release')
        with patch.object(preflight, 'git', side_effect=git), \
                patch.object(preflight, 'engine_paths', return_value=(source, source/'project/android/build_64')), \
                patch.object(preflight, 'latest_release', return_value=release), \
                patch.object(preflight, 'checkout_release') as checkout, \
                patch.object(preflight, 'verify_engine_checkout', return_value={p:'c'*40 for p in preflight.ENGINE_PATHS}):
            with patch('sys.argv', ['preflight.py', '--report-dir', str(report)]):
                preflight.main()
            return checkout

    def test_dirty_input_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(RuntimeError, 'dirty'):
                self.run_check(Path(directory), dirty=' M CMakeLists.txt')

    def test_new_release_and_moved_tag_fail_closed(self):
        for changed in ({'commit_sha':'d'*40}, {'tag':'3.6.2'}, {'id':1}, {'tag_object_sha':'e'*40}):
            with self.subTest(changed=changed), tempfile.TemporaryDirectory() as directory:
                report = Path(directory)
                with self.assertRaisesRegex(RuntimeError, 'stale'):
                    self.run_check(report, changes=changed)
                result = json.loads((report/'source-provenance.json').read_text())
                self.assertEqual('stale', result['freshness_status'])

    def test_app_commit_and_release_commit_are_distinct(self):
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory)
            checkout = self.run_check(report)
            checkout.assert_called_once()
            result = json.loads((report/'source-provenance.json').read_text())
            self.assertEqual('current', result['freshness_status'])
            self.assertNotEqual(result['app_commit_sha'], result['engine_base_sha'])
            self.assertTrue(result['engine_source_matches_locked_upstream'])
            self.assertIn('schema/current', result['engine_tree_objects'])
            self.assertEqual('latest_official_non_prerelease_release', result['engine_policy'])

    def test_annotated_release_tag_is_peeled_to_commit(self):
        release = dict(tag_name='3.6.1', id=358027083, published_at='date', html_url='url', draft=False, prerelease=False)
        responses = [release, {'object':{'type':'tag','sha':'a'*40}}, {'object':{'type':'commit','sha':'b'*40}}]
        with patch.object(preflight, 'github_json', side_effect=responses) as fetch:
            result = preflight.latest_release(LOCK)
            self.assertEqual('b'*40, result['commit_sha'])
            self.assertEqual('a'*40, result['tag_object_sha'])
            self.assertEqual(3, fetch.call_count)
            self.assertTrue(fetch.call_args_list[0].args[0].endswith('/releases/latest'))

    def test_draft_prerelease_or_unpeelable_tag_rejected(self):
        for flag in ('draft', 'prerelease'):
            with patch.object(preflight, 'github_json', return_value=dict(tag_name='new', draft=False, prerelease=False, **{} ) | {flag:True}):
                with self.assertRaisesRegex(RuntimeError, 'stable'): preflight.latest_release(LOCK)
        responses = [{'tag_name':'new','draft':False,'prerelease':False}, {'object':{'type':'tree','sha':'a'*40}}]
        with patch.object(preflight, 'github_json', side_effect=responses):
            with self.assertRaisesRegex(RuntimeError, 'resolved'): preflight.latest_release(LOCK)
