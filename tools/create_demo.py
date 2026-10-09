"""Generate small original demonstration assets; no external media dependencies."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont
import math
import subprocess
import imageio_ffmpeg

ROOT = Path(__file__).resolve().parents[1]
DEST = ROOT / "app/src/main/assets/demo"
DEST.mkdir(parents=True, exist_ok=True)
W, H = 1280, 800

def gradient(top, bottom):
    image = Image.new("RGB", (W, H))
    draw = ImageDraw.Draw(image)
    for y in range(H):
        ratio = y / (H - 1)
        draw.line((0, y, W, y), fill=tuple(round(a + (b-a)*ratio) for a,b in zip(top,bottom)))
    return image

def caption(image, text):
    draw = ImageDraw.Draw(image)
    font = ImageFont.truetype("C:/Windows/Fonts/segoeui.ttf", 22)
    draw.text((46, H-58), "LOCAL TV  /  " + text, font=font, fill=(235,242,242))
    return image

image = gradient((25,56,77), (98,159,154)); d = ImageDraw.Draw(image)
d.ellipse((850,125,1010,285), fill=(237,222,166))
d.polygon([(0,505),(250,232),(480,480),(690,195),(995,492),(1170,315),(1280,450),(1280,800),(0,800)], fill=(32,78,87))
d.polygon([(0,610),(270,444),(520,590),(820,385),(1280,585),(1280,800),(0,800)], fill=(21,58,63))
for y in range(605, H):
    ratio=(y-605)/(H-605)
    color=tuple(round(a+(b-a)*ratio) for a,b in zip((82,144,142),(24,59,68)))
    d.line((0,y,W,y), fill=color)
for j in range(35):
    y=615+j*5; x=890-j*5; d.line((x,y,x+110+j*10,y), fill=(120,177,166),width=2)
caption(image,"MOUNTAIN LAKE").save(DEST/"01_Mountain.jpg",quality=90)

image = gradient((89,62,96),(234,145,118)); d=ImageDraw.Draw(image)
d.ellipse((475,255,755,535),fill=(254,205,151))
d.polygon([(0,630),(190,430),(420,650),(765,472),(1000,600),(1280,430),(1280,800),(0,800)],fill=(90,69,91))
d.polygon([(0,720),(260,590),(660,740),(1070,620),(1280,700),(1280,800),(0,800)],fill=(47,50,74))
caption(image,"AFTER THE SUNSET").save(DEST/"02_Sunset.png",optimize=True)

image = gradient((14,72,95),(87,181,177));d=ImageDraw.Draw(image)
for j in range(12):
    points=[(x, 400+j*35+math.sin(x/130+j*.7)*24) for x in range(-10,W+20,10)]
    d.line(points,fill=(125+j*7,195+j*3,195+j*3),width=5)
d.polygon([(0,650),(280,625),(600,690),(920,740),(1280,700),(1280,800),(0,800)],fill=(213,191,154))
caption(image,"A QUIET COAST").save(DEST/"03_Coast.webp",quality=88)

frames=[]
for frame in range(24):
    im=gradient((10,17,37),(35,47,70));dr=ImageDraw.Draw(im)
    for j in range(65):
        x=(j*197)%W;y=(j*131)%(H-150)
        v=round(135+110*(.5+.5*math.sin(frame*.3+j)))
        dr.ellipse((x,y,x+3+(j%2),y+3+(j%2)),fill=(v,v,min(255,v+20)))
    x=180+frame*33;y=210+frame*9
    dr.line((x-120,y-40,x,y),fill=(112,197,216),width=3)
    dr.ellipse((x-4,y-4,x+4,y+4),fill=(237,255,250))
    dr.polygon([(0,720),(300,495),(550,685),(860,440),(1100,660),(1280,560),(1280,800),(0,800)],fill=(13,29,38))
    frames.append(caption(im,"ANIMATED NIGHT SKY").resize((640,400)))
frames[0].save(DEST/"04_Stars.gif",save_all=True,append_images=frames[1:],duration=90,loop=0,optimize=True)

ffmpeg=imageio_ffmpeg.get_ffmpeg_exe()
subprocess.run([ffmpeg,"-y","-loop","1","-i",str(DEST/"01_Mountain.jpg"),"-f","lavfi","-i","sine=frequency=220:sample_rate=44100", "-vf", "scale=1280:800,zoompan=z='min(zoom+0.0002,1.12)':d=600:s=1280x800:fps=30", "-t","20","-c:v","libx264","-preset","fast","-crf","23","-pix_fmt","yuv420p","-c:a","aac","-b:a","64k","-af","volume=0.08","-movflags","+faststart",str(DEST/"05_Motion.mp4")],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
print("Generated",len(list(DEST.iterdir())),"demo media files",sum(f.stat().st_size for f in DEST.iterdir()),"bytes")
