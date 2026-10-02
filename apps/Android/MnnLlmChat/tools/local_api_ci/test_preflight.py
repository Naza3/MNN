import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import preflight


class PreflightTest(unittest.TestCase):
    def run_check(self, report, dirty='', upstream=None):
        sha=json.loads((preflight.HERE/'build-lock.json').read_text())['engine_base_sha']
        def git(*args):
            if args[1]=='status': return dirty
            if args[1:]==('rev-parse','HEAD'): return 'b'*40
            if args[1]=='ls-remote': return f'{upstream or sha}\trefs/heads/master'
            if args[1]=='rev-parse': return 'c'*40
            raise AssertionError(args)
        with patch.object(preflight,'run',side_effect=git), patch.object(preflight.subprocess,'run') as checks:
            with patch('sys.argv',['preflight.py','--report-dir',str(report)]):
                preflight.main()
            return checks

    def test_dirty_input_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(RuntimeError,'dirty'):
                self.run_check(Path(directory),dirty=' M CMakeLists.txt')

    def test_stale_engine_rejected_and_reported(self):
        with tempfile.TemporaryDirectory() as directory:
            report=Path(directory)
            with self.assertRaisesRegex(RuntimeError,'stale'):
                self.run_check(report,upstream='d'*40)
            result=json.loads((report/'source-provenance.json').read_text())
            self.assertEqual('stale',result['freshness_status'])
            self.assertEqual('d'*40,result['upstream_sha_at_start'])

    def test_app_commit_and_engine_commit_are_distinct(self):
        with tempfile.TemporaryDirectory() as directory:
            report=Path(directory)
            checks=self.run_check(report)
            self.assertEqual(2,checks.call_count)
            result=json.loads((report/'source-provenance.json').read_text())
            self.assertEqual('current',result['freshness_status'])
            self.assertNotEqual(result['app_commit_sha'],result['engine_base_sha'])
            self.assertTrue(result['engine_source_matches_locked_upstream'])
            self.assertIn('schema/current',result['engine_tree_objects'])


if __name__=='__main__': unittest.main()
