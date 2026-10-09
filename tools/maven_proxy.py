"""Optional loopback Maven download adapter for slow Java HTTPS connections."""
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from pathlib import Path
from urllib.parse import urlsplit
import requests
import threading
import hashlib
import glob

CACHE = Path(r"D:/devtools/downloads/localtv-maven-cache")
CACHE.mkdir(parents=True, exist_ok=True)
LOCAL = threading.local()
BASES = {"google": "https://dl.google.com/dl/android/maven2/", "central": "https://repo.maven.apache.org/maven2/", "plugins": "https://plugins.gradle.org/m2/"}
class Handler(BaseHTTPRequestHandler):
    def log_message(self, message, *args): pass
    def do_HEAD(self): self.fetch(head=True)
    def do_GET(self): self.fetch(head=False)
    def fetch(self, head):
        path=urlsplit(self.path).path.lstrip("/")
        prefix,_,suffix=path.partition("/")
        if prefix not in BASES or ".." in suffix.split("/"):
            self.send_error(400);return
        url=BASES[prefix]+suffix
        cache=CACHE/hashlib.sha256(url.encode()).hexdigest()
        try:
            if not cache.exists():
                pieces=suffix.split("/")
                if len(pieces)>=4:
                    group=".".join(pieces[:-3]); artifact,version,filename=pieces[-3:]
                    checksum=filename.endswith((".sha1",".sha256",".md5"))
                    original=filename.rsplit(".",1)[0] if checksum else filename
                    existing=list(Path(r"D:/devtools/gradle-cache/caches/modules-2/files-2.1").joinpath(group,artifact,version).glob("*/"+original))
                    if existing:
                        data=existing[0].read_bytes()
                        if checksum:data=hashlib.new(filename.rsplit(".",1)[1],data).hexdigest().encode()
                        cache.write_bytes(data)
                if cache.exists():
                    self.send_response(200);self.send_header("Content-Length",str(cache.stat().st_size));self.end_headers()
                    if not head:self.wfile.write(cache.read_bytes())
                    return
                if prefix=="google" and not suffix.startswith(("androidx/","com/android/","com/google/android/")):
                    self.send_error(404);return
                if not hasattr(LOCAL,"session"):LOCAL.session=requests.Session()
                response=LOCAL.session.get(url,headers={"Accept-Encoding":"identity"},timeout=(15,45))
                if response.status_code!=200:
                    self.send_error(response.status_code);return
                temp=cache.with_suffix(".tmp-"+str(threading.get_ident()))
                temp.write_bytes(response.content);temp.replace(cache)
                print("Cached",suffix,cache.stat().st_size,flush=True)
            self.send_response(200);self.send_header("Content-Length",str(cache.stat().st_size));self.end_headers()
            if not head:self.wfile.write(cache.read_bytes())
        except Exception as exc:
            print("Download error",suffix,type(exc).__name__,flush=True)
            self.send_error(502)
if __name__=="__main__":
    print("Maven adapter on 127.0.0.1:8766",flush=True)
    ThreadingHTTPServer(("127.0.0.1",8766),Handler).serve_forever()
