#!/usr/bin/env bash
set -e
# ============================================================
#  Android TV 模拟器一键安装脚本
#  创建 API 31 的 Android TV x86_64 模拟器，在电脑上预览效果
# ============================================================

ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
AVD_NAME="AndroidTV_API31"

if [ ! -d "$ANDROID_HOME/cmdline-tools/latest/bin" ]; then
    echo "❌ 未找到 Android SDK。请先执行 ./setup_env.sh 安装开发环境。"
    exit 1
fi

export PATH="$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator"

echo "========================================"
echo " Step 1: 安装 Android TV 模拟器镜像"
echo " (API 31, x86_64, Google TV)"
echo "========================================"
yes | sdkmanager --licenses > /dev/null 2>&1 || true
sdkmanager \
    "system-images;android-31;android-tv;x86" \
    "emulator"

echo ""
echo "========================================"
echo " Step 2: 创建 Android TV AVD"
echo " AVD 名称: $AVD_NAME"
echo "========================================"
# 删除同名旧 AVD（如果存在）
avdmanager delete avd -n "$AVD_NAME" 2>/dev/null || true

echo "no" | avdmanager create avd \
    --name "$AVD_NAME" \
    --package "system-images;android-31;android-tv;x86" \
    --device "tv_1080p" \
    --force

echo ""
echo "========================================"
echo " Step 3: 配置 AVD（1080p TV 分辨率）"
echo "========================================"
AVD_CONFIG_DIR="$HOME/.android/avd/${AVD_NAME}.avd"
cat >> "$AVD_CONFIG_DIR/config.ini" << 'EOF'
hw.lcd.width=1920
hw.lcd.height=1080
hw.lcd.density=320
hw.keyboard=yes
hw.dPad=yes
EOF
echo "AVD 配置完成 ✓"

echo ""
echo "========================================"
echo " 安装完成！"
echo ""
echo " 启动模拟器："
echo "   $ANDROID_HOME/emulator/emulator -avd $AVD_NAME -no-audio"
echo ""
echo " 或运行快捷脚本："
echo "   ./run_emulator.sh"
echo "========================================"

# 生成快捷启动脚本
cat > "$(dirname "$0")/run_emulator.sh" << SCRIPT
#!/usr/bin/env bash
export ANDROID_HOME="\${ANDROID_HOME:-\$HOME/Android/Sdk}"
export PATH="\$PATH:\$ANDROID_HOME/emulator:\$ANDROID_HOME/platform-tools:\$ANDROID_HOME/cmdline-tools/latest/bin"

echo "正在启动 Android TV 模拟器..."
"\$ANDROID_HOME/emulator/emulator" \\
    -avd $AVD_NAME \\
    -no-audio \\
    -gpu host \\
    -no-snapshot &

echo "等待模拟器启动（约 60 秒）..."
adb wait-for-device

echo "安装 Launcher APK..."
sleep 10
APK=\$(find "\$(dirname "\$0")/app/build/outputs/apk/debug" -name "*.apk" 2>/dev/null | head -1)
if [ -n "\$APK" ]; then
    adb install -r "\$APK"
    echo "✓ APK 安装完成: \$APK"
else
    echo "⚠ 未找到 APK，请先执行 ./gradlew assembleDebug"
fi
SCRIPT
chmod +x "$(dirname "$0")/run_emulator.sh"
echo "已生成 run_emulator.sh 快捷启动脚本"
