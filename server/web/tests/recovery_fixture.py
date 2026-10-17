"""Loopback-only HTTPS/SMTP fixtures; no production security settings are relaxed."""
from email import policy
from email.parser import BytesParser
import http.client
import http.server
import json
import re
import secrets
import socketserver
import ssl
import subprocess
import threading


class RecoveryFixture:
    def __init__(self, directory, backend, *, https=False, mail=False, hostname='127.0.0.1'):
        if hostname not in ('127.0.0.1','localhost'):raise ValueError('Fixture must stay on loopback')
        self.servers=[];self.environment={};self.messages=[];self.events=[];self.lock=threading.Lock()
        self.base=f'http://127.0.0.1:{backend}'
        fixture=self
        if https:
            class Proxy(http.server.BaseHTTPRequestHandler):
                protocol_version='HTTP/1.1'
                def log_message(self,*args):pass
                def handle(self):
                    try:super().handle()
                    except (BrokenPipeError,ConnectionResetError,TimeoutError):pass
                    except ssl.SSLError as failure:
                        if failure.reason not in ('SSLV3_ALERT_CERTIFICATE_UNKNOWN','TLSV1_ALERT_UNKNOWN_CA'):raise
                def relay(self):
                    body=self.rfile.read(int(self.headers.get('Content-Length','0')))
                    headers={key:value for key,value in self.headers.items() if key.lower() not in ('connection','content-length','transfer-encoding')}
                    connection=http.client.HTTPConnection('127.0.0.1',backend,timeout=35)
                    try:
                        connection.request(self.command,self.path,body=body,headers=headers)
                        response=connection.getresponse();data=response.read()
                        if self.path.startswith('/api/v1/auth/'):
                            with fixture.lock:fixture.events.append({'method':self.command,'path':self.path.split('?',1)[0],'status':response.status})
                        self.send_response(response.status)
                        for key,value in response.getheaders():
                            if key.lower() not in ('connection','content-length','transfer-encoding'):self.send_header(key,value)
                        # A 304 describes the cached representation; advertising
                        # its empty response body as length zero corrupts module
                        # revalidation in browsers. 204/HEAD have no body either.
                        if response.status not in (204,304) and self.command!='HEAD':self.send_header('Content-Length',str(len(data)))
                        self.end_headers();self.wfile.write(data)
                    except (BrokenPipeError,ConnectionResetError):pass
                    finally:connection.close()
                do_GET=do_POST=do_PUT=do_DELETE=do_OPTIONS=do_HEAD=relay
            class ProxyServer(http.server.ThreadingHTTPServer):request_queue_size=128
            proxy=ProxyServer(('127.0.0.1',0),Proxy)
            key=directory/'fixture-key.pem';certificate=directory/'fixture-cert.pem'
            subprocess.run(['openssl','req','-x509','-newkey','rsa:2048','-nodes','-keyout',str(key),'-out',str(certificate),'-days','1','-subj','/CN=localhost','-addext','subjectAltName=IP:127.0.0.1,DNS:localhost'],check=True,capture_output=True)
            tls=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER);tls.load_cert_chain(str(certificate),str(key))
            # Let request workers perform TLS handshakes. A browser's idle
            # preconnection must not block accept() for every other module.
            proxy.socket=tls.wrap_socket(proxy.socket,server_side=True,do_handshake_on_connect=False)
            self.base=f'https://{hostname}:{proxy.server_port}';self.start(proxy)
        if mail:
            class SMTP(socketserver.StreamRequestHandler):
                def handle(self):
                    self.connection.settimeout(20);self.wfile.write(b'220 loopback fixture\r\n');recipients=[]
                    while line:=self.rfile.readline(8192):
                        verb=line.split(b' ',1)[0].strip().upper()
                        if verb in (b'EHLO',b'HELO'):self.wfile.write(b'250-loopback fixture\r\n250 SIZE 1048576\r\n')
                        elif verb==b'MAIL':recipients=[];self.wfile.write(b'250 OK\r\n')
                        elif verb==b'RCPT':
                            match=re.search(rb'<([^>]+)>',line)
                            if not match:self.wfile.write(b'501 Invalid recipient\r\n');continue
                            recipients.append(match[1].decode('ascii'));self.wfile.write(b'250 OK\r\n')
                        elif verb==b'DATA':
                            self.wfile.write(b'354 End with dot\r\n');data=bytearray()
                            while part:=self.rfile.readline(1048577):
                                if part==b'.\r\n':break
                                data.extend(part[1:] if part.startswith(b'..') else part)
                                if len(data)>1048576:return
                            message=BytesParser(policy=policy.default).parsebytes(data)
                            text='\n'.join(str(part.get_content()) for part in message.walk() if part.get_content_type()=='text/plain')
                            # Keep only what browser assertions need, exclusively in memory.
                            with fixture.lock:
                                fixture.messages.append({'recipients':list(recipients),'subject':str(message['Subject']),
                                    'links':re.findall(r'https://[^\s<>]+/web/#(?:verify-email|reset-password)/[A-Za-z0-9_-]{43}',text)})
                            self.wfile.write(b'250 Queued\r\n')
                        elif verb==b'QUIT':self.wfile.write(b'221 Bye\r\n');return
                        else:self.wfile.write(b'250 OK\r\n')
            class SMTPServer(socketserver.ThreadingTCPServer):daemon_threads=True
            smtp=SMTPServer(('127.0.0.1',0),SMTP);self.start(smtp)
            secret_path='/'+secrets.token_urlsafe(32)
            class Captures(http.server.BaseHTTPRequestHandler):
                def log_message(self,*args):pass
                def do_GET(self):
                    if self.path!=secret_path:self.send_error(404);return
                    with fixture.lock:data=json.dumps(fixture.messages).encode()
                    self.send_response(200);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(data)));self.end_headers();self.wfile.write(data)
            captures=http.server.ThreadingHTTPServer(('127.0.0.1',0),Captures);self.start(captures)
            self.environment.update(TIMEKEEPER_MAIL_ENABLED='true',TIMEKEEPER_SMTP_HOST='127.0.0.1',TIMEKEEPER_SMTP_PORT=str(smtp.server_address[1]),
                TIMEKEEPER_SMTP_FROM='Timekeeper <fixture@example.invalid>',TIMEKEEPER_SMTP_USERNAME='',TIMEKEEPER_SMTP_PASSWORD='',TIMEKEEPER_SMTP_TLS='false',TIMEKEEPER_SMTP_START_TLS='DISABLED',
                TIMEKEEPER_WEB_MAIL_FIXTURE=f'http://127.0.0.1:{captures.server_port}{secret_path}')

    def start(self,server):
        self.servers.append(server);threading.Thread(target=server.serve_forever,daemon=True).start()

    def close(self):
        for server in reversed(self.servers):server.shutdown();server.server_close()
        self.messages.clear()
