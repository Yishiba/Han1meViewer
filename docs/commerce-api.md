# 会员客户端公开协议

当前所有功能免费，会员入口仅用于联调。付款核验与授权服务位于独立私有仓库，未部署生产环境。此页描述公开 Android 客户端预留的接口边界，不包含服务端密钥或商户配置。

| 请求 | 用途 |
|---|---|
| `POST /v1/auth/start` | 按设备 ID 发起爱发电 OAuth2 登录，返回授权地址与一次性登录票据 |
| `POST /v1/auth/complete` | 以票据换取本客户端的会话令牌 |
| `GET /v1/membership` | 查询当前会员状态和短期签名授权 |
| `POST /v1/membership/refresh` | 刷新会员状态；可补交待核验订单号 |
| `POST /v1/auth/logout` | 退出当前会话 |

客户端只接受 HTTPS 服务地址和公开的 ES256 验签公钥；两者通过 `COMMERCE_API_BASE_URL`、`COMMERCE_PUBLIC_KEY_DER_B64` 在构建时配置。商户 API Token、OAuth 客户端密钥、订单数据与签名私钥只能在服务端保存。服务端返回授权并不代表当前 App 已启用 Pro 功能拦截。
