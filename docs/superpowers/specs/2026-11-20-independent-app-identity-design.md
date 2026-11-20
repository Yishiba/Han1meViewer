# 独立应用身份与公开客户端

2026-11-20 用户确认使用 `io.github.yishiba.han1meviewer`，并保持客户端开源、收款授权服务私有。

## 应用身份

- 正式版 `applicationId` 固定为 `io.github.yishiba.han1meviewer`，Debug 自动追加 `.debug`。现有 Kotlin `namespace` 和源码包路径暂保留 `com.yenaly.han1meviewer`，避免把内部重命名与用户数据迁移混在一起；Manifest 的 Provider authority 使用 `${applicationId}`。
- 在既有个人 Firebase 项目注册新正式版和 Debug 应用；本地 `google-services.json` 必须同时包含两个新客户端，且继续忽略于 Git。更新知识库的加密配置备份。
- 新包从第一版起只用自有正式证书，不再附带旧 debug 签名或轮换证明。构建脚本核验 Android 8 与 Android 13 都识别同一张新证书。
- 新包与旧包并存，不能在系统层覆盖迁移。先导出旧包备份，再在新包恢复设置和记录；旧包保留直至用户确认。备份不含 Cookie 和视频文件，下载媒体需另行处理或重下。迁移以真机结果为准。
- 旧包名的 GitHub 草稿 Release 不公开发布；新 APK 经真机验证后另建标签和草稿 Release。公开发布前确认 GitHub 更新 API 能找到正确包。

## 仓库边界

- Android 客户端与公共协议、许可证和上游署名继续留在公开仓库。已公开的服务端历史不能撤回，后续服务端实现在独立私有仓库维护。
- 私有仓库保留当前 `commerce_server` 原型、测试和部署说明；公开仓库移除可执行服务端代码，只保留客户端所需的 API 契约与当前未开售状态。生产密钥只在服务端秘密管理处保存，不能进入任一 Git 仓库。

## 验收

1. Firebase 新正式／Debug 应用均已注册，两个构建变体能解析配置。
2. 新 APK 的包名与 Android 8、13 证书检查正确，能与旧 App 并存启动；旧 App 数据不被清理。
3. 备份恢复范围与不能迁移的资料如实展示；安装与功能回归整批完成后统一验证。
4. 私有服务端仓库可访问且代码完整，公开仓库没有服务端实现或真实凭据；CI 仍能构建客户端。
