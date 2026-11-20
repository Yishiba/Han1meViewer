#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

config_path="${1:-app/google-services.json}"
python3 - "$config_path" <<'PY'
import json
import pathlib
import sys

source_roots = [pathlib.Path('app/src/main') / part for part in ('java', 'res', 'assets')]
for root in source_roots:
    for path in root.rglob('*'):
        if path.suffix not in {'.kt', '.java', '.xml', '.json', '.txt', '.html'}:
            continue
        source = path.read_text(encoding='utf-8', errors='replace').lower()
        if any(old in source for old in (
            'github.com/yenalyliew/han1meviewer',
            'api.github.com/repos/yenalyliew/han1meviewer',
            'github.com/mlr111/han1meviewer',
            'api.github.com/repos/mlr111/han1meviewer',
            'github.com/misaka10032w/han1meviewer',
            'api.github.com/repos/misaka10032w/han1meviewer',
            'raw.githubusercontent.com/yenalyliew/han1meviewer',
            'raw.githubusercontent.com/mlr111/han1meviewer',
            'raw.githubusercontent.com/misaka10032w/han1meviewer',
            'han1meviewer-86e5f',
        )):
            raise SystemExit(f'运行时源码仍引用原项目服务地址：{path}')

constants = pathlib.Path('app/src/main/java/com/yenaly/han1meviewer/Constants.kt').read_text(encoding='utf-8')
if not all(owned in constants for owned in (
    'https://api.github.com/repos/Yishiba/Han1meViewer/',
    'https://han1meviewer-chuishui-default-rtdb.asia-southeast1.firebasedatabase.app/',
)):
    raise SystemExit('自有 GitHub 更新地址或 Firebase 公告库地址缺失，停止构建。')

config = pathlib.Path(sys.argv[1])
if not config.is_file():
    raise SystemExit(f'找不到 Firebase 配置：{config}')
data = json.loads(config.read_text(encoding='utf-8'))
project_id = data.get('project_info', {}).get('project_id')
packages = {
    client.get('client_info', {}).get('android_client_info', {}).get('package_name')
    for client in data.get('client', [])
}
expected = {'io.github.yishiba.han1meviewer', 'io.github.yishiba.han1meviewer.debug'}
if project_id != 'han1meviewer-chuishui' or not expected.issubset(packages):
    raise SystemExit('Firebase 项目或自有 Android 包名不匹配，停止构建。')
print('自有 GitHub、公告库和 Firebase 客户端配置已核对。')
PY
