# 安全与隐私

## 隐私承诺

- 应用**不申请 `INTERNET` 权限**，没有任何联网能力；
- 不集成统计、广告、崩溃上报等第三方 SDK；
- 处理记录写入应用私有目录 `filesDir/logs/`，512 KB 后轮转，不上传；
- 诊断报告只在用户主动点「复制全部」时生成并放入剪贴板，内容包含机型 / 系统版本 / 失败项，**不含账号，也不列出已安装应用**。

## 报告安全问题

如果你发现安全或隐私问题（例如意外的网络访问、敏感信息落盘、越权操作），请**不要开公开 issue**，改用：

- GitHub 的 [Private vulnerability reporting](https://github.com/mfskys/SkipAd/security/advisories/new)（推荐），或
- 通过仓库主页列出的联系方式私下联系维护者。

请尽量附上：复现步骤、Android 版本 / 机型、相关日志（可用应用内「复制全部」，注意先自行检查是否含有你不想公开的内容）。

## 支持范围

| 版本 | 支持 |
|---|---|
| 最新 release | ✅ |
| 更早版本 | 仅接受"升级到最新版是否仍存在"的确认 |

## 权限与敏感能力清单

| 能力 | 声明位置 | 说明 |
|---|---|---|
| 无障碍服务 | `AndroidManifest.xml` | 读节点树、执行点击/手势；只在用户开始处理后工作 |
| `QUERY_ALL_PACKAGES` | `AndroidManifest.xml` | 判断预装应用是否被卸载/停用 |
| `SYSTEM_ALERT_WINDOW` | `AndroidManifest.xml` | 1×1 透明悬浮窗，防 ROM 冻结进程 |
| `REQUEST_INSTALL_PACKAGES` | `AndroidManifest.xml` | 仅用于查询「安装未知应用」授权状态 |
| Shizuku `API_V23` | `AndroidManifest.xml` | 可选加速通道，仅用于开启无障碍；未安装 Shizuku 时无任何行为 |
| 音量键紧急停止 | `ad_skip_accessibility_config.xml` | **仅在会话期间**动态开启按键过滤，只识别音量键，不记录任何按键内容 |
