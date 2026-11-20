#!/usr/bin/env bash
# T13 k6 실행기 (로컬 전용). 백엔드가 localhost:8090에 떠 있어야 한다.
#   사용: loadtest/run-last-seat.sh [라운드 수, 기본 10]
#   비교: 백엔드를 --banting.participation.seat-lock=PESSIMISTIC 으로 다시 띄워 한 번 더 실행
# 공구(남은 5자리)를 라운드 수만큼 DB에 직접 넣고 → k6 → 정원 초과 검사 → 이번 실행 데이터 삭제.
set -euo pipefail
cd "$(dirname "$0")"

ROUNDS="${1:-10}"
RUN="k6$(date +%s | tail -c 7)"
SQL="docker exec -i banting-mysql mysql -ubanting -pbanting-local banting -N"

sql() { $SQL -e "$1" 2>/dev/null; }

# 개설자 + 공구 (정원 6, 개설자 1자리 결제됨 → 남은 5자리)
sql "INSERT INTO users (email, password_hash, nickname, role, created_at, updated_at)
     VALUES ('${RUN}-host@load.test', 'x', '${RUN}h', 'USER', NOW(6), NOW(6))"
HOST=$(sql "SELECT id FROM users WHERE email = '${RUN}-host@load.test'")
IDS=()
for _ in $(seq 1 "$ROUNDS"); do
  ID=$(sql "INSERT INTO group_buys (host_id, title, description, category, product_url, price_type,
              total_price, total_quantity, unit_label, capacity_units, min_units_per_person,
              reserved_units, paid_units, status, location, neighborhood_name, deadline_at, created_at, updated_at)
            VALUES (${HOST}, '[k6] ${RUN}', 'load test', 'LIVING', 'https://example.com', 'EQUAL',
              6000, 6, '개', 6, 1, 1, 1, 'OPEN', ST_PointFromText('POINT(37.5665 126.978)', 4326), '중구 명동',
              NOW() + INTERVAL 1 DAY, NOW(6), NOW(6));
            SELECT LAST_INSERT_ID();")
  sql "INSERT INTO participations (group_buy_id, user_id, is_host, units, amount, status, expires_at, created_at, updated_at)
       VALUES (${ID}, ${HOST}, TRUE, 1, 1000, 'PAID', NOW() + INTERVAL 10 MINUTE, NOW(6), NOW(6))"
  IDS+=("$ID")
done
GB_IDS=$(IFS=,; echo "${IDS[*]}")
echo "실행 ${RUN}: 공구 ${ROUNDS}개 (${GB_IDS})"

cleanup() {
  sql "DELETE pay FROM payments pay JOIN participations p ON p.id = pay.participation_id
         JOIN group_buys g ON g.id = p.group_buy_id WHERE g.title = '[k6] ${RUN}';
       DELETE p FROM participations p JOIN group_buys g ON g.id = p.group_buy_id WHERE g.title = '[k6] ${RUN}';
       DELETE FROM group_buys WHERE title = '[k6] ${RUN}';
       DELETE r FROM refresh_tokens r JOIN users u ON u.id = r.user_id WHERE u.email LIKE '${RUN}-%';
       DELETE FROM users WHERE email LIKE '${RUN}-%'"
  echo "정리 완료 (${RUN})"
}
trap cleanup EXIT

K6_STATUS=0
k6 run --quiet -e RUN_ID="$RUN" -e GB_IDS="$GB_IDS" last-seat.js || K6_STATUS=$?

echo "── 정원 검사 (공구마다 잡힌 자리 = 6, 참여 행 합 = 잡힌 자리)"
sql "SELECT g.id, g.reserved_units, g.capacity_units,
            (SELECT SUM(units) FROM participations p WHERE p.group_buy_id = g.id
               AND p.status IN ('PENDING_PAYMENT','PAID')) AS row_units
     FROM group_buys g WHERE g.title = '[k6] ${RUN}'" | awk '
  { n++; if ($2 > $3 || $2 != $4) bad++ }
  END { printf "공구 %d개 중 정원 초과·불일치 %d건\n", n, bad+0; exit (bad > 0) }'
exit $K6_STATUS
