#!/usr/bin/env python3
"""A selected check must succeed; an unexpected skip is a failure."""
import json
import os


def failures(needs):
    changes = needs['Changes']
    if changes['result'] != 'success':
        return ['Changes']
    selected = changes['outputs']
    jobs = {'API-contract': 'api', 'Server': 'server', 'Web': 'web', 'Build': 'eclipse',
            'Remote-Eclipse': 'remote_eclipse', 'Containers': 'containers', 'Publication': 'site'}
    if any(selected.get(flag) not in ('true', 'false') for flag in set(jobs.values())):
        return ['Invalid validation selection']
    expected = {name for name, flag in jobs.items() if selected[flag] == 'true'} | {'Publication-tooling', 'Agent-quality'}
    return sorted(name for name in expected if needs.get(name, {}).get('result') != 'success')



if __name__ == '__main__':
    failed = failures(json.loads(os.environ['CI_NEEDS']))
    if failed:
        raise SystemExit('Required validation did not succeed: ' + ', '.join(failed))
    print('All selected validation passed.')
