#!/usr/bin/env python3
"""Use the repository's Build workflow sequence for every Synology installer."""
import json
import os
from pathlib import Path
import re
import subprocess
import time


def positive(value):
    if isinstance(value, bool) or not re.fullmatch(r'[1-9][0-9]*', str(value)):
        raise ValueError('A positive Build workflow revision is required')
    return int(value)


def resolve(repo, commit, parent='0', attempts=15):
    if str(parent) not in ('', '0'):
        return positive(parent)
    if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repo) or not re.fullmatch(r'[a-f0-9]{40}', commit):
        raise ValueError('Use a repository name and full source commit')
    for attempt in range(attempts):
        try:
            pages = json.loads(subprocess.check_output([
                'gh', 'api', f'repos/{repo}/actions/workflows/build.yml/runs',
                '--method', 'GET', '-f', f'head_sha={commit}', '-f', 'per_page=100',
                '--paginate', '--slurp'], text=True))
            runs = [run for page in pages for run in page['workflow_runs']
                    if run.get('head_sha') == commit
                    and run.get('path') == '.github/workflows/build.yml'
                    and run.get('head_repository', {}).get('full_name') == repo]
            if runs:
                return max(positive(run['run_number']) for run in runs)
        except subprocess.CalledProcessError:
            # The independent main workflows can start together while GitHub
            # is still creating the Build run, or briefly return a server error.
            pass
        if attempt + 1 < attempts:
            time.sleep(2)
    raise ValueError('No matching Build workflow run is available; refusing a separate installer revision sequence')


if __name__ == '__main__':
    revision = resolve(os.environ['GITHUB_REPOSITORY'], os.environ['GITHUB_SHA'], os.environ.get('PARENT_BUILD_REVISION', '0'))
    if output := os.environ.get('GITHUB_OUTPUT'):
        with Path(output).open('a') as stream:
            stream.write(f'revision={revision}\n')
    print(f'Synology revision: {revision} (Build workflow)')
