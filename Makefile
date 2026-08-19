# CollageApp APK 构建 Makefile
# 用法：
#   make            # 等价于 make debug
#   make debug      # 构建 debug APK（默认）
#   make release    # 构建 release APK（未开启混淆，无需签名即输出 unsigned）
#   make install    # 构建并安装 debug APK 到已连接设备
#   make clean      # 清理构建产物
#   make test       # 运行单元测试
#   make help       # 显示帮助

# 默认使用本机已安装的 Gradle（避免 wrapper 重新下载 gradle 分发包超时）。
# 如需改用 wrapper，可：make GRADLE=./gradlew
GRADLE ?= /home/lxs/opt/gradle/bin/gradle
# 构建变体（可覆盖，例如 make release FLAVOR=paid）
BUILD_TYPE ?= debug

.PHONY: all debug release install clean test help

all: debug

debug:
	$(GRADLE) assembleDebug

release:
	$(GRADLE) assembleRelease

install: debug
	$(GRADLE) installDebug

clean:
	$(GRADLE) clean

test:
	$(GRADLE) testDebugUnitTest

help:
	@echo "CollageApp 构建目标："
	@echo "  make           构建 debug APK（默认）"
	@echo "  make debug     构建 debug APK"
	@echo "  make release   构建 release APK"
	@echo "  make install   构建并安装 debug APK 到设备"
	@echo "  make clean     清理构建产物"
	@echo "  make test      运行单元测试"
	@echo "  make help      显示本帮助"
	@echo ""
	@echo "APK 输出位置："
	@echo "  debug:   app/build/outputs/apk/debug/app-debug.apk"
	@echo "  release: app/build/outputs/apk/release/app-release-unsigned.apk"
