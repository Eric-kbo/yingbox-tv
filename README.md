# 映匣 YingBox TV

一个使用 Kotlin 和 Jetpack Compose for TV 开发的原生 Android 电视照片、视频查看器。

当前本地版本为 1.1.0，交付安装包 `dist/YingBoxTV-1.1.0.apk`；更新内容见 [RELEASE_NOTES.md](RELEASE_NOTES.md)。

## 安装与使用

使用本次交付的 `YingBoxTV-1.1.0.apk`，复制到 U 盘或电视可访问的位置，在电视文件管理器中打开并安装。已发布的 1.0.0 仍可从 [GitHub Releases](https://github.com/Eric-kbo/yingbox-tv/releases) 或 [YingBoxTV-1.0.0.apk](https://github.com/Eric-kbo/yingbox-tv/releases/download/v1.0.0/YingBoxTV-1.0.0.apk) 下载。下载页面中的 `Source code` 是源码压缩包，安装请选 `.apk` 文件。

要求 Android 6.0 或以上，支持 ARM 32 位、ARM 64 位及 x86_64。三星 Tizen、LG webOS 等非 Android 系统不能安装。电视须允许从文件管理器或 U 盘安装应用。安装后在应用列表中打开「映匣」。无需注册；首页的「先体验一下」可离线查看内置的照片、GIF 和视频。

也可按下文「构建」自行生成 `app/build/outputs/apk/debug/app-debug.apk`（调试版）或 `app/build/outputs/apk/release/app-release.apk`（正式版）。

1.1.0：点击「添加源」，默认显示手机二维码。手机与电视连接同一网络，扫码后在浏览器填写共享地址和必要的账号、密码，点击「连接并保存到电视」。应用先验证目录，再自动保存到电视；名称可留空，默认使用文件夹名称。无需安装手机端应用。

也可选择「遥控器填写」，只需填写地址和必要的登录信息，然后「连接并保存」。上下键切换输入项；显示名称和 Windows 域收在「名称与高级设置」。可以绑定多个设备，在「管理源」中修改或移除连接记录。二维码有效期 10 分钟，离开扫码页面后立即失效；失败时会显示地址、账号或网络相关的提示。

| 来源 | 地址示例 | 准备条件 |
| --- | --- | --- |
| 电脑 / NAS（SMB） | `smb://192.168.1.10/Photos` | 设备开启 SMB 2/3 共享，电视能访问设备；账号有读取权限 |
| 网络目录（WebDAV） | `https://example.com/dav/Photos/` | 服务开启 WebDAV，支持目录 PROPFIND 和文件 GET；支持 Basic 账号认证 |

SMB 可填写共享后的子目录、端口和 Windows 域；也支持 `\\设备IP\共享名` 写法。普通网页、网盘分享页面不是 WebDAV 目录，不能直接当作媒体源。互联网来源需有可访问的 WebDAV 服务地址。HTTPS 使用系统信任的证书；自签名证书须由设备系统信任。

## 遥控器

| 场景 / 按键 | 操作 |
| --- | --- |
| 浏览列表：方向键、确认键 | 移动焦点、进入文件夹或打开媒体 |
| 全屏：↑ / ↓ | 上一个 / 下一个照片或视频 |
| 视频：确认键 | 暂停 / 继续，结束后重播 |
| 视频：← / → | 后退 / 前进 10 秒，长按连续跳转 |
| 照片：确认键 | 开始 / 停止每 7 秒的幻灯片 |
| 菜单键 | 显示播放信息；在源卡片上打开修改 |
| 返回键 | 退出全屏、上一级目录、返回源列表 |
| 源卡片：长按确认键 | 修改该源 |

输入框使用电视系统输入法。全屏退出后恢复当前文件的焦点；视频记住播放位置。照片与视频可以混合浏览，也可以切换「照片」「视频」筛选。

## 格式与性能

照片识别 JPG / JPEG、PNG、WebP、GIF、BMP、HEIC / HEIF、AVIF。GIF 支持动画；HEIC、AVIF 的解码能力取决于电视的 Android 版本。TIFF、相机 RAW 未在本版本实现。

视频使用 LibVLC，识别 MP4、MKV、MOV、AVI、WebM、TS、M2TS、MTS、MPEG、VOB、FLV、WMV、ASF、3GP、OGV、RM / RMVB 等常见文件类型。具体能否流畅播放还取决于文件内部的视频 / 音频编码、电视硬件和网络带宽。优先硬件解码；4K、HDR、AV1 等未在实体电视上验证，不能保证所有格式及编码组合都能播放。内嵌字幕由播放器处理，本版本没有音轨 / 字幕选择界面或外挂字幕管理。

文件夹优先，照片和视频默认按文件时间从新到旧排列；时间缺失的文件放在末尾，同一时间按名称自然排序。这里的时间是 SMB 的 LastWriteTime 或 WebDAV 的 getlastmodified，不是 EXIF 拍摄日期。可在「搜索与筛选」切换最早优先或名称排序，每个源记住排序选择。

支持照片 / 视频类型、文件名关键字、年份 / 月份、最近 7 / 30 / 365 天及扩展名格式组合筛选。筛选仅作用于当前文件夹，不递归扫描子目录。「按月定位」可直接跳到列表中对应月份，在时间排序下提供。筛选结果内仍支持上下键连续查看。

目录的时间索引、排序与筛选在后台执行，重复筛选使用内存元数据，不重新访问网络；焦点定位与播放序列用路径索引，避免遥控器每次移动都遍历完整列表。目录缓存最多 6 个或合计约 60,000 条，单个超大目录仍可使用。列表使用五列懒加载网格。缩略图异步读取并缓存，视频缩略图最多同时生成两个，缓存限制约 100 MB。播放按需分段读取，不先下载完整影片。

本机保存源设置；密码使用 Android Keystore 和 AES-GCM 加密，禁用系统备份。不连接开发者服务器，不收集账号或媒体内容；源设备只进行读取。HTTP 和 SMB 的传输安全取决于源及网络，远程连接建议使用 HTTPS WebDAV。播放器通过只监听本机回环地址的随机端口读取媒体，使用随机令牌，不把来源密码写进播放地址。

扫码添加使用电视临时开放的局域网 HTTP 页面，含随机令牌、来源 / Host 检查、请求大小限制和有限工作线程；只在扫码页面打开时运行。手机填写的密码不会写入链接、浏览器持久存储或应用日志。此连接是局域网明文 HTTP，请在自己的可信网络内使用。已有源和加密密码可通过同签名覆盖安装保留。

## 工程结构

- `browser/`：不可变目录索引、筛选条件、月份定位、浏览状态和排序偏好。
- `pairing/`：添加源草稿与名称生成、临时局域网表单服务器、电视网络地址选择。
- `data/`：SMB / WebDAV 读取、源持久化、HTTP 分段、文件时间解析。
- `ui/`：资料库、浏览页、筛选弹窗、添加源和全屏播放，分别组织。
- `MainViewModel`：目录 / 查询任务取消、旧结果隔离、缓存与播放状态。

界面参考 [Apple 的 tvOS 设计指南](https://developer.apple.com/design/human-interface-guidelines/designing-for-tvos) 的内容优先和遥控器焦点原则，采用中性色、简洁排版与克制的焦点反馈；图标使用本项目绘制的矢量图形。

## 构建

需要 JDK 17、Android SDK 36、网络可访问 Google Maven 和 Maven Central。Gradle Wrapper 已包含。

```powershell
./gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintRelease
```

正式 APK 使用固定签名身份。本工作区的 `signing/LocalTV-release.jks` 与 `signing.properties` 应一起保留以便以后覆盖升级；源码压缩包不包含私钥。新环境执行下列命令会生成自己的签名，不能覆盖安装使用原签名的版本。

```powershell
python tools/create_signing.py
./gradlew.bat :app:assembleRelease
```

签名脚本需要 `keytool` 位于 PATH。依赖固定版本，发布包包含三个架构的原生播放库，因此体积较大。`tools/maven_proxy.py` 是当前 Windows 环境使用的可选、只读 Maven 下载适配器；一般构建无需使用它。如果使用，先启动脚本，再给 Gradle 加 `-PlocaltvMavenProxy=http://127.0.0.1:8766`。

## 验证

JVM 测试涵盖字节范围、自然排序、地址校验、WebDAV XML、认证及重定向边界，以及时间解析、时区月份、组合筛选、未知时间、50,000 条目录和临时扫码服务的令牌 / 过期 / 来源 / 请求限制。Android 仪器测试使用 Android TV 模拟器和本机只读 SMB / WebDAV 测试源，涵盖密码加密、中文路径、随机位置读取、HTTP 分段、手机配对 / 遥控器添加 / 修改 / 删除多个源、焦点恢复、查询取消与原生视频解码。

WebDAV 测试源：安装 Python `Pillow`、`imageio-ffmpeg`，执行 `python tools/fixture_server.py` 及 `python tools/create_test_media.py`。SMB 测试源：`tools/SmbFixture.cs` 使用 SMBLibrary 1.5.5 及 SMBLibrary.Win32 1.5.5，在 Windows .NET Framework 编译并运行，参数为 `tools/test-data` 的绝对路径；监听回环地址 1445 端口。测试账号 `viewer`、密码 `localtv-test` 仅用于本机测试源。模拟器使用 `10.0.2.2` 访问主机；其他测试设备通过仪器参数 `fixtureHost` 指定可访问的测试主机。

仪器测试会写入和清理测试媒体源，应在专用模拟器或测试设备运行；日常使用的电视请安装正式 APK。

```powershell
./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w com.localtv.viewer.test/androidx.test.runner.AndroidJUnitRunner
```

本地交付包的最终验证结果见 `dist/测试与交付说明.txt`（`dist/` 不提交到源码仓库）。模拟器验证不能代替具体品牌电视的硬件解码及遥控器适配验证。

## 第三方许可

第三方声明和完整许可文本位于 `app/src/main/assets/licenses/`，同时随 APK 打包。LibVLC 采用 LGPL；本项目提供完整应用源码及构建文件，可以替换依赖并重新构建、链接。LibVLC 的上游源码和版本链接在 `THIRD-PARTY.txt` 中。内置演示媒体是本项目生成的原创程序绘图与动画。
