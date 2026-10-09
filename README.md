# FootPrint

Android 原生城市步行轨迹记录应用。首版完全在手机本地运行；FNOS 仅作为后续备份文件的存放位置，不需要 NAS 服务端。

## 当前实现

目前已实现首版主流程：首次隐私告知、定位权限申请、前台定位服务、Room 本地轨迹存储、开始/暂停/继续/结束、路线地图、历史列表搜索、详情编辑和删除。记录过程中可添加图文记录，直接拍照或从本机选择图片并附上文字；每条图文绑定到打开编辑窗口时最近保存的轨迹点，详情地图会用橙色标记该位置。图片保存在 App 私有目录，文字和文件信息保存在 Room。历史记录页支持加密导出全部轨迹或勾选导出部分轨迹，并校验后合并恢复；归档包含轨迹、轨迹点、图文文字、点位关联和图片，并校验 SHA-256。新备份使用 `.fpbackup` 文件及密码加密；恢复会跳过本机已有的相同轨迹 ID，未完成轨迹会恢复为“待继续”。旧版未加密 ZIP 仍可恢复。

GPX/JSON 通用导出、日期筛选，以及目标手机上的锁屏/进程回收验证尚未完成。每次图文记录最多添加 8 张图片，单张不超过 20 MB。App 启动时若发现未结束轨迹且定位服务没有运行，会提示“继续记录”或“结束记录”；结束会按最后保存的轨迹点结算时长，避免把关机时间计入。加密备份采用 PBKDF2-HMAC-SHA256 派生密钥和 AES-256-GCM；密码不会保存在 App 中，忘记后无法解密。分享给其他用户时需通过其他渠道告知密码。旧版 ZIP 不含加密，也不含图文图片。地图和定位需要配置高德 Key；没有 Key 时记录页仍可运行，但地图显示配置提示。

在历史页点击“导出全部”或“勾选轨迹导出”，设置并确认备份密码后，使用 Android 系统文件选择器选择保存位置。恢复时选择 `.fpbackup` 文件并输入导出密码；旧 ZIP 可直接校验后恢复。可选择手机本地目录或文件管理器提供的 NAS 共享目录；App 不直接连接 NAS。

## 开发环境

- Android Studio（支持 Android Gradle Plugin 9.1）
- JDK 17
- Android SDK 37
- 一台 Android 手机用于验证后台定位
- 高德开放平台 Android Key

在项目根目录的 `local.properties` 中添加：

```properties
sdk.dir=/path/to/Android/Sdk
AMAP_API_KEY=your_key
```

使用 Android Studio 打开项目根目录并同步 Gradle。首次构建需要联网下载 Android Gradle Plugin、AndroidX 和高德 SDK 依赖。

Debug 包用于模拟器和开发调试，不启用代码收缩。发布时选择 `release` 构建变体并生成签名 APK；Release 会启用 R8 和未使用资源收缩、压缩 APK 内的 `.so`，并按 CPU 架构分别输出 APK（例如 `app-arm64-v8a-release.apk`）。安装到 64 位 ARM 手机时选 `arm64-v8a`；不同架构的 APK 不能互换。压缩 native 库会在安装时解压。Android Studio 的“Generate Signed Bundle / APK”可生成签名后的发布包。

高德 Key 需要在控制台绑定应用包名 `com.footprint.app` 和签名 SHA-1。不要提交 `local.properties` 或真实 Key。

首版使用 Android 前台定位服务和 Room 本地数据库。记录期间会显示持续通知；Android 13 及以上如果用户拒绝通知权限，系统仍会运行前台服务，但通知展示方式由 Android 管理。系统重启或服务停止后，重新打开应用会提示继续或结束未完成轨迹；中断期间无法补记位置。
