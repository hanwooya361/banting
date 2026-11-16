#!/usr/bin/env bash
# MySQL 덤프 → S3. systemd timer(banting-backup.timer)가 매일 04:00(KST) 실행, 꺼져 있어 놓치면 켜질 때 실행
# 보관: S3 수명 주기 규칙이 14일 지난 객체 삭제. EC2 역할은 이 버킷에 쓰기·읽기만 가능(삭제 불가)
set -euo pipefail
export TZ=Asia/Seoul

cd /opt/banting
set -a; . ./.env; set +a

BUCKET=banting-backup-150604516450
KEY="mysql/$(date +%Y/%m)/banting-$(date +%Y%m%d-%H%M%S).sql.gz"

# --single-transaction: 테이블을 잠그지 않고 일관된 시점으로 덤프 (InnoDB)
# --no-tablespaces: 일반 계정에 없는 PROCESS 권한 요구를 피함
docker exec -e MYSQL_PWD="$MYSQL_PASSWORD" banting-mysql \
  mysqldump -ubanting --single-transaction --no-tablespaces --routines --triggers banting \
  | gzip \
  | aws s3 cp - "s3://${BUCKET}/${KEY}" --region ap-southeast-2 --only-show-errors

echo "백업 완료: s3://${BUCKET}/${KEY}"
