"""Create representative video containers/codecs for local playback verification."""
from pathlib import Path
import subprocess
import imageio_ffmpeg
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
DEST = ROOT / "tools/test-data/Album/Formats"
DEST.mkdir(parents=True, exist_ok=True)
source = ROOT / "app/src/main/assets/demo/05_Motion.mp4"
ffmpeg = imageio_ffmpeg.get_ffmpeg_exe()
for name, options in [
    ("01_H264.mkv", ["-c", "copy"]),
    ("02_H264.mov", ["-c", "copy"]),
    ("03_MPEG4.avi", ["-c:v", "mpeg4", "-q:v", "5", "-c:a", "libmp3lame"]),
    ("04_VP9.webm", ["-c:v", "libvpx-vp9", "-deadline", "realtime", "-cpu-used", "8", "-b:v", "300k", "-c:a", "libopus"]),
]:
    subprocess.run([ffmpeg, "-y", "-i", str(source), "-t", "12", *options, str(DEST / name)], check=True,
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
Image.open(ROOT / "app/src/main/assets/demo/01_Mountain.jpg").save(DEST / "05_Bitmap.bmp")
print("Prepared MKV, MOV, AVI, WebM and BMP fixtures")
