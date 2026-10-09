"""Package a verified APK and reproducible sources, excluding signing secrets."""
from pathlib import Path
import hashlib
import shutil
import zipfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
DEST = ROOT / "dist"
DEST.mkdir(exist_ok=True)
result = (ROOT / "test-final.log").read_text(encoding="utf-8-sig", errors="replace")
if "OK (6 tests)" not in result:
    raise SystemExit("Final Android TV integration suite must pass before packaging")
unit = ET.parse(ROOT / "app/build/test-results/testDebugUnitTest/TEST-com.localtv.viewer.data.ProtocolTest.xml").getroot()
assert unit.get("tests") == "6" and unit.get("failures") == "0" and unit.get("errors") == "0"
assert "BUILD SUCCESSFUL" in (ROOT / "build-final.log").read_text(encoding="utf-8-sig", errors="replace")
signature = (ROOT / "signature-verify.log").read_text(encoding="utf-8-sig", errors="replace")
assert signature.startswith("Verifies")
apk = DEST / "LocalTV-1.0.0.apk"
shutil.copyfile(ROOT / "app/build/outputs/apk/release/app-release.apk", apk)
paths = [ROOT / name for name in ["README.md", ".gitignore", "build.gradle.kts", "settings.gradle.kts", "gradle.properties", "gradlew", "gradlew.bat", "app/build.gradle.kts", "app/proguard-rules.pro"]]
paths += list((ROOT / "gradle/wrapper").glob("*"))
paths += [p for p in (ROOT / "app/src").rglob("*") if p.is_file()]
paths += [p for p in (ROOT / "tools").iterdir() if p.suffix in (".py", ".cs")]
source = DEST / "LocalTV-1.0.0-source.zip"
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
shutil.copyfile(ROOT / "test-final.log", DEST / "Android-TV-test-results.txt")
shutil.copyfile(ROOT / "signature-verify.log", DEST / "APK-signature.txt")
shutil.copyfile(ROOT / "app/build/reports/lint-results-release.txt", DEST / "Android-lint.txt")
instructions = """映匣 LocalTV 1.0.0 — 安装与交付说明

安装文件：LocalTV-1.0.0.apk（正式签名版本）
系统要求：Android 6.0 / API 23 或以上的电视、电视盒子。
CPU：armeabi-v7a、arm64-v8a、x86_64；同一 APK 自动使用匹配的架构。
应用包名：com.localtv.viewer

安装：把 APK 拷贝到 U 盘或电视可访问的位置，在文件管理器中打开并安装。
如电视提示不允许安装外部应用，请允许该文件管理器安装应用，再打开 APK。
安装后在应用列表打开「映匣」。首页「先体验一下」可以离线试用。
三星 Tizen、LG webOS 等非 Android 电视不能安装 APK。

连接电脑 / NAS：先开启 SMB 2/3 文件共享，再添加源，例如 smb://192.168.1.10/Photos。
连接网络目录：先开启 WebDAV，再添加源，例如 https://example.com/dav/Photos/。
需要登录时填写账号密码；多个源可以分别保存。普通网页和网盘分享链接不是 WebDAV 地址。

遥控器：方向键选择，确认键打开，返回键上一级。
全屏：↑ 上一个，↓ 下一个；视频确认键暂停/继续，←→ 跳转 10 秒。
照片确认键开启/停止 7 秒幻灯片；菜单键显示信息。

已完成验证：
• 6 项 JVM 协议测试通过，0 失败。
• 6 项 Android TV 仪器测试通过，0 失败（Android TV 36，x86_64，1080p 模拟器）。
• 已实际测试带账号认证的本机 SMB 2/3、WebDAV、中文路径及随机位置读取。
• 添加、测试连接、保存多个源、修改和移除源的流程通过。
• 遥控器浏览照片/GIF/视频、暂停、返回焦点恢复通过。
• 原生 LibVLC 解码并显示 MKV/H.264、MOV/H.264、AVI/MPEG-4、WebM/VP9 视频帧及暂停通过。
• JPG、PNG、WebP、GIF 显示及 WebDAV BMP 解码通过。
• 正式构建和 Android Lint 通过（0 错误；有依赖更新建议等非阻塞警告）。
• APK v1/v2 签名校验、ZIP 对齐和 64 位原生库 16 KB LOAD 对齐通过。
• 正式签名 APK 已安装到电视模拟器，完成启动及离线相册/视频操作检查。

验证边界：尚未在实体电视、ARM 设备、真实公网 WebDAV、超大相册或 4K/HDR 影片上测试。
HEIC/AVIF 依赖电视系统解码；影片兼容性和流畅度受内部编码、电视硬件和网络影响，不能保证所有格式。
不支持相机 RAW/TIFF；本版本不含 DLNA 发现、网盘 OAuth 登录、外挂字幕或字幕音轨选择界面。

完整源码：LocalTV-1.0.0-source.zip；源码目录 README.md 包含构建及详细使用说明。
发布签名私钥与配置保存在原开发工作区 signing/ 和 signing.properties，未包含在源码压缩包中。
请保留这两份文件以便以后同签名覆盖升级。
每个压缩包/APK 附有 .sha256 文件；第三方许可随 APK 和源码一起提供。
"""
(DEST / "测试与交付说明.txt").write_text(instructions, encoding="utf-8-sig")
