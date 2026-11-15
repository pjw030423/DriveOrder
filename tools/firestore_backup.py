"""Firestore 매장·주문 데이터를 내 PC에 백업합니다.

사용법 (프로젝트 루트에서):
    python tools/firestore_backup.py               # backup/<날짜-시각>/ 에 stores.json, orders.json 저장
    python tools/firestore_backup.py --update-seed # 위와 같이 저장하고, 서버의 매장 데이터로 시드 파일도 갱신

- backup/ 폴더는 git에 올리지 않습니다(.gitignore).
- --update-seed 는 seed/stores_seed.json 과 app/src/main/assets/stores_seed.json 을 서버 내용으로 바꿉니다.
  콘솔에서 매장·메뉴를 고친 뒤 실행하면, 앱이 다음에 매장을 올릴 때(서버가 비어 있을 때)도 고친 내용이 쓰입니다.
  시드가 바뀌면 커밋해야 저장소에 남습니다.
- 표준 라이브러리만 씁니다. 인증 없이 읽으므로, 보안 규칙에서 읽기를 막으면 동작하지 않습니다.
"""
import datetime
import json
import os
import re
import sys
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SEED = os.path.join(ROOT, 'seed', 'stores_seed.json')
ASSET = os.path.join(ROOT, 'app', 'src', 'main', 'assets', 'stores_seed.json')

STORE_KEYS = ['id', 'name', 'category', 'lat', 'lng', 'address', 'openHour', 'closeHour',
              'pickupAvailable', 'deliveryAvailable', 'isDemo', 'menus']
MENU_KEYS = ['id', 'name', 'price', 'cookMin', 'tags']


def project_id():
    """앱 코드(FirestoreRest.kt)에 적힌 프로젝트 ID를 그대로 씀 (한 곳에서만 관리)"""
    path = os.path.join(ROOT, 'app', 'src', 'main', 'java', 'com', 'example', 'myapplication', 'FirestoreRest.kt')
    m = re.search(r'PROJECT_ID\s*=\s*"([^"]+)"', open(path, encoding='utf-8').read())
    if not m:
        sys.exit('FirestoreRest.kt 에서 PROJECT_ID 를 찾지 못했습니다.')
    return m.group(1)


def from_value(v):
    kind, x = next(iter(v.items()))
    if kind == 'integerValue':
        return int(x)
    if kind == 'doubleValue':
        return float(x)
    if kind == 'arrayValue':
        return [from_value(i) for i in x.get('values', [])]
    if kind == 'mapValue':
        return {k: from_value(i) for k, i in x.get('fields', {}).items()}
    if kind == 'nullValue':
        return None
    return x  # stringValue, booleanValue, timestampValue …


def list_all(base, collection):
    docs, token = [], ''
    while True:
        url = f'{base}/{collection}?pageSize=300' + (f'&pageToken={token}' if token else '')
        with urllib.request.urlopen(url, timeout=30) as r:
            j = json.load(r)
        for d in j.get('documents', []):
            docs.append({k: from_value(v) for k, v in d.get('fields', {}).items()})
        token = j.get('nextPageToken', '')
        if not token:
            return sorted(docs, key=lambda d: str(d.get('id', '')))


def ordered(d, keys):
    """시드 파일과 같은 필드 순서로 (모르는 필드는 뒤에)"""
    return {**{k: d[k] for k in keys if k in d}, **{k: v for k, v in d.items() if k not in keys}}


def write_seed(path, comment, version, stores):
    """seed_expand 로 만든 시드 파일과 같은 모양으로 씀 (메뉴는 한 줄에 하나)"""
    out = ['{', '  "_comment": ' + json.dumps(comment, ensure_ascii=False) + ',',
           '  "version": ' + str(version) + ',', '  "stores": [']
    for i, st in enumerate(stores):
        out.append('    {')
        for k, v in st.items():
            if k != 'menus':
                out.append(f'      "{k}": ' + json.dumps(v, ensure_ascii=False) + ',')
        out.append('      "menus": [')
        out.append(',\n'.join('        { ' + json.dumps(m, ensure_ascii=False)[1:-1] + ' }' for m in st.get('menus', [])))
        out.append('      ]')
        out.append('    }' + (',' if i < len(stores) - 1 else ''))
    out += ['  ]', '}', '']
    open(path, 'w', encoding='utf-8', newline='\n').write('\n'.join(out))


def main():
    base = f'https://firestore.googleapis.com/v1/projects/{project_id()}/databases/(default)/documents'
    stores = list_all(base, 'stores')
    orders = list_all(base, 'orders')

    folder = os.path.join(ROOT, 'backup', datetime.datetime.now().strftime('%Y%m%d-%H%M%S'))
    os.makedirs(folder, exist_ok=True)
    for name, data in (('stores', stores), ('orders', orders)):
        with open(os.path.join(folder, f'{name}.json'), 'w', encoding='utf-8', newline='\n') as f:
            json.dump({name: data}, f, ensure_ascii=False, indent=2)
    print(f'백업 완료: 매장 {len(stores)}곳, 주문 {len(orders)}건 → {os.path.relpath(folder, ROOT)}')

    if '--update-seed' in sys.argv:
        if not stores:
            sys.exit('서버에 매장이 없어 시드를 바꾸지 않았습니다.')
        old = json.load(open(SEED, encoding='utf-8'))
        new_stores = [ordered({**s, 'menus': [ordered(m, MENU_KEYS) for m in s.get('menus', [])]}, STORE_KEYS)
                      for s in stores]
        before = open(SEED, encoding='utf-8').read()
        for path in (SEED, ASSET):
            write_seed(path, old.get('_comment', ''), old.get('version', 1), new_stores)
        changed = open(SEED, encoding='utf-8').read() != before
        print('시드 파일 갱신: ' + ('바뀐 내용이 있어요. 확인 후 커밋하세요.' if changed else '서버와 같아 바뀐 것이 없어요.'))


if __name__ == '__main__':
    main()
