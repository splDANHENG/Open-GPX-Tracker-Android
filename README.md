# Open GPX Tracker for Android

参考 [iOS Open GPX Tracker](https://github.com/merlos/iOS-Open-GPX-Tracker) 制作的非官方 Android GPS 轨迹记录应用，采用 **GPL-3.0-or-later** 开源授权。

当前版本：**0.12.1**。支持 Android 6.0（API 23）及以上系统。

[English documentation](README.en.md)

## 核心功能

- **GPS 轨迹录制**：开始、暂停、继续和结束录制，实时显示轨迹、累计距离、时长、海拔、车速及定位状态。
- **后台录制**：录制期间使用 Android 位置前台服务，显示常驻通知。通知显示已录制距离及精确到分钟的时长，点击回到应用操作。
- **录制悬浮窗**：显示“GPS正在录制中”、经纬度、海拔、当前车速、距离及精确到秒的时长；可拖动并记住位置，点击返回应用，暂停或结束后消失。
- **防误触锁定**：新轨迹开始一分钟后锁定暂停和结束按钮，连续点击独立锁图标三次解锁；解锁后十五秒无操作再次锁定。
- **历史轨迹与续录**：轨迹自动保存到本地 SQLite 数据库，可查看、删除或继续录制；重新录制时创建新轨迹段。
- **导入外部 GPX**：通过系统文件选择器导入轨迹，保留分段，并可继续录制。支持 GPX 轨迹点，不导入航点、路线或自定义扩展。
- **GPX 导出与分享**：支持默认目录保存、系统“另存为”、文件分享和删除。Android 10 及以上可管理默认目录 `Documents/OpenGPXTracker` 内的 GPX 文件。
- **地名与文件命名**：导出时使用 OpenStreetMap Nominatim 建议市级或同等地位的地名前缀，也可手动修改；提交的前缀独立保存为历史地名。文件名自动填充，用户开始编辑时清空默认名称。
- **地图与浏览缓存**：提供 OpenStreetMap 和 OpenTopoMap，支持缩放、旋转、定位跟随与回中；缓存已浏览地图区域，容量为 250 MB。
- **统一权限提示**：首次启动和重新打开时检查相关权限，未授权时提供再次授权或进入系统设置的入口。悬浮窗权限缺失不阻止 GPS 录制。

## GPS 与后台行为

使用 Android 系统定位接口，不随应用打包 Google Play services 定位客户端。录制期间才启用位置前台服务；暂停或结束后停止该服务并移除通知。

弱 GPS、无 GPS 或定位接口暂时出错时，**不会自动暂停或结束轨迹**。录制计时继续运行，定位接口出错时重试；重新收到坐标后继续当前轨迹段。没有新坐标的期间不会生成虚构轨迹点，恢复后两个点之间可能出现直线连接。定位超过十秒未更新时标记失去定位，隐藏过时的车速和海拔。

系统结束进程后，下次打开会将已保存的轨迹恢复为暂停状态。系统杀进程、强制停止或设备重启后的持续录制不作保证。

## 构建与验证

需要 JDK 17、Android SDK 36，以及兼容 Android Gradle Plugin 8.13.2 的 Android Studio。

```shell
./gradlew assembleDebug testDebugUnitTest lintDebug
```

调试安装包生成在 `app/build/outputs/apk/debug/app-debug.apk`。

设备测试请使用专用模拟器或无重要轨迹的测试设备：

```shell
./gradlew connectedDebugAndroidTest
```

设备测试任务可能卸载应用并清除本地轨迹数据库。使用多台设备时，请明确指定专用测试设备。

## 地图、网络与隐私

轨迹保存在设备本地。应用未接入分析或自动上传轨迹服务；Android 系统备份、用户主动分享和导出受相应系统设置及接收应用影响。

地图浏览会向选定地图服务发送瓦片请求。打开 GPX 导出对话框时，会将轨迹第一个坐标发送至 Nominatim 查询地名；建议结果按轨迹缓存，请求串行执行且设备端间隔至少一秒。前缀可以修改或清空。

地图数据署名：© OpenStreetMap contributors。OpenTopoMap 还使用 SRTM 数据及 OpenTopoMap 制图，并按其 CC-BY-SA 授权显示署名。地图数据及服务条款独立于应用源码授权。

缓存只覆盖实际浏览过、尚未被淘汰或过期的瓦片，**不等于完整离线地图，也不提供区域批量预下载**。公开部署及大规模使用须遵守 [OSM 瓦片政策](https://operations.osmfoundation.org/policies/tiles/)、[Nominatim 使用政策](https://operations.osmfoundation.org/policies/nominatim/)及 [OpenTopoMap 条款](https://opentopomap.org/about)。规模扩大时需更换合规服务或自建服务。

## 当前限制

- 极长轨迹的地图刷新采用完整轨迹重绘，尚未验证十万点以上的交互性能。
- 导入文件限制为 50 MB、50 万个轨迹点；缺失时间戳保持缺失，不补造时间。
- Android 10 以下主要通过系统文件选择器导入及导出，不提供默认目录文件列表管理。
- 设备省电策略、权限、系统定位能力与卫星信号会影响后台录制及定位质量。

## 授权与来源

应用源码采用 **GNU GPL 第三版或更新版本（GPL-3.0-or-later）**。详见 [LICENSE](LICENSE)、[NOTICE](NOTICE) 和 [第三方依赖声明](THIRD_PARTY_NOTICES.md)。修改及分发应保留署名和授权信息；分发修改版本或安装包时须按 GPL 提供相应源码。

iOS 参考项目由 Juan M. Merlos 及贡献者开发，Copyright (C) 2014–2023。参考版本提交为 `cd0954ffcd5ae688b76f8b796607c98ea5c6a0eb`。Android 实现及修改于 2026 年完成；这是非官方 Android 项目，不代表 iOS 原作者发布或认可。

仓库包含应用源码、资源、测试、构建配置、Gradle wrapper 及项目文档。个人轨迹、测试截图、签名密钥、本机配置和生成的安装包不属于源码发布内容。
