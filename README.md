# 星律回响 · ASTRA ECHO

Android 节奏音游（Kotlin + Jetpack Compose + 120fps `SurfaceView`）及配套网页版谱面编辑器。

## 项目结构

- `app/` — Android 客户端（4K / 5K / 6K 多轨道、可调 3D 透视倾角、5 套音符皮肤、本地音频/视频/抖音链接导入、自动制谱、手机端谱面编辑器、歌曲+谱面 `.zip` 打包导入导出）
- `web-editor/` — 零依赖网页版谱面编辑器（波形时间轴、全键型编辑、实时键盘录谱、1024 点多频段 FFT 自动制谱、3D 透视试玩预览、抖音链接导入、与 Android 端 100% 互通的 `.zip` 谱面包导出/导入）

## 快速启动

### Android 客户端
使用 Android Studio 打开本仓库根目录，或在命令行执行：
```bash
./gradlew :app:assembleDebug
```

### 网页版谱面编辑器
确保已安装 Node.js 18+，然后运行：
```bash
node web-editor/server.mjs
```
或在 Windows 下直接双击 `web-editor/启动网页编辑器.bat`，打开浏览器访问 `http://127.0.0.1:5173`。
