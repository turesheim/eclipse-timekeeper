"""Ephemeral loopback iCalendar provider, controlled by an unguessable test URL."""
import hashlib
import http.server
import json
import secrets
import threading
from urllib.parse import urlsplit


class CalendarFixture:
    def __init__(self):
        fixture=self
        self.lock=threading.Lock();self.feeds={};self.secret='/'+secrets.token_urlsafe(32)

        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self,*args):pass
            def send(self,status,body=b'',content_type='application/json',etag=None):
                self.send_response(status);self.send_header('Content-Type',content_type);self.send_header('Content-Length',str(len(body)))
                if etag:self.send_header('ETag',etag)
                self.end_headers()
                if body:
                    try:self.wfile.write(body)
                    except (BrokenPipeError,ConnectionResetError):pass
            def payload(self):
                length=int(self.headers.get('Content-Length','0'))
                if length>3*1024*1024:raise ValueError('Oversized fixture input')
                return json.loads(self.rfile.read(length))
            def address(self):
                path=urlsplit(self.path).path
                if not path.startswith(fixture.secret+'/feeds'):return None
                return path[len(fixture.secret+'/feeds'):]
            def do_POST(self):
                if self.address()!='':self.send(404);return
                try:values=self.payload();body=values['body'];assert isinstance(body,str)
                except (ValueError,KeyError,AssertionError):self.send(400);return
                identifier=secrets.token_urlsafe(18);gate=threading.Event()
                if not values.get('blocked'):gate.set()
                with fixture.lock:fixture.feeds[identifier]={'body':body,'status':values.get('status',200),'gate':gate,'requests':0,'headers':{}}
                control=fixture.base+'/feeds/'+identifier
                self.send(201,json.dumps({'url':control+'.ics','control':control}).encode())
            def do_PUT(self):
                suffix=self.address();identifier=suffix[1:] if suffix and suffix.startswith('/') else None
                try:values=self.payload()
                except (ValueError,KeyError):self.send(400);return
                with fixture.lock:
                    record=fixture.feeds.get(identifier)
                    if record is None:self.send(404);return
                    if 'body'in values:record['body']=values['body']
                    if 'status'in values:record['status']=values['status']
                    if values.get('blocked'):record['gate'].clear()
                    elif 'blocked'in values:record['gate'].set()
                self.send(200,b'{}')
            def do_GET(self):
                suffix=self.address()
                if not suffix or not suffix.startswith('/'):self.send(404);return
                download=suffix.endswith('.ics');identifier=suffix[1:-4] if download else suffix[1:]
                with fixture.lock:
                    record=fixture.feeds.get(identifier)
                    if record is None:self.send(404);return
                    if download:record['requests']+=1;record['headers']=dict(self.headers);gate=record['gate']
                    else:
                        self.send(200,json.dumps({'requests':record['requests'],'headers':record['headers'],'blocked':not record['gate'].is_set()}).encode());return
                gate.wait(25)
                with fixture.lock:body=record['body'].encode();status=record['status']
                etag='"'+hashlib.sha256(body).hexdigest()+'"'
                if status==200 and self.headers.get('If-None-Match')==etag:self.send(304,etag=etag)
                else:self.send(status,body,'text/calendar; charset=utf-8',etag)

        self.server=http.server.ThreadingHTTPServer(('127.0.0.1',0),Handler)
        self.origin=f'http://127.0.0.1:{self.server.server_port}';self.base=self.origin+self.secret
        threading.Thread(target=self.server.serve_forever,daemon=True).start()
        self.environment={'TIMEKEEPER_CALENDAR_TRUSTED_ORIGINS':self.origin,'TIMEKEEPER_WEB_CALENDAR_FIXTURE':self.base}

    def close(self):
        with self.lock:
            for record in self.feeds.values():record['gate'].set()
        self.server.shutdown();self.server.server_close();self.feeds.clear()
