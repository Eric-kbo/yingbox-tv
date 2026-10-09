"""Submit to the TV's visible pairing form. Credentials come only from stdin."""
import json
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
import requests

serial, name, address = sys.argv[1:4]
credential = json.load(sys.stdin)
base = ["adb", "-s", serial]
dump = subprocess.run(base + ["shell", "uiautomator", "dump", "/data/local/tmp/localtv-pair-ui.xml"], check=True, capture_output=True, stdin=subprocess.DEVNULL)
if b"dumped" not in dump.stdout:
    raise SystemExit("TV UI is unavailable; pairing was not attempted")
xml = subprocess.check_output(base + ["shell", "cat", "/data/local/tmp/localtv-pair-ui.xml"], stdin=subprocess.DEVNULL)
root = ET.fromstring(xml)
urls = [re.search(r"http://[^\s]+", node.get("text", "")) for node in root.iter("node") if node.get("text", "").startswith("也可在浏览器输入：")]
if not urls or not urls[0]:
    raise SystemExit("Open the TV's phone-pairing page first")
url = urls[0].group()
payload = {"kind": "SMB", "address": address, "name": name, "username": credential["User"], "password": credential["Password"]}
response = requests.post(url, data=payload, timeout=70)
del payload, credential
print("TV pairing status:", response.status_code)
print(response.text[:250])
if response.status_code != 200:
    raise SystemExit(1)
