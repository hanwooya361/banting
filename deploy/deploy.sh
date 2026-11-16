#!/usr/bin/env bash
# EC2에서 실행: 새 이미지로 교체 → 헬스체크 → 실패하면 직전 이미지로 롤백
# 사용: deploy.sh <이미지 태그(커밋 SHA)>
set -euo pipefail

NEW_TAG="$1"
DIR=/opt/banting
cd "$DIR"

PREV_TAG="$(cat .current_tag 2>/dev/null || true)"
echo "배포 시작: ${PREV_TAG:-없음} → ${NEW_TAG}"

health() {
  # Spring 기동에 1분 안팎 걸리므로 최대 약 2분 대기
  for _ in $(seq 1 40); do
    if curl -fs http://localhost/api/health > /dev/null 2>&1; then return 0; fi
    sleep 3
  done
  return 1
}

APP_TAG="$NEW_TAG" docker compose pull app
APP_TAG="$NEW_TAG" docker compose up -d

if health; then
  echo "$NEW_TAG" > .current_tag
  docker image prune -af --filter "until=72h" > /dev/null || true
  echo "배포 성공: ${NEW_TAG}"
  exit 0
fi

echo "헬스체크 실패: ${NEW_TAG}" >&2
docker logs --tail 50 banting-app >&2 || true
if [ -n "$PREV_TAG" ]; then
  echo "롤백: ${PREV_TAG}" >&2
  APP_TAG="$PREV_TAG" docker compose up -d app
  health && echo "롤백 성공: ${PREV_TAG}" >&2 || echo "롤백 후에도 헬스체크 실패" >&2
fi
exit 1
