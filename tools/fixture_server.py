"""Local authenticated WebDAV fixture with byte ranges for APK integration tests."""
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from pathlib import Path
from urllib.parse import unquote, quote
import base64
import shutil
import xml.sax.saxutils

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "tools/test-data"
DATA.mkdir(exist_ok=True)
(DATA / "Album").mkdir(exist_ok=True)
for file in (ROOT / "app/src/main/assets/demo").iterdir():
    shutil.copyfile(file, DATA / file.name)
shutil.copyfile(DATA / "01_Mountain.jpg", DATA / "Album/家庭 + 1.jpg")
(DATA / "notes.txt").write_text("Unsupported file should not appear.", encoding="utf-8")
AUTH = "Basic " + base64.b64encode(b"viewer:localtv-test").decode()

class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, message, *args):
        print(message % args, flush=True)

    def authenticated(self):
        if self.headers.get("Authorization") != AUTH:
            self.send_response(401)
            self.send_header("WWW-Authenticate", 'Basic realm="LocalTV"')
            self.send_header("Content-Length", "0")
            self.end_headers()
            return False
        return True

    def resolve_path(self):
        route = unquote(self.path.split("?",1)[0])
        if not route.startswith("/dav/"):
            return None
        file = (DATA / route.removeprefix("/dav/")).resolve()
        return file if file.is_relative_to(DATA.resolve()) else None

    def do_PROPFIND(self):
        self.rfile.read(int(self.headers.get("Content-Length", "0")))
        if not self.authenticated(): return
        folder = self.resolve_path()
        if folder is None or not folder.is_dir():
            self.send_error(404); return
        entries = [folder] + sorted(folder.iterdir())
        records=[]
        for item in entries:
            path=item.relative_to(DATA).as_posix()
            if path==".": path=""
            href="/dav/"+quote(path,safe="/")+("/" if item.is_dir() and path else "")
            mime = "image/jpeg" if item.suffix.lower()==".jpg" else "application/octet-stream"
            records.append(f'<d:response><d:href>{xml.sax.saxutils.escape(href)}</d:href><d:propstat><d:prop><d:resourcetype>{"<d:collection/>" if item.is_dir() else ""}</d:resourcetype><d:getcontenttype>{mime}</d:getcontenttype><d:getcontentlength>{item.stat().st_size if item.is_file() else 0}</d:getcontentlength><d:getlastmodified>Fri, 09 Oct 2026 00:00:00 GMT</d:getlastmodified></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>')
        body=('<d:multistatus xmlns:d="DAV:">'+"".join(records)+'</d:multistatus>').encode()
        self.send_response(207)
        self.send_header("Content-Type", "application/xml; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers();self.wfile.write(body)

    def do_HEAD(self): self.file(head=True)
    def do_GET(self): self.file(head=False)
    def file(self,head):
        if not self.authenticated():return
        file=self.resolve_path()
        if file is None or not file.is_file():self.send_error(404);return
        size=file.stat().st_size; start=0;end=size-1
        header=self.headers.get("Range")
        if header:
            try:
                left,right=header.removeprefix("bytes=").split("-")
                if left: start=int(left);end=min(int(right) if right else size-1,size-1)
                else:start=max(0,size-int(right))
                if start<0 or start>=size or end<start:raise ValueError()
            except ValueError:
                self.send_response(416);self.send_header("Content-Range",f"bytes */{size}");self.send_header("Content-Length","0");self.end_headers();return
        self.send_response(206 if header else 200)
        self.send_header("Content-Length",str(end-start+1));self.send_header("Accept-Ranges","bytes")
        if header:self.send_header("Content-Range",f"bytes {start}-{end}/{size}")
        self.end_headers()
        if not head:
            try:
                with file.open("rb") as stream:
                    stream.seek(start);remaining=end-start+1
                    while remaining:
                        chunk=stream.read(min(65536,remaining))
                        self.wfile.write(chunk);remaining-=len(chunk)
            except (ConnectionResetError,BrokenPipeError,ConnectionAbortedError):pass

if __name__=="__main__":
    print("WebDAV test fixture at 127.0.0.1:8765",flush=True)
    ThreadingHTTPServer(("127.0.0.1",8765),Handler).serve_forever()
