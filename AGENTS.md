# CollageApp 项目指南

## 构建命令
- `make` 或 `make debug`：构建 debug APK
- `make release`：构建 release APK（未混淆，输出 unsigned）
- `make install`：构建并安装 debug APK 到设备
- `make test`：运行单元测试（等价于 `./gradlew testDebugUnitTest`）
- `make clean`：清理构建产物
- 使用系统 Gradle：`make GRADLE=gradle`；使用 wrapper：`make GRADLE=./gradlew`

## 架构约束
- 仅支持 `arm64-v8a` 架构（控制 APK 体积，避免 MediaPipe 原生库膨胀）
- compileSdk/targetSdk = 35，minSdk = 24
- JVM 目标：Java 17

## 依赖管理
- 使用阿里云 Maven 镜像加速下载（配置在 `settings.gradle.kts`）
- 关键依赖：
  - MediaPipe Tasks-Vision 0.10.35（人像分割）
  - TensorFlow Lite 2.14.0（人像抠图，含可选 GPU delegate）
- GPU delegate 已引入但因模型算子兼容性问题未启用，默认走 CPU 推理

## 项目结构
- 主要代码：`app/src/main/java/com/example/collage/`
- 关键文件：
  - `MainActivity.kt`：主界面、选图、属性面板、导出
  - `FreeCanvasView.kt`：画布渲染、交互、蒙版绘制与合成
  - `ModnetSegmenter.kt`：TFLite 抠图推理 + 后处理
- 模型文件：`app/src/main/assets/modnet.tflite`

## 测试
- 单元测试：`make test` 或 `./gradlew testDebugUnitTest`
- 无 CI 配置，测试需本地运行

## 开发注意事项
- APK 输出位置：
  - debug：`app/build/outputs/apk/debug/app-debug.apk`
  - release：`app/build/outputs/apk/release/app-release-unsigned.apk`
- `local.properties` 包含本地 SDK 路径，已 gitignore
- 构建缓存和产物在 `.gradle/` 和 `build/` 目录，已 gitignore