"""Range-download a large official build artifact to the optional Maven adapter cache."""
from pathlib import Path
import requests
import concurrent.futures
import hashlib
import sys
import time
url=sys.argv[1]
if not url.startswith(("https://dl.google.com/dl/android/maven2/", "https://repo.maven.apache.org/maven2/")):
    raise SystemExit("Only official build repositories are supported")
cache=Path(r"D:/devtools/downloads/localtv-maven-cache")/hashlib.sha256(url.encode()).hexdigest()
r=requests.get(url,headers={"Range":"bytes=0-0","Accept-Encoding":"identity"},timeout=30)
r.raise_for_status()
if r.status_code!=206:
    cache.write_bytes(r.content);print("Cached",len(r.content));raise SystemExit()
size=int(r.headers["Content-Range"].split("/")[-1]);chunk=2*1024*1024
print("Fetching",size,"bytes",flush=True)
def fetch(index):
    lo=index*chunk;hi=min(size-1,lo+chunk-1)
    for attempt in range(3):
        try:
            response=requests.get(url,headers={"Accept-Encoding":"identity","Range":f"bytes={lo}-{hi}"},timeout=45)
            response.raise_for_status()
            if response.status_code!=206 or len(response.content)!=hi-lo+1:raise ValueError("Invalid byte range")
            return index,response.content
        except Exception:
            if attempt==2:raise
            time.sleep(1)
with concurrent.futures.ThreadPoolExecutor(max_workers=12) as pool:
    chunks=dict(pool.map(fetch,range((size+chunk-1)//chunk)))
data=b"".join(chunks[i] for i in sorted(chunks))
checksum=requests.get(url+".sha1",timeout=20)
if checksum.status_code==200:
    assert hashlib.sha1(data).hexdigest()==checksum.text.strip().split()[0],"Checksum mismatch"
cache.write_bytes(data)
print("Cached artifact",len(data),"bytes",flush=True)
