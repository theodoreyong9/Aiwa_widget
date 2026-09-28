#!/usr/bin/env python3
import json,subprocess,threading
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
HOST="127.0.0.1";PORT=8787;SESSION="8aab65ab-182b-40ab-b163-30338c87d2f5";events=[];lock=threading.Lock()
def emit(x):
 with lock: events.append(x)
def run_claude(text):
 p=subprocess.Popen(["claude","-p","--resume",SESSION,"--input-format","stream-json","--output-format","stream-json","--include-partial-messages","--verbose"],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,bufsize=1)
 p.stdin.write(json.dumps({"type":"user","message":{"role":"user","content":[{"type":"text","text":text}]}})+"\n");p.stdin.close()
 for line in p.stdout:
  try:o=json.loads(line)
  except:continue
  if o.get("type")=="stream_event":
   d=o.get("event",{}).get("delta",{})
   if d.get("type")=="text_delta":emit({"type":"text","text":d.get("text","")})
  elif o.get("type")=="result":emit({"type":"done","result":o.get("result","")})
 p.wait()
class H(BaseHTTPRequestHandler):
 def j(self,x):
  b=json.dumps(x).encode();self.send_response(200);self.send_header("Content-Type","application/json");self.send_header("Content-Length",str(len(b)));self.end_headers();self.wfile.write(b)
 def do_GET(self):
  if self.path=="/api/events":
   with lock:d=list(events);events.clear()
   self.j(d)
  elif self.path=="/api/status":self.j({"session":SESSION,"status":"ready"})
  elif self.path=="/api/sessions":self.j([SESSION])
  else:self.send_error(404)
 def do_POST(self):
  n=int(self.headers.get("Content-Length","0"));body=self.rfile.read(n).decode()
  if self.path=="/api/message":threading.Thread(target=run_claude,args=(body,),daemon=True).start();self.j({"accepted":True})
  elif self.path=="/api/background":self.j({"accepted":False,"reason":"not wired yet"})
  else:self.send_error(404)
if __name__=="__main__":ThreadingHTTPServer((HOST,PORT),H).serve_forever()
