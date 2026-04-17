#!/usr/bin/env bash
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export PATH="$PATH:$ANDROID_HOME/emulator:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin"

echo "正在启动 Android TV 模拟器..."
"$ANDROID_HOME/emulator/emulator" \
    -avd AndroidTV_API31 \
    -no-audio \
    -gpu host \
    -no-snapshot &

echo "等待模拟器启动（约 60 秒）..."
adb wait-for-device

echo "安装 Launcher APK..."
sleep 10
APK=$(find "$(dirname "$0")/app/build/outputs/apk/debug" -name "*.apk" 2>/dev/null | head -1)
if [ -n "$APK" ]; then
    adb install -r "$APK"
    echo "✓ APK 安装完成: $APK"
else
    echo "⚠ 未找到 APK，请先执行 ./gradlew assembleDebug"
fi
