"""The loopback browser proxy must survive preconnections and cached module bursts."""
from concurrent.futures import ThreadPoolExecutor
import http.client
import http.server
import json
from pathlib import Path
import socket
import ssl
import sys
import tempfile
import threading
import unittest
from urllib.parse import urlsplit

sys.path.insert(0,str(Path(__file__).resolve().parents[2]/'server/web/tests'))
from recovery_fixture import RecoveryFixture


class BrowserTLSFixtureTest(unittest.TestCase):
    def test_idle_preconnection_and_parallel_cached_modules_do_not_block_or_reset_requests(self):
        body=b'export const fixture = true;\n'
        class Backend(http.server.BaseHTTPRequestHandler):
            def log_message(self,*args):pass
            def do_GET(self):
                if self.headers.get('If-None-Match')=='"fixture"':self.send_response(304);self.send_header('ETag','"fixture"');self.end_headers();return
                self.send_response(200);self.send_header('Content-Type','text/javascript');self.send_header('Content-Length',str(len(body)));self.send_header('ETag','"fixture"');self.end_headers();self.wfile.write(body)
            def dav(self):
                incoming=self.rfile.read(int(self.headers.get('Content-Length','0')))
                value=json.dumps({'method':self.command,'body':incoming.decode(),'destination':self.headers.get('Destination'),'etag':self.headers.get('If-Match')}).encode()
                self.send_response(207 if self.command=='PROPFIND' else 201);self.send_header('Content-Length',str(len(value)));self.end_headers();self.wfile.write(value)
            do_PROPFIND=do_MKCOL=do_MOVE=dav
        class BackendServer(http.server.ThreadingHTTPServer):request_queue_size=128
        backend=BackendServer(('127.0.0.1',0),Backend)
        threading.Thread(target=backend.serve_forever,daemon=True).start()
        try:
            with tempfile.TemporaryDirectory() as temporary:
                fixture=RecoveryFixture(Path(temporary),backend.server_port,https=True,hostname='localhost')
                address=urlsplit(fixture.base)
                idle=socket.create_connection(('127.0.0.1',address.port),timeout=2)
                try:
                    def request(index):
                        client=http.client.HTTPSConnection('127.0.0.1',address.port,timeout=3,context=ssl._create_unverified_context())
                        try:
                            for cached in (False,True,False):
                                client.request('GET',f'/web/module-{index}.js',headers={'If-None-Match':'"fixture"'} if cached else {})
                                response=client.getresponse();value=response.read()
                                self.assertEqual(304 if cached else 200,response.status)
                                self.assertEqual(b'' if cached else body,value)
                                if cached:self.assertIsNone(response.getheader('Content-Length'))
                        finally:client.close()
                    with ThreadPoolExecutor(max_workers=12) as pool:list(pool.map(request,range(36)))
                    client=http.client.HTTPSConnection('127.0.0.1',address.port,timeout=3,context=ssl._create_unverified_context())
                    try:
                        for method in ('PROPFIND','MKCOL','MOVE'):
                            client.request(method,'/dav/notes/',body=b'<propfind/>',headers={'Destination':'/dav/notes/moved.md','If-Match':'"fixture"'})
                            response=client.getresponse();value=json.loads(response.read())
                            self.assertEqual(207 if method=='PROPFIND' else 201,response.status)
                            self.assertEqual({'method':method,'body':'<propfind/>','destination':'/dav/notes/moved.md','etag':'"fixture"'},value)
                    finally:client.close()
                finally:idle.close();fixture.close()
        finally:backend.shutdown();backend.server_close()
