#!/bin/bash
# 고정 주소(woosunguk.github.io/taspa)가 가리키는 터널 주소를 갱신한다.
# 사용법: deploy/update-tunnel.sh https://<새주소>.trycloudflare.com
# QR 은 고정 주소를 담고 있으므로 다시 만들 필요가 없다 — 이 스크립트만 실행하면 된다.
set -euo pipefail
TUNNEL="${1:?사용법: deploy/update-tunnel.sh https://xxx.trycloudflare.com}"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
git -C "$WORK" clone -q --branch gh-pages --depth 1 git@github.com:woosunguk/taspa.git site
render() { # $1=출력파일 $2=목적지경로 $3=화면제목
  cat > "$WORK/site/$1" <<HTML
<!doctype html><meta charset="utf-8">
<title>taspa — 이동 중</title>
<meta http-equiv="refresh" content="0; url=$TUNNEL$2">
<style>body{font-family:'Apple SD Gothic Neo',sans-serif;background:#0e2a1c;color:#f3f7ea;display:flex;align-items:center;justify-content:center;height:100vh;margin:0}a{color:#8fd6ad}</style>
<p>$3 로 이동 중… 자동으로 넘어가지 않으면 <a href="$TUNNEL$2">여기</a>를 누르세요.</p>
HTML
}
render index.html "" "taspa 데모"
mkdir -p "$WORK/site/pos"
render pos/index.html "/pos" "POS 단말"
git -C "$WORK/site" add -A
git -C "$WORK/site" commit -q -m "터널 주소 갱신: $TUNNEL" || { echo "변경 없음(이미 최신)"; exit 0; }
git -C "$WORK/site" push -q origin gh-pages
echo "갱신 완료 → https://woosunguk.github.io/taspa/ (반영까지 ~1분)"
