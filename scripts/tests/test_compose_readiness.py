"""A healthy response from one replica must not hide another replica's outage."""
from pathlib import Path
import sys
import unittest
from unittest.mock import MagicMock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'server/scripts'))
from compose_readiness import wait_ready


class ComposeReadinessTest(unittest.TestCase):
    def wait(self, compose, members=('api-1', 'api-2')):
        response = MagicMock()
        response.__enter__.return_value.status = 200
        with patch('compose_readiness.urlopen', return_value=response) as gateway, \
                patch('compose_readiness.time.sleep') as sleep:
            wait_ready(compose, 'http://gateway', members)
        return gateway, sleep

    @staticmethod
    def stats(first='UP', second='UP'):
        return f'# pxname,svname,status\napi,api-1,{first}\napi,api-2,{second}\n'

    def test_stale_proxy_health_does_not_hide_a_replica_still_reconnecting(self):
        attempts = 0

        def compose(*args):
            nonlocal attempts
            if args[2] == 'gateway':
                attempts += 1
                return self.stats()
            if args[2] == 'api-1' and attempts == 1:
                raise RuntimeError('Readiness returned HTTP 503 during database recovery')
            return ''

        gateway, sleep = self.wait(compose)
        self.assertEqual(2, attempts)
        sleep.assert_called_once()
        gateway.assert_called_once()

    def test_waits_until_both_recovered_replicas_are_routed(self):
        compose = MagicMock(side_effect=[self.stats(second='DOWN'), self.stats(), '', ''])
        gateway, sleep = self.wait(compose)
        sleep.assert_called_once()
        gateway.assert_called_once()

    def test_failover_waits_until_stopped_replica_is_removed_from_routing(self):
        compose = MagicMock(side_effect=[self.stats(), self.stats(first='DOWN'), ''])
        gateway, sleep = self.wait(compose, ('api-2',))
        sleep.assert_called_once()
        gateway.assert_called_once()
        self.assertFalse(any(call.args[2] == 'api-1' for call in compose.call_args_list))

    def test_unrecovered_replica_fails_within_deadline(self):
        with patch('compose_readiness.time.monotonic', side_effect=[0, 0, 121]), \
                patch('compose_readiness.time.sleep'), patch('compose_readiness.urlopen') as gateway:
            with self.assertRaisesRegex(AssertionError, 'api-1, api-2'):
                wait_ready(MagicMock(return_value=self.stats(second='DOWN')), 'http://gateway')
        gateway.assert_not_called()
