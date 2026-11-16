#!/usr/bin/env bash
# S3 백업을 MySQL에 복원
# 사용: restore.sh [S3 키, 생략 시 최신] [대상 DB, 기본 banting_restore_test]
#  - 기본은 별도 DB에 복원해 내용만 확인 (운영 DB는 건드리지 않음)
#  - 운영 DB(banting)로 복원할 때는 먼저 앱을 멈춘다: docker compose stop app → restore.sh <키> banting → docker compose start app
set -euo pipefail
cd /opt/banting
set -a; . ./.env; set +a

BUCKET=banting-backup-150604516450
KEY="${1:-}"
TARGET_DB="${2:-banting_restore_test}"

if [ -z "$KEY" ]; then
  KEY=$(aws s3api list-objects-v2 --bucket "$BUCKET" --prefix mysql/ --region ap-southeast-2 \
        --query 'sort_by(Contents,&LastModified)[-1].Key' --output text)
fi
echo "복원: s3://${BUCKET}/${KEY} → ${TARGET_DB}"

mysql_root() { docker exec -i -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" banting-mysql mysql -uroot "$@"; }

mysql_root -e "CREATE DATABASE IF NOT EXISTS \`${TARGET_DB}\`"
aws s3 cp "s3://${BUCKET}/${KEY}" - --region ap-southeast-2 | gunzip | mysql_root "$TARGET_DB"

echo "복원된 테이블 수: $(mysql_root -N -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='${TARGET_DB}'")"
echo "Flyway 버전: $(mysql_root -N -e "SELECT MAX(version) FROM \`${TARGET_DB}\`.flyway_schema_history WHERE success = 1")"
