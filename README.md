# 弹幕伴侣 v1.35

给任意安卓播放器叠加弹幕的悬浮窗工具：读取系统媒体会话识别片名 / 季 / 集，匹配你配置的弹弹play v2 弹幕库，跟随播放进度。

> **使用前需自配弹幕库。** 本应用不内置弹幕源，也不内置官方凭证。
> 先自建 [danmu_api](https://github.com/huangxd-/danmu_api) 并填入服务地址，或到
> [弹弹play开发者中心](https://dev.dandanplay.com) 申请 AppId / AppSecret 接入官方 API。
> 没有配置时悬浮窗能打开，但不会出现弹幕。

![真机：自配弹幕库在播放器上叠加弹幕](docs/screenshots/device-playback.jpg)

*真机截图：弹幕来自自配的弹弹play v2 服务，叠在播放器画面上；右侧橙色「弹」字圆点是悬浮球。*

[![Release](https://img.shields.io/github/v/release/km191/baomihua-danmaku-android?color=FF7A45)](https://github.com/km191/baomihua-danmaku-android/releases/latest)
[![License](https://img.shields.io/badge/license-MIT-blue)](LICENSE)
[![minSdk](https://img.shields.io/badge/Android-6.0%2B-3DDC84)](#下载与安装)
[![APK](https://img.shields.io/badge/APK-121_KB-informational)](https://github.com/km191/baomihua-danmaku-android/releases/latest)

**[下载最新 APK](https://github.com/km191/baomihua-danmaku-android/releases/latest)**（121 KB，已签名，Android 6.0+）

**当前版本：弹幕伴侣v1.35** · [更新记录](CHANGELOG.md) · [OPPO 适配](docs/OPPO-COLOROS.md) · [API 接入](docs/DANDANPLAY-API.md)

## 功能

- 自动识别片名 / 季 / 集，搜索并加载弹幕；换集优先沿用已选来源。
- 跟随播放、暂停和拖动；退出播放后自动收起，不在桌面继续滚。
- 悬浮球 + 小面板：字号、速度、不透明度、描边、阴影、行数、密度、校准。
- 弹幕行数 `1`–`30`，默认 12，从屏幕最顶部往下排；悬浮球默认靠右。
- 手动搜索换源、载入本地 XML / 弹弹play JSON、屏蔽关键词、合并重复、时间偏移。
- 自建弹幕源免鉴权；官方 API 支持签名 / 凭证两种 AppId 鉴权。
- 主面板、悬浮球小面板、通知栏都能显示 / 隐藏悬浮窗，服务可继续后台运行。

## 下载与安装

1. 从 [Releases](https://github.com/km191/baomihua-danmaku-android/releases/latest) 下载 `DanmuCompanion-1.35.apk`。
2. 传到手机安装，允许「安装未知来源应用」。
3. 使用本地自签名证书，系统可能提示未知开发者。

支持 Android 6.0+（API 23），目标 Android 14（API 34）。

## 快速开始

1. 打开 App，授予 **悬浮窗权限**、**通知使用权**。
2. Android 13+ 再授予 **通知权限**。
3. 在「更多设置」配置弹幕库（见下一节）。
4. 点「开启悬浮窗」，在任意播放器里播放视频。

> OPPO / 一加 / realme 还需要放行电池优化、自启动等，见「OPPO / ColorOS」。

## 弹幕库

### 自建 danmu_api（无鉴权）

在「更多设置 → 弹幕服务地址」填入服务根地址，程序会自动补 `/api/v2`：

```text
https://danmu.example.com
http://192.168.1.10:9321
https://danmu.example.com/YOUR_TOKEN
https://danmu.example.com?token=YOUR_TOKEN
```

部署方法见 [danmu_api](https://github.com/huangxd-/danmu_api)。

### 官方 api.dandanplay.net（需 AppId / AppSecret）

1. 在 [弹弹play开发者中心](https://dev.dandanplay.com) 申请应用。
2. 地址填 `https://api.dandanplay.net`。
3. 鉴权选 **签名**（推荐）或 **凭证**，填 AppId / AppSecret 并保存。
4. 点「测试连接」。

AppSecret 使用 Android Keystore 加密保存在本机。完整说明见
**[弹弹play API v2 接入](docs/DANDANPLAY-API.md)**。

没有服务也可以把弹幕 XML / JSON 放进手机，用「载入本地弹幕」离线加载。

## 使用

| 操作 | 说明 |
|---|---|
| 悬浮球单击 | 弹出小面板，播放不中断 |
| 悬浮球长按 | 打开完整设置 |
| 拖动悬浮球 | 换位置；复位按钮在「更多设置」 |
| 隐藏 / 显示 | 主面板按钮或下拉通知栏点通知 |
| 停止服务 | 通知栏「停止服务」或「更多设置 → 停止服务」 |

- 弹幕默认从顶部开始，行数可在主面板或小面板调整。
- 悬浮球默认停在屏幕右侧，留 14dp 边距，避免被曲面屏裁切。
- 隐藏不会停止服务，再显示时不用重新识别和加载。

## OPPO / ColorOS

ColorOS 会限制后台应用。识别到 OPPO / 一加 / realme 时，应用会在「自检」下面显示
**ColorOS 后台设置**，建议逐项放行：

- **电池优化**：允许后台活动
- **自启动**：允许
- **耗电管理**：允许后台活动，别选智能省电
- **后台弹出界面**：允许，否则点悬浮球可能弹不出小面板

详细路径与排错见 **[OPPO / ColorOS 适配说明](docs/OPPO-COLOROS.md)**。

## 常见问题

| 现象 | 处理 |
|---|---|
| 没有弹幕 | 检查服务地址、来源、显示开关；点「测试连接」 |
| 开启后没有悬浮窗 | 看「自检」弹幕窗是否已挂载；点「测试横幅」验证权限 |
| 锁屏后弹幕消失 | 放行电池优化 / 耗电管理 |
| 重启后不自动恢复 | 允许自启动；打开一次应用也会恢复 |
| 通知栏点了没反应 | 展开通知，点「隐藏悬浮窗 / 显示悬浮窗」动作 |
| 识别错作品 | 用「手动搜索 / 换源」 |
| 弹幕位置不对 | 调整弹幕行数；悬浮球用「复位到右边」 |
| 需要完全退出 | 通知栏「停止服务」或「更多设置 → 停止服务」 |

## 构建与测试

需要 Android SDK（build-tools 34、platform android-34）和 JDK 17+，无需 Gradle：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/build.ps1   # dist\DanmuCompanion-1.35.apk
powershell -ExecutionPolicy Bypass -File scripts/test.ps1    # 190 项测试
```

## 文档

- [更新记录](CHANGELOG.md)
- [OPPO / ColorOS 适配](docs/OPPO-COLOROS.md)
- [弹弹play API v2 接入](docs/DANDANPLAY-API.md)
- Windows 原版：[Baomihua-danmu-companion](https://github.com/ghjkllasdf-fake/Baomihua-danmu-companion)

## 隐私与许可

- 不收集统计、不上传设备信息；弹幕缓存只在应用私有目录。
- 「通知使用权」只用于读取媒体会话，不读取通知内容。
- 不提供影视资源，与网易爆米花、弹弹play 及相关弹幕站点没有隶属关系。
- 代码采用 [MIT License](LICENSE)。
