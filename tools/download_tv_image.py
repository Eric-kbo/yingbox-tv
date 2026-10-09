"""Fetch official TV emulator image when sdkmanager's downloader stalls."""
import requests
import time
import zipfile
import concurrent.futures
import re
from pathlib import Path
dest = Path(r"D:/devtools/downloads/localtv-tv36.zip")
url = "https://dl.google.com/android/repository/sys-img/android-tv/x86_64-36_r04.zip"
start = time.time()
headers={"Accept-Encoding":"identity", "Range":"bytes=0-0"}
response=requests.get(url,headers=headers,timeout=30)
response.raise_for_status()
length=int(response.headers["Content-Range"].split("/")[-1])
chunk_size=4*1024*1024
chunks=dest.parent/"localtv-tv-chunks"
chunks.mkdir(exist_ok=True)
def fetch(index):
    lo=index*chunk_size;hi=min(length-1,lo+chunk_size-1)
    chunk=chunks/f"chunk-{index:04d}"
    if chunk.exists() and chunk.stat().st_size==hi-lo+1:return index,hi-lo+1
    for attempt in range(4):
        try:
            r=requests.get(url,headers={"Accept-Encoding":"identity","Range":f"bytes={lo}-{hi}"},timeout=(20,90))
            r.raise_for_status()
            if r.status_code!=206 or r.headers.get("Content-Range")!=f"bytes {lo}-{hi}/{length}" or len(r.content)!=hi-lo+1:
                raise RuntimeError("Incorrect range response")
            chunk.write_bytes(r.content)
            return index,len(r.content)
        except Exception:
            if attempt==3:raise
            time.sleep(1)
count=0;last=time.time()
with concurrent.futures.ThreadPoolExecutor(max_workers=24) as executor:
    futures=[executor.submit(fetch,i) for i in range((length+chunk_size-1)//chunk_size)]
    for future in concurrent.futures.as_completed(futures):
        _,size=future.result();count+=size
        if time.time()-last>10:
            print(f"Downloaded {count/1024/1024:.0f}/{length/1024/1024:.0f} MB",flush=True);last=time.time()
with dest.open("wb") as output:
    for i in range((length+chunk_size-1)//chunk_size):output.write((chunks/f"chunk-{i:04d}").read_bytes())
print(f"Download completed in {time.time()-start:.0f} seconds", flush=True)
with zipfile.ZipFile(dest) as archive:
    if archive.testzip() is not None:
        raise RuntimeError("Corrupt image archive")
    archive.extractall(r"D:/devtools/android-sdk/system-images/android-36/android-tv")
print("Extracted official TV image", flush=True)
