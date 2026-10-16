import subprocess,os,pathlib,time,re,json,threading,http.server,concurrent.futures,tempfile
root=pathlib.Path(__file__).resolve().parents[1]
tmp=tempfile.TemporaryDirectory(prefix='rust-wss-test-'); w=pathlib.Path(tmp.name)
bins=pathlib.Path(os.environ.get('CARGO_TARGET_DIR',str(root/'target'))).resolve()/'debug'; procs=[]
image=os.environ.get('TUNNEL_TEST_IMAGE'); network='rust-wss-net-'+str(os.getpid()); server_name='rust-wss-server-'+str(os.getpid())
(w/'token').write_text('smoke-'+('x'*48));(w/'bad-token').write_text('wrong-'+('z'*48))
def openssl(*args):
 subprocess.run(['openssl',*args],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
openssl('req','-x509','-newkey','rsa:2048','-nodes','-keyout',str(w/'ca-key.pem'),'-out',str(w/'ca.pem'),'-days','1','-subj','/CN=TunnelTestCA','-addext','basicConstraints=critical,CA:TRUE')
openssl('req','-new','-newkey','rsa:2048','-nodes','-keyout',str(w/'key.pem'),'-out',str(w/'leaf.csr'),'-subj','/CN=localhost')
(w/'leaf.ext').write_text('subjectAltName=DNS:localhost\nbasicConstraints=critical,CA:FALSE\nextendedKeyUsage=serverAuth\n')
openssl('x509','-req','-in',str(w/'leaf.csr'),'-CA',str(w/'ca.pem'),'-CAkey',str(w/'ca-key.pem'),'-CAcreateserial','-out',str(w/'cert.pem'),'-days','1','-extfile',str(w/'leaf.ext'))
(w/'dynamic.yml').write_text('''http:
  routers:
    all:
      rule: Host(`localhost`) || HostRegexp(`^[a-z0-9-]+\\.tunnel\\.test$`)
      service: tunnel
      tls: {}
  services:
    tunnel:
      loadBalancer:
        servers:
          - url: http://host.docker.internal:18000
tls:
  certificates:
    - certFile: /verify/cert.pem
      keyFile: /verify/key.pem
''')
if image:
 text=(w/'dynamic.yml').read_text().replace('host.docker.internal:18000',server_name+':8080')
 (w/'dynamic.yml').write_text(text)
class Handler(http.server.BaseHTTPRequestHandler):
 def log_message(self,*a):pass
 def do_GET(self):self.do_POST()
 def do_POST(self):
  body=self.rfile.read(int(self.headers.get('Content-Length',0)))
  if self.path=='/redirect':
   self.send_response(302);self.send_header('Location','http://127.0.0.1:1/');self.end_headers();return
  self.send_response(201 if self.command=='POST' else 200)
  self.send_header('Set-Cookie','a=1');self.send_header('Set-Cookie','b=2')
  self.end_headers()
  if self.path=='/binary':self.wfile.write(body)
  else:self.wfile.write(json.dumps({'method':self.command,'path':self.path,'body':body.decode(),'port':self.server.server_port}).encode())
servers=[]
for port in [18300,18301]:
 s=http.server.ThreadingHTTPServer(('127.0.0.1',port),Handler);servers.append(s);threading.Thread(target=s.serve_forever,daemon=True).start()
def start(args,log):
 f=open(w/log,'w');p=subprocess.Popen(args,stdout=f,stderr=subprocess.STDOUT);procs.append((p,f));return p
base=[str(bins/'tunnel-client'),'18300','--server','wss://localhost:18443','--ca-cert',str(w/'ca.pem'),'--token-file',str(w/'token')]
def client(name=None,port=18300,log='client.log'):
 args=base.copy();args[1]=str(port)
 if name:args+=['--name',name]
 p=start(args,log)
 for _ in range(100):
  text=(w/log).read_text();m=re.search(r'https://([a-z0-9-]+\.tunnel\.test)',text)
  if m:return p,m.group(1)
  if p.poll() is not None:raise RuntimeError(text)
  time.sleep(.1)
 raise RuntimeError('URL timeout')
def curl(host,path='/',data=None):
 args=['curl','--silent','--show-error','--max-time','15','--cacert',str(w/'ca.pem'),'https://localhost:18443'+path,'-H','Host: '+host,'-D',str(w/('headers-'+threading.current_thread().name)),'-w','\n%{http_code}']
 if data is not None:args+=['--data-binary','@-']
 out=subprocess.check_output(args,input=data)
 body,code=out.rsplit(b'\n',1)
 headers=(w/('headers-'+threading.current_thread().name)).read_text()
 return int(code),body,headers
try:
 if image:
  (w/'token').chmod(0o444)
  subprocess.run(['docker','network','create',network],check=True,stdout=subprocess.DEVNULL)
  subprocess.run(['docker','run','--rm','-d','--name',server_name,'--network',network,'--read-only','--cap-drop=ALL','--security-opt=no-new-privileges:true','-v',str(w/'token')+':/run/secrets/token:ro',image,'--domain','tunnel.test','--connect-domain','localhost','--token-file','/run/secrets/token'],check=True,stdout=subprocess.DEVNULL)
 else:
  start([str(bins/'tunnel-server'),'--domain','tunnel.test','--connect-domain','localhost','--bind','0.0.0.0:18000','--token-file',str(w/'token')],'server.log')
 subprocess.run(['docker','run','--rm','-d','--name','rust-wss-test-'+str(os.getpid()),*(['--network',network] if image else []),'-p','127.0.0.1:18443:443','-v',str(w)+':/verify:ro','traefik:v3.7.14','--entrypoints.websecure.address=:443','--providers.file.filename=/verify/dynamic.yml'],check=True,stdout=subprocess.DEVNULL)
 time.sleep(1)
 p,host=client();assert re.fullmatch('[a-f0-9]{16}.tunnel.test',host);print('random subdomain PASS')
 named,hn=client('demo',18301,'named.log');assert hn=='demo.tunnel.test';print('specified subdomain PASS')
 duplicate=subprocess.run(base+['--name','demo'],capture_output=True,timeout=10);assert duplicate.returncode and b'409' in duplicate.stderr;print('name conflict HTTP 409 PASS')
 wrong=base.copy();wrong[-1]=str(w/'bad-token');res=subprocess.run(wrong,capture_output=True,timeout=10);assert res.returncode and b'401' in res.stderr;print('invalid token HTTP 401 PASS')
 res=subprocess.run(base+['--name','INVALID'],capture_output=True,timeout=10);assert res.returncode;print('invalid name PASS')
 def check(i):
  h=host if i%2==0 else hn;port=18300 if i%2==0 else 18301
  code,body,headers=curl(h,'/query?i='+str(i));assert code==200;assert json.loads(body)['port']==port
  assert headers.lower().count('set-cookie:')==2
 with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:list(pool.map(check,range(16)))
 print('parallel routing isolation + duplicate cookies PASS')
 data=bytes(range(256))*32;code,body,_=curl(host,'/binary',data);assert code==201 and body==data;print('binary POST and status PASS')
 assert curl(host,'/redirect')[0]==302;print('redirect preserved PASS')
 assert curl(host,'/large',b'x'*(5*1024*1024+1))[0]==413;print('body limit PASS')
 named.terminate();named.wait(timeout=5)
 for _ in range(30):
  if curl(hn)[0]==404:break
  time.sleep(.1)
 else:raise AssertionError('name not released')
 client('demo',18301,'reused.log');print('disconnect cleanup + name reuse PASS')
 servers[0].shutdown();servers[0].server_close();assert curl(host)[0]==502;print('local service unavailable HTTP 502 PASS')
 print('All tests use WSS/HTTPS through Traefik with certificate validation enabled.')
finally:
 for p,f in reversed(procs):
  if p.poll() is None:p.terminate()
  try:p.wait(timeout=3)
  except subprocess.TimeoutExpired:p.kill();p.wait()
  f.close()
 for s in servers:s.shutdown();s.server_close()
 subprocess.run(['docker','rm','-f','rust-wss-test-'+str(os.getpid())],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)

 if image:
  subprocess.run(['docker','rm','-f',server_name],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
  subprocess.run(['docker','network','rm',network],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
 tmp.cleanup()
