# Han1meClient 图标

默认图标是本项目原创的成年二次元角色：深色长发、播放发夹和俏皮表情。SVG 原稿在 [han1me-client-icon.svg](han1me-client-icon.svg)。没有使用网站标志或 fork 原图。

修改 SVG 后，在仓库根目录运行 `python3 scripts/generate-brand-icon.py`，再构建 Android。脚本会同步生成桌面图标的透明前景和设置页预览。深色背景、圆形与 Debug 自适应图标由 `app/src/main/res/mipmap-anydpi-v26/` 的 XML 定义；通知和系统单色图标使用简化的播放列表图形，保证小尺寸可读。
