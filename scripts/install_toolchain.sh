#!/usr/bin/env bash
# Installs JDK 21 + Android SDK command-line tools (idempotent).
set -euo pipefail
SDK_ROOT="${SDK_ROOT:-/opt/android-sdk}"
JDK_HOME=/usr/lib/jvm/java-21-openjdk-amd64
CMDLINE_ZIP="commandlinetools-linux-13114758_latest.zip"

if [ ! -x "$JDK_HOME/bin/java" ]; then
  echo "==> apt: openjdk-21"
  sudo apt-get update -qq
  sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq openjdk-21-jdk-headless unzip zip >/dev/null
fi
export JAVA_HOME="$JDK_HOME"; export PATH="$JAVA_HOME/bin:$PATH"
java -version 2>&1 | head -1

if [ ! -x "$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" ]; then
  echo "==> cmdline-tools"
  sudo mkdir -p "$SDK_ROOT"
  cd /tmp
  for url in "https://dl.google.com/android/repository/$CMDLINE_ZIP" "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"; do
    if curl -sSfL -o cml.zip "$url"; then echo "got $url"; break; fi
  done
  rm -rf /tmp/cmlx && mkdir -p /tmp/cmlx && unzip -q -o cml.zip -d /tmp/cmlx
  sudo rm -rf "$SDK_ROOT/cmdline-tools/latest"
  sudo mv /tmp/cmlx/cmdline-tools "$SDK_ROOT/cmdline-tools/latest"
  sudo chown -R "$(id -u):$(id -g)" "$SDK_ROOT"
fi
export ANDROID_HOME="$SDK_ROOT"; export ANDROID_SDK_ROOT="$SDK_ROOT"
export PATH="$SDK_ROOT/cmdline-tools/latest/bin:$SDK_ROOT/platform-tools:$PATH"

echo "==> licenses"
yes | sdkmanager --licenses >/dev/null 2>&1 || true
echo "==> packages"
sdkmanager --install "platform-tools" "platforms;android-35" "build-tools;35.0.0" 2>&1 | tail -3
echo "==> installed:"
ls "$SDK_ROOT/platforms" "$SDK_ROOT/build-tools" 2>&1
echo "==> TOOLCHAIN READY"
