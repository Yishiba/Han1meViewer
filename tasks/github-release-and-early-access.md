# 自有 GitHub Release 与首批试用

**历史候选记录**：本文件描述旧包名 `com.yenaly.han1meviewer` 的草稿 Release。2026-11-20 用户决定改用独立包名 `io.github.yishiba.han1meviewer`；旧草稿不得公开发布。当前方案见 [独立应用身份设计](../docs/superpowers/specs/2026-11-20-independent-app-identity-design.md)。

记录于 2026-11-20。应用目前不收款，爱发电产品准入仍待回复。本文件用于首个自有 APK 发布前核对，不是已发布公告。

## 当前状态

- 应用内 Release API、仓库链接和反馈入口指向 `Yishiba/Han1meViewer`；客户端不再携带 GitHub Token，CI 构建产物不再作为更新通道。
- GitHub Issues / Discussions 已启用；README 有无露骨内容的功能示意和三步试用说明。首个自有签名 APK 已上传为草稿 Release，尚未公开发布。
- CI 仓库目前没有签名或 Firebase 配置 Secrets，不能产出公开 Release。当前手机是 Android 13、安装版本 `0.26.0-release+26111903`，`versionCode=26111903`；旧证书 SHA-256 为 `25ffea57792a71ac2a8fcbb5d338df44cabdd4a62d2896a5e885e3157d1b0d64`，来自本机 `~/.android/debug.keystore`。
- 用户明确要求自有正式签名。已创建 `~/.android/han1meviewer-release.jks`（RSA 4096，权限 600；密码仅保存在 macOS 登录钥匙串的 `Han1meViewerReleaseKeystore` 项），新证书 SHA-256 为 `bbc987ea88753d153963350fd9c13f603ac7c6686900e034f9ab5b449e8d8d8c`；并用旧、新私钥创建私有签名传承文件 `~/.android/han1meviewer-signing-lineage.bin`。三者都不得提交仓库，且必须分别安全备份。
- Android 官方 [v3/v3.1 签名轮换](https://source.android.com/docs/security/features/apksigning/v3)允许在支持的系统上使用新证书覆盖旧安装。当前方案让 Android 13+ 使用自有新证书，Android 8–12 为兼容既有安装继续使用旧证书；低版本若要完全改用新证书，需另行迁移数据或改包名。2026-11-20 已在 Android 13 真机从 `26111903` 覆盖安装到 `26112002`，首次安装时间仍为 2026-11-18，首页正常加载，原有自动译中文设置仍生效。拉取手机实际安装的 APK 验证：API 33 新证书、API 27 旧证书。

## 已构建的发行候选版

- Tag：`v0.26.0-release+26112002`，指向构建时的源码提交 `3e05c10d`。
- APK：`app/build/outputs/apk/release/Han1meViewer-v0.26.0-release+26112002.apk`；SHA-256：`4d798794d108dfd7e792ad01e9e2c87481c2b8f18c3691aec77ccb32f92f4fa8`。
- GitHub 草稿 Release 已上传 APK 与 `SHA256SUMS`，资产 API 的 `digest` 与本地哈希一致。草稿不出现在公开的 `releases/latest`；发布后才可验证应用内更新。

## 首个 Release 的准备与验收

1. 核对本地工作区的大量未提交功能变更，审查无私人数据、Token 或测试凭据后，提交到要发布的分支。GitHub Release 的 tag 必须指向与 APK 同一份代码。
2. 运行 `bash scripts/build-release-local.sh`。脚本从 macOS 钥匙串读取新密钥密码，使用 JDK 21 构建，再用 `apksigner` 将新旧证书和签名传承写入同一 APK；它校验 Android 13+ 的新证书、Android 8 的兼容旧证书及版本号。不要把密码、密钥文件或签名传承放进仓库、聊天或 APK 资源中。
3. 生成 Release APK 后，在 Android 13 真机用 `adb install -r` 覆盖安装，确认包名与已有设置仍在；不要卸载旧 App。本次首次安装时间未变、首页翻译设置仍生效，但下载记录与备份尚未逐项人工核对。另找低版本设备验证时，按签名传承的 API 兼容范围核对。
4. 创建与 `versionName` 对应的 Git tag 和 GitHub Release，只上传 APK、校验值与下方发布说明；从 Release API 验证 `digest=sha256:...`、资产名匹配 `Han1meViewer-v*.apk`，再用已安装旧版检查应用内更新。
5. 若 Android 13 真机不接受签名传承，停止发布，先制定数据迁移方案；不能让已有用户通过卸载重装来“完成升级”。

## 首版发布说明草稿

> **仅供成年用户使用。** 这是本 fork 的首个独立发行版，与内容网站及原仓库维护者无隶属关系。当前所有功能免费；“体验与价格研究”不收款、不到期锁功能。
>
> - 中文或英文搜索词可在设备端译为日文，保留原词，译文可编辑。
> - 首页、搜索结果和详情可自动译为选定语言；首次使用需要下载端侧模型。
> - 支持智能合集、下载优化、自动备份、多版本恢复及 WebDAV 同步。
> - 安装后到“设置 → 自动翻译”选目标语言；遇到问题到本仓库 Issues 提交试用反馈。
>
> 只从本仓库 Releases 下载 APK。覆盖安装需要与已安装版本使用相同签名；安装前建议导出备份。不要在反馈中上传账号 Cookie、私人文件或露骨截图。

## 首批用户招募

先通过本人已有联系渠道邀请 20–30 位成年 Android 用户，告知当前无收费，征得同意后发送自有 Release 链接。暂不在禁止成人话题的社区投放，也不在原项目仓库或群组冒充官方发布。可直接使用以下私下邀请文案：

> 我在测试一个 Android 第三方客户端，重点改进了中文转日文搜索、标题翻译、下载与备份。当前免费，面向成年用户。愿意试用的话，我会发送自己仓库的安装包链接；试用一周后想请你用几分钟反馈搜索是否更容易、哪些功能有用。应用不提供或上传视频内容。

试用反馈使用本仓库的 `🧪 试用反馈` Issue 表单；不要求参与者提供邮箱、手机号或账号。以 Release 资产下载次数作为粗略触达量（重复下载可能重复计数），以自愿反馈估计有效试用，不能把下载次数当作独立用户。第一轮观察：至少 20 份真实反馈；记录首页加载是否成功、翻译搜索是否找到目标、7 天后是否继续使用、对 ¥30/年高级工具的意向。若反馈不足，先改善下载和安装说明；若首页加载失败集中出现，优先修网络问题；若搜索价值不明显，再调整产品定位。未经平台明确受理，不公开付款链接或启用 Pro 拦截。
