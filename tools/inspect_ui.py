"""ADB screenshots and readable Android accessibility state for manual QA."""
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET
import sys
import time
ROOT=Path(__file__).resolve().parents[1]
SERIAL="emulator-5556"
def adb(*args):return subprocess.run(["adb","-s",SERIAL,*args],check=True,capture_output=True).stdout
if len(sys.argv)>2:
    for key in sys.argv[2:]:
        adb("shell","input","keyevent",key);time.sleep(.3)
time.sleep(1)
name=sys.argv[1] if len(sys.argv)>1 else "screen"
image=ROOT/"dist"/(name+".png")
image.write_bytes(adb("exec-out","screencap","-p"))
adb("shell","uiautomator","dump","/sdcard/localtv-ui.xml")
xml=adb("shell","cat","/sdcard/localtv-ui.xml")
(ROOT/"tools"/(name+"-ui.xml")).write_bytes(xml)
for node in ET.fromstring(xml).iter("node"):
    text=node.get("text")
    if text:print(text)
    if node.get("focused")=="true":print("FOCUSED",node.get("bounds"),[n.get("text") for n in node.iter("node") if n.get("text")])
print(image)
