"""백업해 둔 매장 데이터를 Firestore에 다시 올립니다.

사용법 (프로젝트 루트에서):
    python tools/firestore_restore.py                              # seed/stores_seed.json 을 올림
    python tools/firestore_restore.py backup/20261115-170000/stores.json   # 특정 백업을 올림

- 같은 id의 매장은 덮어쓰고, 파일에 없는 매장은 지우지 않습니다.
- 주문(orders)은 되돌리지 않습니다 (테스트·시연 기록이라 복구할 필요가 없음).
- firestore.rules 를 적용하면 매장 쓰기가 막혀 이 스크립트는 403으로 실패합니다.
  그때는 Firebase 콘솔에서 고치거나, 규칙의 stores 쓰기를 잠깐 허용한 뒤 실행하고 다시 막으세요.
"""
import json
import os
import sys
import urllib.error
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from firestore_backup import ROOT, SEED, project_id  # noqa: E402


def to_value(v):
    if isinstance(v, bool):
        return {'booleanValue': v}
    if isinstance(v, int):
        return {'integerValue': str(v)}
    if isinstance(v, float):
        return {'doubleValue': v}
    if isinstance(v, str):
        return {'stringValue': v}
    if isinstance(v, list):
        return {'arrayValue': {'values': [to_value(x) for x in v]}}
    if isinstance(v, dict):
        return {'mapValue': {'fields': {k: to_value(x) for k, x in v.items()}}}
    if v is None:
        return {'nullValue': None}
    raise TypeError(type(v))


def main():
    path = os.path.join(ROOT, sys.argv[1]) if len(sys.argv) > 1 else SEED
    stores = json.load(open(path, encoding='utf-8'))['stores']
    base = f'https://firestore.googleapis.com/v1/projects/{project_id()}/databases/(default)/documents/stores'
    ok = 0
    for s in stores:
        body = json.dumps({'fields': {k: to_value(x) for k, x in s.items()}}).encode('utf-8')
        req = urllib.request.Request(f'{base}/{s["id"]}', data=body, method='PATCH',
                                     headers={'Content-Type': 'application/json'})
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                ok += r.status == 200
        except urllib.error.HTTPError as e:
            sys.exit(f'{s["id"]} 업로드 실패: HTTP {e.code} (보안 규칙이 매장 쓰기를 막고 있을 수 있어요)')
    print(f'복원 완료: {ok}/{len(stores)}곳 ({os.path.relpath(path, ROOT)})')


if __name__ == '__main__':
    main()
