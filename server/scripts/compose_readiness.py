"""Wait for fresh replica health and matching HAProxy routing after an outage."""
import csv
import io
import time
from urllib.error import HTTPError, URLError
from urllib.request import urlopen


def wait_ready(compose, base, members=('api-1', 'api-2'), timeout=120):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            stats = compose('exec', '-T', 'gateway', 'sh', '-c',
                            'printf "show stat\\n" | nc -w 1 127.0.0.1 9999')
            rows = csv.DictReader(io.StringIO(stats))
            states = {row['svname']: row['status'] for row in rows
                      if row['# pxname'] == 'api' and row['svname'] in ('api-1', 'api-2')}
            # A single gateway request can succeed while the next is routed to
            # an unhealthy replica. Docker health status can also predate the outage.
            if (set(states) == {'api-1', 'api-2'} and
                    {member for member, state in states.items() if state == 'UP'} == set(members)):
                for member in members:
                    compose('exec', '-T', member, 'curl', '--fail', '--silent', '--show-error',
                            '--max-time', '3', 'http://localhost:8080/q/health/ready')
                with urlopen(base + '/q/health/ready', timeout=2) as response:
                    if response.status == 200:
                        return
        except (HTTPError, URLError, TimeoutError, RuntimeError):
            pass
        time.sleep(.5)
    raise AssertionError('Compose replicas and gateway did not recover readiness: ' + ', '.join(members))
