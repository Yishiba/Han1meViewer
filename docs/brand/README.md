# Han1meClient 图标

默认图标采用项目维护者提供的成年角色插画，原始图片在 [han1me-client-icon.jpg](han1me-client-icon.jpg)。没有使用网站标志或 fork 原图。

修改原图后，在仓库根目录安装 Pillow 并运行 `python3 scripts/generate-brand-icon.py`，再构建 Android。脚本会生成自适应图标前景和设置页预览；原图的构图经过轻微缩放与下移，使圆形图标保留人物的头发、面部和上半身。圆形与 Debug 图标由 `app/src/main/res/mipmap-anydpi-v26/` 的 XML 定义；通知和系统单色图标继续使用简化的播放列表图形，保证小尺寸可读。

启动画面使用维护者单独提供的竖版人物插画，位于 `app/src/main/res/drawable-nodpi/han1me_launch_art.jpg`；它与方形应用图标是两张独立图片。Android 12 及以上的系统启动画面无法铺满整张图片，因此系统阶段仅显示深蓝底色并隐藏居中图标；应用首帧再以 `ContentScale.Crop` 铺满插画。首页加载时继续显示，且至少展示 700 毫秒；内容成功或失败后淡出。首次使用须知也以插画为全屏背景，约 700 毫秒后展示底部须知，接受按钮等待 5 秒后可用，暂停再返回不会重置倒计时。
