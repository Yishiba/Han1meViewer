# 收款链路：待商户受理的授权核心

首个爱发电 Pro 方案的价格、权益文案与后台实际状态见 [`afdian-plan-draft.md`](afdian-plan-draft.md)。2026-11-20 已创建 ¥30/年方案并隐藏；**平台提示隐藏后仍可通过直链购买**，因此当前不能传播付款链接或宣称支付即解锁。

当前中国大陆经营主体尚无获批成人业务商户账号，因此**没有可用收银台，也没有可验证的支付通知**。CCBill 官方 Visa/Mastercard 商户地区清单未列中国大陆，Segpay 成人商户清单仅列美国、欧盟或英国主体；这两个渠道当前不能按既有大陆主体直接接入。本目录仅实现订单状态和短期签名授权核心；绝不把测试付款或客户端回跳视为真实付款。不要部署为对外支付服务，不要在 App 中启用付费拦截。

## 现有代码

- `CommerceLedger.create_order` 创建待付订单；价格和币种只在服务端初始化时配置，不能采信客户端报价。
- 经商户渠道核验后，适配器可调用 `settle(VerifiedPayment, event_id)`。金额、币种、订单号、交易号必须精确匹配；相同事件和交易重复不会再次开通。
- 已核验退款或拒付后调用 `refund`，同一商户事件不能重放到另一笔交易。`entitlement` 生成最长 24 小时有效的 P-256／SHA-256（ES256）签名快照，指定设备验证后才可离线使用；下一次刷新会反映退款。签名协议版本为 2；测试夹具只含一次性测试公钥和虚构授权。
- 改用 ES256 是因为 Android 官方 `Signature` 文档仅在 API 33+ 支持 Ed25519，而项目最低支持 API 27；`SHA256withECDSA` 和 EC 公钥解析从 API 11 起可用。Android 的 `EntitlementVerifier` 已使用 Python 生成的测试授权做跨语言验证。
- 私钥必须由服务器外部密钥管理提供，正式公钥可以内置 App。不能把私钥、商户密钥或测试用的签名私钥固定到 APK/仓库。
- `python3 -m unittest discover -s commerce_server/tests -v` 运行核心测试。测试依赖 `cryptography`，见 `requirements.txt`。

## 爱发电接口预备（2026-11-19）

- `afdian.py` 已按[官方开发者文档](https://guide.afdian.com/creator/developer)实现服务端 `query-order` 签名、按订单号查询和固定会员方案核验。要求订单 `status=2`、常规方案 `product_type=0`、已绑定的爱发电用户 ID、方案 ID、实付金额精确匹配；只返回核验结果，不生成授权。
- 官方 Webhook 文档没有给出回调签名；因此 `webhook_order_hint` 只提取待查询的订单号。任何回调正文、App 上传的订单号或付款成功回跳都不能直接开通 Pro。漏回调时由服务端主动查单补偿；重复通知必须按爱发电订单号幂等处理。
- 爱发电订单由平台生成，**不能直接调用现有 `create_order` → `settle` 路径**：后者要求服务端先生成订单号，并将其与设备绑定。`AfdianOAuthClient` 已按[爱发电 OAuth2 文档](https://guide.afdian.com/creator/oauth2)准备授权地址、随机 `state` 和服务端授权码兑换。联调服务已提供一次性 `state`/票据与外部订单账本，关联 OAuth2 返回的 `user_id`；仅凭用户输入订单号不能领取别人的会员。OAuth2 仍需要平台提供 client ID/secret 和可信回调域名，当前尚未配置。
- 当前 `core.py` 的固定一年授权、设备 ID 绑定只是通用原型；爱发电 `month` 字段与退款后状态的语义还要按最终获批方案确认。公开文档仅写成功订单 Webhook 与查订单，未提供可依赖的退款/拒付事件规范；不能把“查不到退款字段”当作订单永不退款。退款核验、定期对账和到期计算明确后，才接通 `CommerceLedger` 或替换其订单模型。
- Rust 不是这条链路的必要条件。jasmine 把会员逻辑放在 Rust 核心是它的架构选择；这里应保留 Python 服务端处理密钥、查单和签发授权，Android 端只验证公钥签名。把服务端私钥或爱发电 API Token 放进 Rust/Android 库一样可以被提取，不能作为安全边界。

## 可联调原型（未部署）

`server.py` 以 FastAPI 提供独立会员接口；`afdian_store.py` 用 SQLite 持久化一次性 OAuth `state`/登录票据、会话、已核验订单和当前方案状态。所有票据与会话只保存 SHA-256 摘要。Android 的 `MembershipApiClient` 使用独立 OkHttp 客户端，不携带视频网站 Cookie，且验证服务端 P-256 签名后才返回有效会员状态。App 登录票据和会话存于 Android Keystore 加密的 no-backup 文件。**只有构建时同时配置服务 URL 与正式验签公钥，设置页才显示「发电会员」入口**；默认构建无入口，也没有运行时付费拦截。

Android 联调构建需要公开配置 `COMMERCE_API_BASE_URL=https://你的服务域名/` 和 `COMMERCE_PUBLIC_KEY_DER_B64=服务端 P-256 公钥的 DER Base64`，也可用 Gradle 属性 `commerce.api.url`、`commerce.public.key.der.base64`。这两个值会进入 APK；它们只能是公开的 URL/公钥，不能放 API Token、OAuth client secret 或签名私钥。入口位于「设置 → 发电会员」，可连接爱发电、返回 App 完成登录、刷新会员、必要时输入订单号和退出。打开界面时自动刷新；会话失效后清除本地令牌以便重新登录，断网时仍可在本机退出。浏览、播放及现有工具不因该入口改变权限。

| 接口 | 用途 |
|---|---|
| `POST /v1/auth/start` | App 提供安装设备 ID，获得爱发电授权地址和一次性登录票据 |
| `GET /v1/auth/afdian/callback` | 服务端核验 `state` 并兑换授权码，绑定爱发电用户 ID |
| `POST /v1/auth/complete` | App 轮询票据；授权成功后仅一次返回会话令牌 |
| `POST /v1/webhooks/afdian` | 收到回调后用官方 API 再查订单，按平台用户 ID 幂等入账；回调自报身份不被采信 |
| `POST /v1/membership/refresh` | 会话持有者提供自己的订单号，服务端查订单和当前赞助方案，再签发短期授权；已关联订单的换机用户可省略订单号 |
| `GET /v1/membership` | 读取当前短期签名授权，过期状态不被当作 Pro |
| `POST /v1/auth/logout` | 撤销会话 |

依赖：`pip install -r commerce_server/requirements.txt`；本地测试：`python3 -m unittest discover -s commerce_server/tests -v`。运行入口是 `uvicorn commerce_server.server:create_app_from_env --factory --no-access-log`。入口会要求 `COMMERCE_DB_PATH`、`COMMERCE_SIGNING_KEY_PEM`（文件路径）、`AFDIAN_CREATOR_USER_ID`、`AFDIAN_API_TOKEN`、`AFDIAN_OAUTH_CLIENT_ID`、`AFDIAN_OAUTH_CLIENT_SECRET`、`AFDIAN_OAUTH_REDIRECT_URI`、`AFDIAN_PRO_PLAN_ID`、`AFDIAN_PRO_AMOUNT_MINOR`，缺项直接启动失败。生产私钥和 API Token 只在服务器环境中配置，不写入仓库或 APK。OAuth 回调 URL 带一次性 `code` 和 `state`，反向代理、APM 和网关访问日志也必须去除查询参数。

这是**联调原型，不是可直接公网部署的支付服务**。上线前还需：可信 HTTPS 域名和正式凭据；API 入口限流与请求体大小限制；对 OAuth `state` 的会话/设备来源审计；监控、备份、密钥轮换；退款/拒付核验及停权规则；平台对当前产品和会员权益的业务答复。Webhook 已在二次查单后自动入账，App 也支持订单号补偿。当前 `query-sponsor` 只能按公开文档分页扫描，最多 50 页（约 1000 个赞助者）后失败关闭，正式规模需向平台确认可按用户查询或提供更高效的状态接口。已签发授权最长有效 1 小时，刷新后可反映当前方案失效；无法远程即时吊销离线快照。

## 部署路径（2026-11-20，待平台答复）

用户已经向爱发电发送产品形态、会员权益、退款核验和 OAuth2 接入问题，尚未收到明确受理答复；目前没有自有域名或服务器。现有 Firebase 项目 `han1meviewer-chuishui` 未启用计费。以下是部署选项，不代表已获准收款：

| 路径 | 当前前置条件 | 对现有原型的改造 |
|---|---|---|
| Firebase Functions + 托管数据库 | 用户为现有项目启用 Blaze 并绑定结算；取得爱发电认可的 HTTPS 回调域名 | Functions 不能依赖本地 SQLite 文件持久化，须迁移 `afdian_store.py` 到托管数据库并处理并发、备份和密钥。Firebase [官方文档](https://firebase.google.com/docs/functions/get-started)确认部署 Functions 要求 Blaze。 |
| Cloudflare Workers + D1 | Cloudflare 账号；确认爱发电是否接受 `*.workers.dev` 为 OAuth2 可信域名 | Workers 自带 HTTPS 子域名，D1 有免费试验额度，但需适配 Python 运行环境、D1 API、密钥和原子事务；不能把现有 FastAPI + `sqlite3` 原样部署。Cloudflare [文档](https://developers.cloudflare.com/workers/configuration/routing/workers-dev/)将 `workers.dev` 定位于个人/试验用途，生产推荐自有域名。 |
| 自有域名 + 持久化主机 | 购买域名与主机，设置 HTTPS、备份和监控 | 可最大程度复用 FastAPI + SQLite，但仍须完成上面的支付安全和运维清单。 |

当前不要求用户购买域名/服务器或开启 Firebase 计费。先等待爱发电确认产品准入、OAuth2 能否申请、可信域名要求和退款状态接口；若允许 `workers.dev`，可评估免费联调，正式收款仍需稳定域名与部署。取得答复后再选定平台并申请正式凭据，避免为未经受理的商品先投入基础设施。爱发电[官方 OAuth2 文档](https://guide.afdian.com/creator/oauth2)明确要求应用名称、可信域名和服务端授权码流程，未说明共享子域名是否可用。

## 商户获批后必须补齐

1. 按实际受理渠道提供官方托管收银台/固定价链接；若使用爱发电，订单号由平台生成，按已绑定的用户身份关联订单。确认会员时长、续费方式和最终商品价；历史价格研究的 ¥29/¥49 不是正式商品价。爱发电普通会员年付保存时实测最低 ¥30；用户确认后已创建 ¥30/年隐藏方案，详见方案记录。
2. 提供商户指定的交易核验方式：核验通知来源、商户账户、交易状态、金额、币种、订单号和时间，必要时向官方 API 再查询。**仅靠浏览器回跳、IP 地址或客户端传参不能开通授权。** 配置生产和沙箱完全隔离。
3. 建立 HTTPS API、认证、限流、数据库备份、密钥轮换、订单对账、拒付处理、日志脱敏、数据删除与合规隐私说明；补充集成及恶意请求测试。
4. Android 端增加购买入口、服务端试用激活、授权轮询和签名校验、离线宽限、换机/恢复、播放中不中断及本地数据导出保护。现有 `TrialResearchStore` 仅做研究，不能当真实授权。
5. 正式 release 使用长期保管的签名，并提供测试包签名到正式版的迁移路径；改变 README 当前免费/不商业化承诺前先发布明确的现有用户政策。

## 向收款服务商提供的业务事实

产品是第三方成人内容网站 Android 客户端，提供浏览、播放、翻译、整理与备份工具，不拥有或出售网站片源，也不是网站会员。经营主体注册在中国大陆。需先寻找确实接受这一主体及产品的合法持牌渠道，并明确询问：是否接受这一精确形态；大陆主体所需证件、结算地区和币种；是否需要网站授权或内容权利证明；一次性年度使用权的最低价/费率/固定费用；年龄验证、投诉处理和退款要求；测试账户、收银台与交易核验接口。没有书面受理答复前，不应接入真实支付。

参考（渠道当前规则，以商户审核答复为准）：
- CCBill：<https://ccbill.com/doc/list-of-countries-for-visa-and-mastercard-processing>；<https://ccbill.com/doc/flexforms-quick-start-guide>；<https://ccbill.com/doc/webhooks-user-guide>
- Segpay：<https://segpay.com/wp-content/uploads/2024/12/Adult_Content_Website_Checklist.pdf>（公开清单列出 US/EU/UK 主体条件，当前大陆主体不符合该公开清单）
- Android 算法支持：<https://developer.android.com/reference/java/security/Signature>、<https://developer.android.com/reference/java/security/KeyFactory>

## 经营主体需要提供的下一步信息

1. 向候选渠道如实提交产品说明：大陆主体、第三方成人内容客户端、仅出售软件高级工具使用权、站点内容不归本产品所有。取得**书面受理答复**和沙箱／生产接入文档；公开说明不能替代个案审核。
2. 获批后提供托管收银台、服务端交易核验和退款／拒付事件的官方规范，以及商户结算币种与最终单次年度售价。密钥只放自己的服务器密钥管理或环境变量，不在聊天中发送。
3. 确定可用的 HTTPS 域名和服务器部署环境、客服与退款处理主体、现有用户过渡政策。正式试用期须绑定可恢复且有滥用控制的账户身份；匿名安装 ID 只能用于测试，不能充当可靠账号或永久换机凭据。

获批账户、服务端身份和域名就绪前，现有代码继续不启用付费拦截，客户端不会出现虚假的“已付款”状态。
