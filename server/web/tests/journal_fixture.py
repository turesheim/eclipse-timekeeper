"""Private operator configuration fixture; real rsync/sshd acceptance is a separate test."""
import base64
import hashlib
import http.server
import json
from pathlib import Path
import secrets
import struct
import threading


class JournalFixture:
    def __init__(self, directory):
        self.root=Path(directory).resolve()/'journal-fixture';self.root.mkdir(mode=0o700)
        self.incoming=self.root/'incoming';self.incoming.mkdir(mode=0o700)
        self.configuration=self.root/'journal.json';self.accounts={};self.lock=threading.Lock()
        raw=struct.pack('>I',11)+b'ssh-ed25519'+struct.pack('>I',32)+secrets.token_bytes(32)
        self.host_key='ssh-ed25519 '+base64.b64encode(raw).decode();self.secret='/'+secrets.token_urlsafe(32)
        self.write();fixture=self

        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self,*args):pass
            def do_POST(self):
                if self.path!=fixture.secret+'/accounts':self.send_error(404);return
                try:
                    size=int(self.headers.get('Content-Length','0'))
                    if size>4096:raise ValueError()
                    owner=json.loads(self.rfile.read(size))['ownerId']
                    if not isinstance(owner,str) or not owner or len(owner)>200:raise ValueError()
                    with fixture.lock:
                        if owner not in fixture.accounts:
                            fixture.accounts[owner]={'ownerId':owner,'username':'journal_'+hashlib.sha256(owner.encode()).hexdigest()[:16],'token':secrets.token_urlsafe(32)}
                            fixture.write()
                        binding=fixture.accounts[owner]
                    body=json.dumps({'username':binding['username'],'token':binding['token']}).encode()
                    self.send_response(201);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body)
                except (ValueError,KeyError):self.send_error(400)
        self.server=http.server.ThreadingHTTPServer(('127.0.0.1',0),Handler);self.thread=threading.Thread(target=self.server.serve_forever,daemon=True);self.thread.start()
        self.environment={'TIMEKEEPER_JOURNAL_CONFIGURATION_FILE':str(self.configuration),'TIMEKEEPER_WEB_JOURNAL_FIXTURE':f'http://127.0.0.1:{self.server.server_port}'+self.secret}

    def write(self):
        value={'format':1,'hostname':'journal.example.invalid','port':2222,'hostKey':self.host_key,'incomingRoot':str(self.incoming),'encryptedStorage':True,'backupKeysSeparated':True,
               'accounts':[{'ownerId':entry['ownerId'],'username':entry['username'],'brokerTokenSha256':hashlib.sha256(entry['token'].encode()).hexdigest()} for entry in self.accounts.values()]}
        temporary=self.configuration.with_suffix('.new');temporary.write_text(json.dumps(value));temporary.chmod(0o600);temporary.replace(self.configuration)

    def close(self):
        self.server.shutdown();self.server.server_close();self.thread.join(timeout=5)
