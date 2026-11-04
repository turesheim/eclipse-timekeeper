import json
import unittest
from unittest.mock import patch
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from ci_synology_revision import resolve

REPO = 'example/timekeeper'
COMMIT = 'a' * 40


def run(number, **changes):
    return {'run_number': number, 'head_sha': COMMIT, 'path': '.github/workflows/build.yml',
            'head_repository': {'full_name': REPO}, **changes}


class SynologyRevisionTest(unittest.TestCase):
    def test_called_workflow_uses_its_build_parent_without_an_api_request(self):
        with patch('ci_synology_revision.subprocess.check_output') as api:
            self.assertEqual(1043, resolve(REPO, COMMIT, '1043'))
            api.assert_not_called()

    def test_main_uses_the_same_build_sequence_instead_of_its_own_744_counter(self):
        pages = [{'workflow_runs': [run(1044)]}]
        with patch('ci_synology_revision.subprocess.check_output', return_value=json.dumps(pages)) as api:
            self.assertEqual(1044, resolve(REPO, COMMIT))
            self.assertIn('head_sha=' + COMMIT, api.call_args.args[0])

    def test_matching_build_runs_are_paginated_and_the_latest_revision_wins(self):
        pages = [{'workflow_runs': [run(1044)]}, {'workflow_runs': [run(1048)]}]
        with patch('ci_synology_revision.subprocess.check_output', return_value=json.dumps(pages)):
            self.assertEqual(1048, resolve(REPO, COMMIT))

    def test_other_source_repositories_and_workflows_cannot_supply_the_revision(self):
        for invalid in (run(1044, head_sha='b' * 40), run(1044, head_repository={'full_name': 'fork/timekeeper'}),
                        run(1044, path='.github/workflows/server-containers.yml')):
            with self.subTest(invalid=invalid), patch('ci_synology_revision.subprocess.check_output', return_value=json.dumps([{'workflow_runs': [invalid]}])):
                with self.assertRaisesRegex(ValueError, 'No matching Build'):
                    resolve(REPO, COMMIT, attempts=1)

    def test_a_delayed_build_run_or_transient_api_failure_is_retried(self):
        for first in (json.dumps([{'workflow_runs': []}]), subprocess.CalledProcessError(1, ['gh'])):
            with self.subTest(first=first), patch('ci_synology_revision.subprocess.check_output', side_effect=[first, json.dumps([{'workflow_runs': [run(1044)]}])]), patch('ci_synology_revision.time.sleep'):
                self.assertEqual(1044, resolve(REPO, COMMIT, attempts=2))

    def test_missing_runs_and_invalid_revisions_fail_without_falling_back(self):
        with patch('ci_synology_revision.subprocess.check_output', return_value=json.dumps([{'workflow_runs': []}])):
            with self.assertRaisesRegex(ValueError, 'No matching Build'):
                resolve(REPO, COMMIT, attempts=1)
        for value in ('-1', '1.5', 'latest', True):
            with self.subTest(value=value), self.assertRaises(ValueError):
                resolve(REPO, COMMIT, value)
