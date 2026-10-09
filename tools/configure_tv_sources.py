"""Operate the TV app over ADB; credentials enter through stdin, never log them."""
from pathlib import Path
import json
import re
import shlex
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

BASE = ["adb", "-s", "192.168.100.240:5555"]
ROOT = Path(__file__).resolve().parents[1]

def adb(*args):
    result = subprocess.run(BASE + list(args), capture_output=True, timeout=30)
    if result.returncode:
        raise RuntimeError("ADB operation failed; credential values omitted")
    return result.stdout

def state():
    adb("shell", "uiautomator dump /data/local/tmp/localtv-setup-ui.xml")
    return ET.fromstring(adb("shell", "cat /data/local/tmp/localtv-setup-ui.xml"))

def key(*codes):
    for code in codes:
        adb("shell", "input", "keyevent", str(code))
        time.sleep(.3)

def snapshot(name):
    root = state()
    (ROOT / "dist" / (name + ".png")).write_bytes(adb("exec-out", "screencap", "-p"))
    for node in root.iter("node"):
        if node.get("password") == "true":
            print("Password field populated:", bool(node.get("text")), "focused:", node.get("focused"))
        elif node.get("text") or node.get("focused") == "true":
            print({key: node.get(key) for key in ("text", "class", "focused", "bounds")})
    return root

def field(index, text):
    root = state()
    fields = [n for n in root.iter("node") if n.get("class") == "android.widget.EditText" and n.get("package") == "com.localtv.viewer"]
    box = list(map(int, re.findall(r"\d+", fields[index].get("bounds"))))
    x, y = (box[0] + box[2]) // 2, (box[1] + box[3]) // 2
    adb("shell", "input", "tap", str(x), str(y))
    time.sleep(.6)
    adb("shell", "input text " + shlex.quote(text))
    key(4)
    time.sleep(.3)

action = sys.argv[1]
if action == "snapshot":
    snapshot(sys.argv[2])
elif action == "keys":
    key(*sys.argv[2:])
    snapshot("tv-current")
elif action == "fill":
    credentials = json.load(sys.stdin)
    field(0, sys.argv[2])
    field(1, sys.argv[3])
    field(2, credentials["User"])
    field(3, credentials["Password"])
    del credentials
    snapshot("tv-source-filled")
