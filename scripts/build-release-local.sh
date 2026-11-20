#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

if [ -n "$(git status --porcelain)" ]; then
  echo '工作区有未提交改动；请先核对，避免 APK 与 Git tag 指向不同源码。' >&2
  exit 1
fi

release_keystore="$HOME/.android/han1meviewer-release.jks"
legacy_keystore="${HAN1MEVIEWER_LEGACY_KEYSTORE_PATH:-$HOME/.android/han1meviewer-legacy-debug.keystore}"
if [ ! -f "$legacy_keystore" ] && [ -z "${HAN1MEVIEWER_LEGACY_KEYSTORE_PATH:-}" ]; then
  legacy_keystore="$HOME/.android/debug.keystore"
fi
lineage_path="$HOME/.android/han1meviewer-signing-lineage.bin"
for needed in "$release_keystore" "$legacy_keystore" "$lineage_path"; do
  if [ ! -f "$needed" ]; then
    echo "找不到签名材料：$needed" >&2
    exit 1
  fi
done

export KEYSTORE_PASSWORD
if ! KEYSTORE_PASSWORD=$(security find-generic-password -a Yishiba -s Han1meViewerReleaseKeystore -w 2>/dev/null); then
  if [ ! -t 0 ]; then
    echo '钥匙串中没有签名密码；请在交互式终端运行并从密码管理器输入。' >&2
    exit 1
  fi
  read -r -s -p 'Han1meViewer release 密钥密码：' KEYSTORE_PASSWORD
  printf '\n'
fi
export KEY_ALIAS='han1meviewer_release'
export RELEASE_KEYSTORE_PATH="$release_keystore"
export OLD_KEYSTORE_PASSWORD='android'
trap 'unset KEYSTORE_PASSWORD OLD_KEYSTORE_PASSWORD' EXIT

jdk_path='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
export JAVA_HOME="${RELEASE_JAVA_HOME:-$jdk_path}"
if [ ! -x "$JAVA_HOME/bin/keytool" ]; then
  echo '需要 JDK 21；请设置 RELEASE_JAVA_HOME。' >&2
  exit 1
fi
"$JAVA_HOME/bin/keytool" -list -alias "$KEY_ALIAS" -keystore "$release_keystore" -storepass:env KEYSTORE_PASSWORD >/dev/null

bash ./gradlew :app:assembleRelease --console=plain

metadata='app/build/outputs/apk/release/output-metadata.json'
read -r version_code version_name apk_name < <(python3 - "$metadata" <<'PY'
import json, sys
with open(sys.argv[1], encoding='utf-8') as source:
    data = json.load(source)
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
rotated_path="${apk_path%.apk}.rotated.apk"
sdk_path="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
apksigner=''
for candidate in "$sdk_path"/build-tools/*/apksigner; do
  if [ -x "$candidate" ]; then apksigner="$candidate"; fi
done
if [ -z "$apksigner" ]; then
  echo '找不到 Android SDK apksigner。' >&2
  exit 1
fi

"$apksigner" sign \
  --ks "$legacy_keystore" --ks-key-alias androiddebugkey \
  --ks-pass env:OLD_KEYSTORE_PASSWORD --key-pass env:OLD_KEYSTORE_PASSWORD \
  --next-signer --ks "$release_keystore" --ks-key-alias "$KEY_ALIAS" \
  --ks-pass env:KEYSTORE_PASSWORD --key-pass env:KEYSTORE_PASSWORD \
  --lineage "$lineage_path" --rotation-min-sdk-version 33 \
  --out "$rotated_path" --in "$apk_path"

legacy_digest='25ffea57792a71ac2a8fcbb5d338df44cabdd4a62d2896a5e885e3157d1b0d64'
release_digest='bbc987ea88753d153963350fd9c13f603ac7c6686900e034f9ab5b449e8d8d8c'
legacy_certs=$("$apksigner" verify --print-certs --min-sdk-version 27 --max-sdk-version 27 "$rotated_path")
modern_certs=$("$apksigner" verify --print-certs --min-sdk-version 33 --max-sdk-version 33 "$rotated_path")
if ! printf '%s\n' "$legacy_certs" | grep -q "certificate SHA-256 digest: $legacy_digest"; then
  echo 'Android 8 的签名与已安装用户不兼容，停止发布。' >&2
  exit 1
fi
if ! printf '%s\n' "$modern_certs" | grep -q "minSdkVersion=33.*certificate SHA-256 digest: $release_digest"; then
  echo 'Android 13+ 未使用自有正式签名，停止发布。' >&2
  exit 1
fi

mv "$rotated_path" "$apk_path"
printf 'APK: %s\n版本: %s (code %s)\nGit tag: v%s\n' "$apk_path" "$version_name" "$version_code" "$version_name"
shasum -a 256 "$apk_path"
echo 'Android 13+ 使用自有正式签名；Android 8–12 为保留原有安装数据，使用旧签名与密钥轮换证明。'
