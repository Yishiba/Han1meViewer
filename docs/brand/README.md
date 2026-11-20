# Han1meClient 图标

默认图标采用项目维护者提供的成年角色插画，原始图片在 [han1me-client-icon.jpg](han1me-client-icon.jpg)。没有使用网站标志或 fork 原图。

修改原图后，在仓库根目录安装 Pillow 并运行 `python3 scripts/generate-brand-icon.py`，再构建 Android。脚本会生成自适应图标前景和设置页预览；原图的构图经过轻微缩放与下移，使圆形图标保留人物的头发、面部和上半身。圆形与 Debug 图标由 `app/src/main/res/mipmap-anydpi-v26/` 的 XML 定义；通知和系统单色图标继续使用简化的播放列表图形，保证小尺寸可读。
