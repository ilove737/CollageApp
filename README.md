# CollageApp

一个 Android 拼图 / 海报制作应用，支持网格拼图、自由画布、海报、拼图四种模式，并内置基于 TFLite 的人像抠图（去背景）与手动精修功能。

## 功能特性

- **四种创作模式**
  - `grid` 网格拼图
  - `free` 自由画布（可任意拖拽、缩放、旋转图片）
  - `poster` 海报
  - `puzzle` 拼图
- **AI 人像抠图**：基于 TFLite 的 MODNet / RMBG 模型（`assets/modnet.tflite`）本地推理，去除背景得到带透明通道的人像。
  - 软阈值重映射 + 形态学边缘膨胀，减少袖子、发丝等易被误删的边缘被切掉。
  - 默认走 CPU 推理，稳定兼容各机型（GPU delegate 因算子兼容性问题已在代码中禁用）。
- **手动精修（橡皮擦 / 画笔）**：在 AI 抠图结果上叠加一层用户蒙版（User Mask），可
  - **橡皮擦**：擦除多余背景
  - **画笔**：补回被误删的袖子、发丝等
  - 支持软边笔刷、笔刷大小调节、一键清空、完成保存。
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
│       │   └── ...
│       ├── res/                       # 布局、字符串、图标等
│       └── assets/
│           └── modnet.tflite          # 人像抠图模型
├── settings.gradle.kts
├── build.gradle.kts
└── Makefile
```

## 使用说明

1. 选择创作模式（网格 / 自由 / 海报 / 拼图）。
2. 从相册添加图片到画布，可拖拽、缩放、旋转。
3. 选中图片后点击 **「AI 抠图」** 去除背景。
4. 抠图完成后点击 **「手动修正」**，使用橡皮擦 / 画笔对边缘进行精修，拖动笔刷大小滑条调整笔触。
5. 点击 **「完成」** 保存精修结果（**「清空」** 可恢复 AI 原始结果）。
6. 点击合成 / 导出按钮，将结果保存为图片（手动精修的蒙版会一并应用到导出结果中）。

## 已知限制 / 说明

- 抠图默认 CPU 推理；GPU delegate 依赖已引入但当前因模型算子兼容性问题未启用。
- 用户蒙版以独立图层叠加，导出时与 AI 蒙版合并生效。
- 模型文件较大，建议保持 `arm64-v8a` 单一架构以控制 APK 体积。

## 许可证

仅供学习 / 演示用途。
