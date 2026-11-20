#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

if [ -n "$(git status --porcelain)" ]; then
  echo '工作区有未提交改动；请先核对，避免 APK 与 Git tag 指向不同源码。' >&2
  exit 1
fi

release_keystore="$HOME/.android/han1meviewer-release.jks"
if [ ! -f "$release_keystore" ]; then
  echo "找不到自有正式签名密钥：$release_keystore" >&2
  exit 1
fi

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
trap 'unset KEYSTORE_PASSWORD' EXIT

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
assert data['applicationId'] == 'io.github.yishiba.han1meviewer'
assert len(data['elements']) == 1
apk = data['elements'][0]
print(apk['versionCode'], apk['versionName'], apk['outputFile'])
PY
)

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

release_digest='bbc987ea88753d153963350fd9c13f603ac7c6686900e034f9ab5b449e8d8d8c'
for api in 27 33; do
  certs=$("$apksigner" verify --print-certs --min-sdk-version "$api" --max-sdk-version "$api" "$apk_path")
  if ! printf '%s\n' "$certs" | grep -q "certificate SHA-256 digest: $release_digest"; then
    echo "Android API $api 未使用自有正式签名，停止发布。" >&2
    exit 1
  fi
done

printf 'APK: %s\n版本: %s (code %s)\nGit tag: v%s\n' "$apk_path" "$version_name" "$version_code" "$version_name"
shasum -a 256 "$apk_path"
echo '新包名在所有受支持系统上只使用自有正式签名；旧包留在手机上供备份迁移。'
