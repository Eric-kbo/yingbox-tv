"""Preserve upstream license/notice resources from the shipped dependency jars."""
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[1]
DEST = ROOT / "app/src/main/assets/licenses"
CACHE = Path("D:/devtools/gradle-cache/caches/modules-2/files-2.1")
for group, artifact, version in [
    ("org.slf4j", "slf4j-api", "2.0.9"),
    ("org.bouncycastle", "bcprov-jdk18on", "1.75"),
    ("net.engio", "mbassador", "1.3.0"),
    ("com.hierynomus", "smbj", "0.13.0"),
    ("com.hierynomus", "asn-one", "0.6.0"),
]:
    folder = CACHE / group / artifact / version
    jars = list(folder.glob(f"*/{artifact}-{version}.jar"))
    if not jars:
        print("Missing jar:", artifact)
        continue
    pieces = []
    with zipfile.ZipFile(jars[0]) as jar:
        for entry in jar.namelist():
            basename = entry.rsplit("/", 1)[-1].lower()
            if basename.startswith(("license", "licence", "notice", "copyright")) and not entry.endswith("/"):
                pieces.append(entry + "\n\n" + jar.read(entry).decode("utf-8", errors="replace"))
    if pieces:
        (DEST / f"{artifact}-NOTICES.txt").write_text("\n\n".join(pieces), encoding="utf-8")
        print("Preserved", artifact)
    else:
        print("No embedded notices:", artifact)
glide = DEST / "MIT.txt"
if glide.exists():
    glide.rename(DEST / "Glide-LICENSE.txt")
notice = DEST / "THIRD-PARTY.txt"
notice.write_text(notice.read_text(encoding="utf-8").replace("See MIT.txt", "See Glide-LICENSE.txt"), encoding="utf-8")
