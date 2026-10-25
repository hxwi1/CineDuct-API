# promo

宣传片的动画源码和逐帧渲染管线。跟 mod 源码无关，改这里不影响构建。

## 目录

```
promo/
├─ src/
│  ├─ index.html      舞台：1920×1080 固定画布
│  ├─ style.css       设计 token、背景、印章与管线两个镜头的样式
│  ├─ app.js          时间轴核心：缓动、__seek、镜头注册
│  └─ scenes/
│     ├─ seal.js      镜头：全网首发印章（0–5.2s）
│     └─ pipeline.js  镜头：零拷贝第一拍（5.2–11.6s）
├─ capture/
│  ├─ render.mjs      起本地服务 + Chrome headless 逐帧截图（零依赖）
│  └─ encode.ps1      ffmpeg 编码，自动挑 h264_nvenc / h264_mf / libopenh264
└─ out/
   ├─ frames/         截图序列 + manifest.json
   └─ *.mp4           成片
```

## 怎么跑

```powershell
# 全部帧（48.8 秒、30fps、1464 帧，约 6.5 分钟）
node promo/capture/render.mjs --fps=30 --from=0 --to=11600

# 抽帧看画面（每 12 帧取一帧）
node promo/capture/render.mjs --fps=30 --from=0 --to=11600 --step=12 --dir=out/test

# 只渲一段（改一处不用重跑全片）
node promo/capture/render.mjs --fps=30 --from=5200 --to=8000

# 编码
powershell -File promo/capture/encode.ps1 -Frames out/frames -Fps 30 -Out out/sample-1080p30.mp4
powershell -File promo/capture/encode.ps1 -Frames out/frames -Out out/preview-720p.mp4 -Preview
```

参数都在 `render.mjs` 顶上读，`--chrome` 可以换 Chrome 路径，`--port` 换调试端口。

## 规则

- 动画只能由 `window.__seek(tMs)` 决定状态。不要用 CSS transition、不要用 setTimeout 链、不要读 `Date.now()`，否则逐帧渲染会抖。
- 每个镜头导出 `start` / `end` / `root` / `render(tLocal, duration)`，在 `app.js` 里注册。
- 字幕走 `.sub-line`，基线在 y≈890；画面底部 96px 内不要放东西（B 站播放器控件会压住）。
- 字体目前用系统字体兜底，正式版换 Noto Sans SC + JetBrains Mono 本地打包（见 [VIDEO-PROMO-PLAN.md](VIDEO-PROMO-PLAN.md)）。
- **不要用 PowerShell 的 Get-Content / Set-Content 改这些源码**。PS 5.1 会拿 ANSI 读 UTF-8 文件，中文变乱码，甚至把后面的 `<` 吞掉，还得整文件重写。用编辑器或 write 直接写。

## 现状

- 管线可用：Chrome 155 headless，抓帧 ~3.7 fps，1920×1080 PNG；48.8 秒的样片约 6.5 分钟渲完。
- 帧号按绝对时间算（`f_00900` 永远是第 30 秒），分段渲染不会互相覆盖。
- 静态服务已经关掉缓存，并给 CDP 设了 `cacheDisabled`——改完 js/css 忘了清缓存会渲出旧画面，这个坑踩过一次。
- 已做：开场、全网首发印章、贴链接就播（MC 界面 + 指令 + 屏幕升起 + 开始播）、老做法（进程内来回两趟）、零拷贝三拍（解码就在显存 / 跨进程只递句柄和锁、剪断老路 / GPU 内转 BGRA8、槽位 33MB→8MB、屏幕亮）。
- 图标用 Lucide（ISC）的 lock / lock-open 路径，内联 SVG，不用图标字体。
- 字幕逐句推进，一句一步；台词在各自 scene 文件的 `subs` 数组里。
- 未做：8K 实测、对比表、快闪、安装、片尾。
- 音轨、字幕文件、封面还没开始。
