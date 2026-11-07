# 弹弹play API v2 接入说明

安卓版从 **1.3.0** 起同时支持两类弹弹play v2 服务：

| 服务 | 地址示例 | 鉴权 | 适合谁 |
|---|---|---|---|
| 自建 [danmu_api](https://github.com/huangxd-/danmu_api) | `https://danmu.example.com` | 无（可带路径/查询 Token） | 自己有 NAS / 服务器的用户 |
| 官方开放弹幕网络 | `https://api.dandanplay.net` | 需要 AppId + AppSecret | 已申请官方开发者凭证的用户 |

应用**不内置**任何官方 AppId/AppSecret，也不会替你申请；官方凭证请到
[弹弹play开发者中心](https://dev.dandanplay.com) 创建应用后获取。

---

## 一、自建 danmu_api（最简单）

1. 部署 danmu_api（Node / Docker / Vercel 等均可）。
2. 打开「弹幕伴侣 → 更多设置 → 弹幕服务地址」。
3. 填服务根地址，例如：

```
https://danmu.example.com
http://192.168.1.10:9321
https://danmu.example.com/TOKEN
https://danmu.example.com?token=TOKEN
```

4. 「官方 API 鉴权」保持 **无鉴权**。
5. 点「保存地址」→「测试连接」。

程序会自动补 `/api/v2`；路径 Token 和查询参数 Token 都会保留。
如果 danmu_api 前面还有一层需要登录的网关，本应用不支持网页登录/Cookie，请改用路径 Token。

---

## 二、官方 API（api.dandanplay.net）

### 1. 申请凭证

访问 [dev.dandanplay.com](https://dev.dandanplay.com)，注册开发者、创建应用并等待审核。
审核通过后你会得到：

- `AppId`
- 两个 `AppSecret`（任选其一）

> AppSecret 等同于密码，不要发到群里或截图里。本应用只把它加密存在本机。

### 2. 在应用里配置

1. 弹幕服务地址填：

```
https://api.dandanplay.net
```

2. 「官方 API 鉴权」选 **签名**（推荐）或 **凭证**。
3. 填 AppId 和 AppSecret，点「保存鉴权」。
4. 点「测试连接」，成功会显示返回的搜索结果数量。

签名模式下，请求只发送由 Secret 计算出的签名，Secret 本身不出设备；
凭证模式会把 AppSecret 放进请求头，**只建议在完全可信的网络中使用**。

### 3. 签名算法

官方规范：

```
X-Signature = base64(sha256(AppId + Timestamp + Path + AppSecret))
```

- `Timestamp`：Unix 秒级时间戳（`X-Timestamp`）
- `Path`：不含域名和查询参数的路径，例如 `/api/v2/comment/123450001`
- 哈希结果做标准 Base64（带 `=` 填充）
- 请求头同时带 `X-AppId`

示例（`AppId=app123`、`Timestamp=1735660800`、
`Path=/api/v2/comment/123450001`、`AppSecret=secret456`）：

```
X-Signature: oykjtAeMjRLO9sAerNa9hMyQZqFdBcWneI/ED4BerSQ=
```

应用内 `DanmuApi.signature()` 与 `Base64Codec` 是纯 Java 实现，JVM 测试里用上面这组向量做回归。

### 4. 用到的端点

| 用途 | 请求 |
|---|---|
| 搜索作品/分集 | `GET /api/v2/search/episodes?anime=关键词[&episode=5]` |
| 作品详情与分集 | `GET /api/v2/bangumi/{animeId}` |
| 获取弹幕 | `GET /api/v2/comment/{episodeId}?withRelated=true&chConvert=0` |
| 文件名识别 | `POST /api/v2/match`（`matchMode=fileNameOnly`） |

四个都是官方文档里的公开接口。应用不调用发送弹幕、关注、播放历史等受限接口。

### 5. 为什么 match 只按文件名

官方 match 推荐用「文件前 16MB 的 MD5 + 文件名」识别。安卓版是叠加在**别的播放器**上的，
拿不到正在播放的视频文件，也无法计算 MD5，所以只发 `fileName` + `videoDuration`，
`matchMode` 设为 `fileNameOnly`。识别不准时请用「手动搜索 / 换源」。

---

## 三、常见错误

| 提示 | 原因 | 处理 |
|---|---|---|
| 鉴权失败：这个服务需要 AppId / AppSecret | 服务端返回 `Missing Authentication Headers` | 填 AppId/AppSecret，或改用自建无鉴权服务 |
| 鉴权失败：AppId 无效 | `Invalid AppId` | 核对开发者中心里的 AppId |
| 鉴权失败：AppSecret 无效 | `Invalid AppSecret` | 换另一个 Secret，或重新生成 |
| 鉴权失败：签名不匹配，检查 AppSecret，或校准手机时间 | `Invalid Signature` | 检查 Secret 是否复制完整；开启自动时间校准 |
| 鉴权失败：手机时间不准，请开启自动校准时间 | `Invalid Timestamp` | 系统时间与标准时间差太多，开启「自动设置时间」 |
| 接口不存在 | 404 | 地址不是 v2 服务，或少了/多了路径 |
| 请求过多 | 429 | 官方有频率检测；等一会儿，避免连续换源 |
| 已选择官方 API 鉴权，但 AppId 或 AppSecret 没填 | 本机校验 | 补全后点「保存鉴权」 |
| 已保存；本机加密不可用，Secret 以应用私有明文保存 | Keystore 异常 | 功能可用；介意的话换机或清除密钥后重填 |

官方 API 的 403 会在响应头带 `X-Error-Message`，应用会读取并翻译成上面的提示。

---

## 四、隐私与安全

- AppSecret 优先用 **Android Keystore 的 AES/GCM 密钥**加密后存进应用私有目录；
  密钥不可导出，换机或备份无法解密。
- 个别 ROM 的 Keystore 不可用时，退回到应用私有明文，并在保存时提示。
- 从 1.3.0 起关闭了系统备份（`allowBackup=false`），避免鉴权信息随备份离开设备。
- AppSecret 不写日志、不上传；「复制诊断」只包含地址、机型、错误码，不包含密钥。
- 签名模式不会把 Secret 发到网络上。

---

## 五、兼容性说明

应用按弹弹play v2 的公开响应格式解析，并做了这些容错：

- `animes` / `data.animes` 两种包装；
- `bangumi` / `data.bangumi` 两种包装；
- 200 响应里的 `{"success":false,"errorCode":...}` 业务错误；
- `episodeId` 为数字或数字字符串；
- 弹幕 JSON / B 站 XML / AcFun XML；
- 地址带 `/api/v2`、路径 Token、查询参数 Token。

不支持：

- 需要网页登录 Cookie、OAuth 用户登录的接口；
- 需要自定义鉴权头的私有网关（可以自己在前面加反向代理转成路径 Token）；
- 发送弹幕、关注、播放历史等受限接口。

---

## 六、开发与测试

```powershell
powershell -ExecutionPolicy Bypass -File scripts/test.ps1
```

相关覆盖：

- `Base64Codec` 编码；
- 官方签名官方向量；
- 签名模式请求头（`X-AppId`/`X-Timestamp`/`X-Signature` 与算法一致）；
- 凭证模式 `X-AppSecret`；
- 无鉴权不发认证头；
- 403 `X-Error-Message` 翻译；
- 查询参数 Token 的端点规范化；
- `match` 请求体为 `fileNameOnly`；
- `data.animes` 包装兼容；
- 设置里鉴权模式/AppId 的序列化与钳制。
