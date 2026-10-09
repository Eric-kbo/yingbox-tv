"""Package a verified APK and reproducible sources, excluding signing secrets."""
from pathlib import Path
import hashlib
import json
import re
import shutil
import zipfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
DEST = ROOT / "dist"
DEST.mkdir(exist_ok=True)
metadata = json.loads((ROOT / "app/build/outputs/apk/release/output-metadata.json").read_text(encoding="utf-8"))
version = metadata["elements"][0]["versionName"]
assert re.fullmatch(r"\d+\.\d+\.\d+", version)
result = (ROOT / "test-final.log").read_text(encoding="utf-8-sig", errors="replace")
passed = re.search(r"OK \((\d+) tests\)", result)
if not passed or "FAILURES!!!" in result:
    raise SystemExit("Final Android TV integration suite must pass before packaging")
unit_count = 0
for report in (ROOT / "app/build/test-results/testDebugUnitTest").glob("TEST-*.xml"):
    unit = ET.parse(report).getroot()
    assert unit.get("failures") == "0" and unit.get("errors") == "0" and unit.get("skipped") == "0"
    unit_count += int(unit.get("tests"))
assert unit_count >= 14
assert "BUILD SUCCESSFUL" in (ROOT / "build-final.log").read_text(encoding="utf-8-sig", errors="replace")
signature = (ROOT / "signature-verify.log").read_text(encoding="utf-8-sig", errors="replace")
assert signature.startswith("Verifies")
apk = DEST / f"YingBoxTV-{version}.apk"
shutil.copyfile(ROOT / "app/build/outputs/apk/release/app-release.apk", apk)
paths = [ROOT / name for name in ["README.md", "RELEASE_NOTES.md", f"QA-{version}.md", ".gitignore", "build.gradle.kts", "settings.gradle.kts", "gradle.properties", "gradlew", "gradlew.bat", "app/build.gradle.kts", "app/proguard-rules.pro"]]
paths += list((ROOT / "gradle/wrapper").glob("*"))
paths += [p for p in (ROOT / "app/src").rglob("*") if p.is_file()]
paths += [p for p in (ROOT / "tools").iterdir() if p.suffix in (".py", ".cs")]
source = DEST / f"YingBoxTV-{version}-source.zip"
with zipfile.ZipFile(source, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
    for path in sorted(paths):
        relative = path.relative_to(ROOT).as_posix()
        assert "signing.properties" not in relative and not relative.endswith(".jks")
        archive.write(path, "LocalTV/" + relative)
with zipfile.ZipFile(source, "r") as archive:
    assert archive.testzip() is None
for path in (apk, source):
    with path.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    (DEST / (path.name + ".sha256")).write_text(digest + "  " + path.name + "\n", encoding="utf-8")
    print(path.name, f"{path.stat().st_size / 1048576:.1f} MiB", digest)
shutil.copyfile(ROOT / "test-final.log", DEST / f"Android-TV-test-results-{version}.txt")
shutil.copyfile(ROOT / "signature-verify.log", DEST / f"APK-signature-{version}.txt")
shutil.copyfile(ROOT / "app/build/reports/lint-results-release.txt", DEST / f"Android-lint-{version}.txt")
instructions = (ROOT / "RELEASE_NOTES.md").read_text(encoding="utf-8")
(DEST / f"交付说明-{version}.txt").write_text(instructions, encoding="utf-8-sig")
shutil.copyfile(ROOT / f"QA-{version}.md", DEST / f"QA-{version}.md")
