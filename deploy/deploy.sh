#!/usr/bin/env bash
# EC2에서 실행: 새 이미지로 교체 → 헬스체크 → 실패하면 직전 이미지로 롤백
# 사용: deploy.sh <이미지 태그(커밋 SHA)>
set -euo pipefail

NEW_TAG="$1"
DIR=/opt/banting
cd "$DIR"

# 같은 커밋의 운영 스크립트(백업·복원)와 systemd 타이머를 설치 → 백업 설정도 PR 머지만으로 반영
install_ops() {
  local raw="https://raw.githubusercontent.com/hanwooya361/banting/${NEW_TAG}/deploy"
  for f in backup.sh restore.sh; do
    curl -fsSL "$raw/$f" -o "$DIR/$f"
    chmod 700 "$DIR/$f"
  done
  for f in banting-backup.service banting-backup.timer; do
    curl -fsSL "$raw/$f" -o "/etc/systemd/system/$f"
  done
  systemctl daemon-reload
  systemctl enable --now banting-backup.timer > /dev/null
}
install_ops

# 외부 API 키 등 운영 비밀 값: AWS Parameter Store(SecureString) → .env
# 키는 저장소·GitHub·터미널 출력에 남지 않는다. EC2 역할은 /banting/prod/* 읽기만 가능
sync_secret() {
  local env_name="$1" param_name="$2" value
  value=$(aws ssm get-parameter --region ap-southeast-2 --name "$param_name" --with-decryption           --query Parameter.Value --output text)
  umask 077
  { grep -v "^${env_name}=" .env || true; echo "${env_name}=${value}"; } > .env.tmp
  mv .env.tmp .env
  chmod 600 .env
  echo "비밀 값 반영: ${env_name} (${#value}자)"
}
sync_secret KAKAO_REST_API_KEY /banting/prod/kakao-rest-api-key
sync_secret TOSS_SECRET_KEY /banting/prod/toss-secret-key

# 선택 비밀 값: 없어도 배포는 계속하고 그 기능만 꺼진다 (T23 간편 가입 키는 사용자가 나중에 넣음)
sync_optional_secret() {
  local env_name="$1" param_name="$2" value
  if value=$(aws ssm get-parameter --region ap-southeast-2 --name "$param_name" --with-decryption       --query Parameter.Value --output text 2>/dev/null); then
    umask 077
    { grep -v "^${env_name}=" .env || true; echo "${env_name}=${value}"; } > .env.tmp
    mv .env.tmp .env
    chmod 600 .env
    echo "비밀 값 반영: ${env_name} (${#value}자)"
  else
    { grep -v "^${env_name}=" .env || true; } > .env.tmp && mv .env.tmp .env && chmod 600 .env
    echo "선택 비밀 값 없음: ${env_name} (해당 기능 꺼짐)"
  fi
}
sync_optional_secret KAKAO_LOGIN_CLIENT_SECRET /banting/prod/kakao-login-client-secret
sync_optional_secret GOOGLE_CLIENT_ID /banting/prod/google-client-id
sync_optional_secret GOOGLE_CLIENT_SECRET /banting/prod/google-client-secret

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
