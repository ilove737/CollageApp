# CollageApp

一个 Android 拼图 / 海报制作应用，支持网格拼图、自由画布、海报、拼图四种模式，并内置基于 TFLite 的人像抠图（去背景）与手动精修功能。

## 功能特性

- **四种创作模式**
  - `grid` 网格拼图
  - `free` 自由画布（可任意拖拽、缩放、旋转图片）
  - `poster` 海报
  - `puzzle` 拼图
- **AI 人像抠图**：本地推理去除背景得到带透明通道的人像。
  - 默认使用 MODNet 大模型（`assets/modnet.tflite`，TFLite 推理、多线程提速）；模型缺失时自动回退 MediaPipe 轻量模型（`assets/selfie_segmenter.tflite`）。
  - 软阈值重映射 + 形态学边缘膨胀，减少袖子、发丝等易被误删的边缘被切掉。
  - 默认走 CPU 推理，稳定兼容各机型（GPU delegate 因算子兼容性问题已在代码中禁用）。
- **手动精修（橡皮擦 / 画笔）**：在 AI 抠图结果上叠加一层用户蒙版（User Mask），可
  - **橡皮擦**：擦除多余背景
  - **画笔**：补回被误删的袖子、发丝等
  - 支持软边笔刷、笔刷大小调节、一键清空、完成保存。
- **画板背景色**：预设一套浅色 / 粉彩调色板（默认暖米色），支持自动换行排布与 RGB 自定义颜色。
- **排列与图层管理**：对齐（左 / 右 / 顶 / 底 / 水平居中 / 垂直居中）、水平 / 垂直分布、置顶、置底、上移、下移，均为纯图标操作。
- **左侧图层列表**：实时展示画布元素，当前选中项高亮显示，锁定元素带 🔒 标志。
- 图片滤镜、蒙版形状、透明度、锁定、替换、裁剪等编辑能力。
- 一键合成导出为图片。

## 运行环境

| 项目 | 要求 |
| --- | --- |
| 平台 | Android 7.0+（minSdk 24） |
| 构建工具 | Android Gradle Plugin + Kotlin |
| compileSdk / targetSdk | 35 |
| 架构 | 仅 `arm64-v8a`（真机 64 位） |
| 依赖 | MediaPipe Tasks-Vision、TensorFlow Lite（含可选 GPU delegate） |

## 快速开始

### 使用 Makefile 构建

项目中提供了 `Makefile`，默认使用本机已安装的 Gradle（可在 `Makefile` 中通过 `GRADLE` 变量覆盖为 `./gradlew`）。

```bash
make            # 构建 debug APK（默认）
make debug      # 构建 debug APK
make release    # 构建 release APK（未开启混淆，输出 unsigned）
make install    # 构建并安装 debug APK 到已连接设备
make clean      # 清理构建产物
make test       # 运行单元测试
make help       # 显示帮助
```

APK 输出位置：
- debug：`app/build/outputs/apk/debug/app-debug.apk`
- release：`app/build/outputs/apk/release/app-release-unsigned.apk`

### 使用 Gradle 构建

```bash
./gradlew assembleDebug      # 或 assembleRelease
./gradlew installDebug       # 安装到设备
```

> 仓库使用阿里云 Maven 镜像（`settings.gradle.kts`）以加速依赖下载。

## 项目结构

```
CollageApp/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── java/com/example/collage/
│       │   ├── MainActivity.kt        # 主界面、选图、属性面板、导出
│       │   ├── FreeCanvasView.kt      # 画布渲染、交互、蒙版绘制与合成
│       │   ├── CanvasElement.kt       # 画布元素模型（含 ImageElement.userMask）
│       │   ├── ModnetSegmenter.kt      # TFLite 抠图推理 + 后处理
│       │   ├── Segmenter.kt           # 抠图接口/封装
│       │   ├── ImageEffects.kt        # 滤镜、蒙版形状
│       │   ├── CropOverlayView.kt     # 裁剪框视图
│       │   └── ...
│       ├── res/                       # 布局、字符串、颜色、图标、XML 配置
│       │   ├── layout/                # activity_main、bottom_sheet_board、dialog_crop 等
│       │   ├── drawable/              # 功能图标（对齐/分布/图层等）+ 背景/选中态
│       │   ├── values/                # 颜色、主题、字符串常量
│       │   ├── values-night/          # 深色模式下颜色 / 主题覆盖
│       │   └── xml/                   # FileProvider 路径配置
│       └── assets/
│           ├── modnet.tflite          # MODNet 抠图大模型（TFLite 默认模型）
│           └── selfie_segmenter.tflite # MediaPipe 轻量回退模型
├── settings.gradle.kts
├── build.gradle.kts
├── gradlew / gradle/wrapper           # Gradle Wrapper（可用 make GRADLE=./gradlew）
└── Makefile
```

## 使用说明

1. 选择创作模式（网格 / 自由 / 海报 / 拼图）。
2. 从相册添加图片到画布，可拖拽、缩放、旋转。
3. 双击 / 选中图片后，在底部 **属性面板** 切换：
   - **操作**：替换、裁剪、滤镜、蒙版、抠图、手动修正
   - **排列**：对齐、分布、置顶 / 置底 / 上移 / 下移（纯图标）
   - **属性**：不透明度、锁定、画板尺寸与背景色（预设浅色调色板 / 自定义 RGB）
4. 左侧 **图层列表** 可点击切换选中元素；当前选中项高亮，锁定元素带 🔒 标志。
5. 选中图片后点击 **「AI 抠图」** 去除背景（MODNet 为主，缺失时自动回退 MediaPipe 轻量模型）。
6. 抠图完成后点击 **「手动修正」**，使用橡皮擦 / 画笔对边缘精修，拖动笔刷大小滑条调整笔触。
7. 点击 **「完成」** 保存精修结果（**「清空」** 可恢复 AI 原始结果）。
8. 点击合成 / 导出按钮，将结果保存为图片（手动精修的蒙版会一并应用到导出结果中）。

## 已知限制 / 说明

- 抠图默认 CPU 推理；GPU delegate 依赖已引入但当前因模型算子兼容性问题未启用。
- 用户蒙版以独立图层叠加，导出时与 AI 蒙版合并生效。
- 模型文件较大，建议保持 `arm64-v8a` 单一架构以控制 APK 体积。

## 许可证

仅供学习 / 演示用途。
