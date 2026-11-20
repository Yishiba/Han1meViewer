#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

if [ -n "$(git status --porcelain)" ]; then
  echo "工作区有未提交改动；请先核对，避免 APK 与 Git tag 指向不同源码。" >&2
  exit 1
fi

default_debug_keystore="$HOME/.android/debug.keystore"
keystore_path="${RELEASE_KEYSTORE_PATH:-$default_debug_keystore}"
if [ ! -f "$keystore_path" ]; then
  echo "找不到现有签名文件：$keystore_path" >&2
  exit 1
fi

if [ "$keystore_path" = "$default_debug_keystore" ]; then
  KEYSTORE_PASSWORD="${KEYSTORE_PASSWORD:-android}"
  KEY_ALIAS="${KEY_ALIAS:-androiddebugkey}"
fi
export RELEASE_KEYSTORE_PATH="$keystore_path"

if [ -z "${KEYSTORE_PASSWORD:-}" ]; then
  read -rs -p '请输入现有 keystore 密码（不会显示）：' KEYSTORE_PASSWORD
  printf '\n'
fi
export KEYSTORE_PASSWORD
trap 'unset KEYSTORE_PASSWORD' EXIT

jdk_path='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
export JAVA_HOME="${JAVA_HOME:-$jdk_path}"
if [ ! -x "$JAVA_HOME/bin/keytool" ]; then
  echo "需要 JDK 21；请设置 JAVA_HOME。" >&2
  exit 1
fi

if [ -z "${KEY_ALIAS:-}" ]; then
  "$JAVA_HOME/bin/keytool" -list -keystore "$keystore_path" -storepass:env KEYSTORE_PASSWORD
  read -r -p '请输入上面列出的签名别名：' KEY_ALIAS
fi
export KEY_ALIAS
"$JAVA_HOME/bin/keytool" -list -alias "$KEY_ALIAS" -keystore "$keystore_path" -storepass:env KEYSTORE_PASSWORD >/dev/null

bash ./gradlew :app:assembleRelease --console=plain

metadata='app/build/outputs/apk/release/output-metadata.json'
read -r version_code version_name apk_name < <(python3 - "$metadata" <<'PY'
import json, sys
data = json.load(open(sys.argv[1], encoding='utf-8'))
assert data['applicationId'] == 'com.yenaly.han1meviewer'
assert len(data['elements']) == 1
apk = data['elements'][0]
print(apk['versionCode'], apk['versionName'], apk['outputFile'])
PY
)

if [ "$version_code" -le 26111903 ]; then
  echo "新版本号 $version_code 不高于现有手机版本 26111903。" >&2
  exit 1
fi

apk_path="app/build/outputs/apk/release/$apk_name"
sdk_path="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
apksigner=''
for candidate in "$sdk_path"/build-tools/*/apksigner; do
  if [ -x "$candidate" ]; then apksigner="$candidate"; fi
done
if [ -z "$apksigner" ]; then
  echo '找不到 Android SDK apksigner。' >&2
  exit 1
fi

signer_digest=$("$apksigner" verify --print-certs "$apk_path" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p' | head -1)
expected_digest='25ffea57792a71ac2a8fcbb5d338df44cabdd4a62d2896a5e885e3157d1b0d64'
if [ "$signer_digest" != "$expected_digest" ]; then
  echo '签名证书与现有手机不一致；停止发布，以免覆盖安装失败或丢数据。' >&2
  exit 1
fi

printf 'APK: %s\n版本: %s (code %s)\nGit tag: v%s\n' "$apk_path" "$version_name" "$version_code" "$version_name"
shasum -a 256 "$apk_path"
echo '签名与现有手机匹配。发布前仍须真机覆盖安装并检查数据。'
