#!/usr/bin/env bash
set -e

# ============================================================
#  Android TV Launcher 开发环境一键安装脚本
#  目标设备: Sony BRAVIA 4K AE2 / Android 12 (API 31) / armeabi-v7a
#
#  ⚠ 磁盘策略：
#    - JDK 安装在系统盘（仅约 300MB）
#    - Android SDK 安装在 VMware 共享文件夹（181GB 可用）
# ============================================================

# Android SDK 安装在本地磁盘（腾出空间后足够使用）
ANDROID_HOME="$HOME/Android/Sdk"
CMDLINE_TOOLS_VERSION="11076708"

# 取消代理（直连 Google 下载服务器）
unset http_proxy https_proxy HTTP_PROXY HTTPS_PROXY
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip"
PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"

echo "========================================"
echo " Step 0: 清理系统垃圾，释放空间"
echo "========================================"
sudo apt-get clean
sudo apt-get autoremove -y -qq
sudo journalctl --vacuum-size=100M 2>/dev/null || true
echo "垃圾清理完成 ✓"
echo "当前可用空间: $(df -h / | awk 'NR==2{print $4}')"

echo ""
echo "========================================"
echo " Step 1: 安装 JDK 17"
echo "========================================"
sudo apt-get update -qq
sudo apt-get install -y openjdk-17-jdk wget unzip gradle
java -version
echo "JDK 17 安装完成 ✓"

echo ""
echo "========================================"
echo " Step 2: 下载 Android Command Line Tools"
echo " → 安装到: $ANDROID_HOME"
echo "========================================"
mkdir -p "$ANDROID_HOME/cmdline-tools"
cd /tmp
wget -q --show-progress -O cmdline-tools.zip "$CMDLINE_TOOLS_URL"
unzip -q -o cmdline-tools.zip -d "$ANDROID_HOME/cmdline-tools"
mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest" 2>/dev/null || true
rm -f /tmp/cmdline-tools.zip
echo "Android Command Line Tools 安装完成 ✓"

echo ""
echo "========================================"
echo " Step 3: 配置环境变量 (~/.bashrc)"
echo "========================================"
# 移除旧的 ANDROID_HOME 配置（如有）
if grep -q "ANDROID_HOME" ~/.bashrc; then
    sed -i '/# ---- Android SDK ----/,/JAVA_HOME/d' ~/.bashrc
fi

cat >> ~/.bashrc << 'ENVBLOCK'

# ---- Android SDK ----
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/build-tools/34.0.0:$ANDROID_HOME/emulator"
# ---- Java ----
export JAVA_HOME="$(dirname $(dirname $(readlink -f $(which java))))"
ENVBLOCK
echo "环境变量已写入 ~/.bashrc ✓"

export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/build-tools/34.0.0:$ANDROID_HOME/emulator"
export JAVA_HOME="$(dirname $(dirname $(readlink -f $(which java))))"

echo ""
echo "========================================"
echo " Step 4: 安装 SDK 组件 (API 31 + 构建工具)"
echo "========================================"
yes | sdkmanager --licenses > /dev/null 2>&1 || true
sdkmanager --install \
    "platform-tools" \
    "platforms;android-31" \
    "build-tools;34.0.0" \
    "extras;android;m2repository" \
    "extras;google;m2repository"
echo "Android SDK API 31 安装完成 ✓"

echo ""
echo "========================================"
echo " Step 5: 更新项目 local.properties"
echo "========================================"
echo "sdk.dir=$ANDROID_HOME" > "$PROJECT_DIR/local.properties"
echo "local.properties 已更新: sdk.dir=$ANDROID_HOME ✓"

echo ""
echo "========================================"
echo " Step 6: 生成 Gradle Wrapper"
echo " (下载 Gradle 8.6 用于生成 wrapper)"
echo "========================================"
cd /tmp
GRADLE_VER="8.6"
GRADLE_ZIP="gradle-${GRADLE_VER}-bin.zip"
if [ ! -f "/tmp/gradle-${GRADLE_VER}/bin/gradle" ]; then
    wget -q --show-progress "https://services.gradle.org/distributions/${GRADLE_ZIP}"
    unzip -q "${GRADLE_ZIP}"
fi
cd "$PROJECT_DIR"
/tmp/gradle-${GRADLE_VER}/bin/gradle wrapper --gradle-version ${GRADLE_VER} --distribution-type bin
chmod +x gradlew
rm -rf /tmp/gradle-${GRADLE_VER} /tmp/${GRADLE_ZIP} 2>/dev/null || true
echo "Gradle Wrapper 生成完成 ✓"

echo ""
echo "========================================"
echo " Step 7: 验证安装"
echo "========================================"
echo "Java 版本:"
java -version
echo ""
echo "Gradle 版本:"
./gradlew --version 2>/dev/null | head -5
echo ""
echo "系统盘剩余空间: $(df -h / | awk 'NR==2{print $4}')"
echo "共享盘剩余空间: $(df -h /mnt/hgfs | awk 'NR==2{print $4}')"

echo ""
echo "========================================"
echo " 开发环境搭建完成！"
echo ""
echo " 下一步："
echo "   # 构建 APK"
echo "   cd $PROJECT_DIR && ./gradlew assembleDebug"
echo ""
echo "   # 安装模拟器（可选）"
echo "   ./setup_emulator.sh"
echo ""
echo "   # 安装到模拟器/设备"
echo "   adb install -r app/build/outputs/apk/debug/app-debug.apk"
echo "========================================"
