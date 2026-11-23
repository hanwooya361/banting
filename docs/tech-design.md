# 반띵 기술 설계

> 2026-11-16 T05에서 ERD 확정 (Flyway `V1__init.sql`). 이후 스키마 변경은 이 문서를 먼저 고치고 새 마이그레이션(V2…)으로만 한다.

## 1. 아키텍처
> 2026-11-16 변경: AWS 새 가입 방식(Builder ID 프로젝트)의 무료 플랜으로 확정. 리전은 시드니(ap-southeast-2) 고정, 도메인은 사지 않는다. 크레딧 100달러로 6개월(2027-05-16)을 버티기 위해 RDS 대신 EC2 안 MySQL을 쓴다. Python AI 서버는 두지 않는다.

```
사용자 ─ https://xxxx.cloudfront.net ─→ CloudFront ─┬─ /*      → S3 (React 빌드 결과)
                                                   └─ /api/* → EC2 [Spring Boot + MySQL, Docker Compose]
                                                                  ├→ S3 (상품 사진, presigned URL)
                                                                  ├→ 토스페이먼츠 (결제 승인·취소)
                                                                  └→ Sentry (에러)
```

- 주소는 CloudFront 기본 주소 하나. HTTPS는 CloudFront가 기본 제공하므로 인증서 발급이 필요 없다
- 화면과 API가 같은 주소라 CORS 설정이 필요 없다 (로컬 개발은 Vite 프록시)
- CloudFront → EC2 구간은 HTTP(80). EC2 보안 그룹은 CloudFront 관리형 접두사 목록에서 오는 80만 허용하고, SSH(22)는 열지 않는다. 서버 접속과 배포 명령은 SSM(Session Manager·Run Command)으로 한다 (2026-11-16 변경, GitHub Actions IP가 계속 바뀌어 SSH를 좁게 열 수 없음)
- CloudFront 오리진에는 EC2 퍼블릭 DNS를 쓴다. EC2를 중지·시작하면 주소가 바뀌므로 탄력적 IP를 붙인다 (퍼블릭 IPv4 요금이 크레딧에서 차감되는 점 감안)
- `/api/*` 동작은 캐시 끄기(CachingDisabled), 모든 헤더·쿠키·쿼리 전달. 정적 파일만 캐시
- React 라우트 새로고침은 기본 동작(`/*`)에만 붙인 CloudFront Function이 `/index.html`로 바꿔 처리한다. 오류 응답 대체(404 → index.html)는 배포 전체에 걸려 API 404까지 HTML이 되므로 쓰지 않는다
- 개발 기간에는 EC2를 쓸 때만 켠다 (`scripts/ec2-start.ps1`, `ec2-stop.ps1`). 꺼져 있을 때 백엔드 배포는 이미지 push까지만 하고 경고와 함께 건너뛰며, 켜기 스크립트가 최신 이미지로 맞춘다
- MySQL은 EC2 안 Docker 볼륨에 저장하고, 하루 1회 mysqldump를 S3로 백업
- 사진용 S3 버킷과 프론트용 S3 버킷은 분리 (T08: `banting-images-*`, CloudFront `/images/*`로 같은 주소에서 서비스)
- 새 가입 방식은 허용된 서비스만 쓸 수 있으므로, T04 시작 전에 EC2·S3·CloudFront 사용 가능 여부를 콘솔에서 먼저 확인
- 배포 (GitHub Actions, 바뀐 폴더만)
  - `frontend/**` 변경: 빌드 → `aws s3 sync` → CloudFront 캐시 무효화
  - `backend/**` 변경: 테스트 → Docker 이미지 빌드·푸시 → EC2에서 `docker compose pull && up -d` → CloudFront 주소의 `/api/health` 확인, 실패 시 이전 이미지로 롤백
  - DB 스키마는 백엔드 시작 시 Flyway가 EC2 안 MySQL에 적용

## 2. ERD (MVP)

공통 규칙 (T05 확정)
- 모든 테이블에 `created_at`, `updated_at` DATETIME(6) NOT NULL. 앱이 Asia/Seoul 기준으로 채우고 DB 기본값은 두지 않음
- 상태값은 VARCHAR (MySQL ENUM은 값 추가 시 테이블 변경이 필요해 쓰지 않음), 금액 BIGINT, 비율만 DECIMAL
- FK는 실제 제약, 기본 RESTRICT. 공구 이미지만 CASCADE
- POINT SRID 4326의 축 순서는 **(위도, 경도)**: `ST_PointFromText('POINT(위도 경도)', 4326)`
- CHECK 제약: 정원(`reserved_units ≤ capacity_units`, `paid_units ≤ reserved_units`), 금액 양수, 정산 `gross = fee + net`, 반경 500/1000/3000. MySQL CHECK 위반(오류 3819)은 Spring이 `DataIntegrityViolationException`이 아니라 `UncategorizedSQLException`으로 던진다 (T13 참고)
- JPA는 `ddl-auto=validate`: 스키마는 Flyway만 바꾸고, 엔티티는 해당 티켓에서 만든다

```
users 1 ─ N group_buys (host)
users 1 ─ N participations N ─ 1 group_buys
participations 1 ─ N payments
group_buys 1 ─ N group_buy_images
group_buys 1 ─ N pickup_options 1 ─ N pickup_votes N ─ 1 users
group_buys 1 ─ 1 settlements
users 1 ─ N inquiries
users 1 ─ N notifications N ─ 1 group_buys
notices (독립)
```

### users
| 컬럼 | 타입 | 설명 |
| --- | --- | --- |
| id | BIGINT PK | |
| email | VARCHAR(100) UNIQUE | 로그인 아이디 |
| password_hash | VARCHAR(100) | BCrypt |
| nickname | VARCHAR(20) UNIQUE | |
| role | VARCHAR(10) | USER / ADMIN |
| location | POINT SRID 4326 NULL | 동네 기준 좌표, SPATIAL INDEX는 NOT NULL이 필요하므로 users에는 인덱스 없음 |
| neighborhood_name | VARCHAR(50) NULL | 화면 표시용 동네 이름 |
| search_radius_m | INT | 500 / 1000 / 3000, 기본 1000 |
| created_at, updated_at | DATETIME(6) | |
| deleted_at | DATETIME(6) NULL | 탈퇴(소프트 삭제) |

### group_buys (공구)
| 컬럼 | 타입 | 설명 |
| --- | --- | --- |
| id | BIGINT PK | |
| host_id | BIGINT FK users | 개설자 |
| title | VARCHAR(100) | 상품명 |
| description | TEXT | |
| category | VARCHAR(20) | LIVING / FOOD / KITCHEN / ETC |
| product_url | VARCHAR(500) | 온라인 상품 링크, 필수 |
| price_type | VARCHAR(15) | EQUAL / BY_QUANTITY |
| total_price | BIGINT | 총 결제 예정 금액(원) |
| total_quantity | INT | 총 수량 (예: 30) |
| unit_label | VARCHAR(10) | 단위 (예: 롱, 개, g) |
| capacity_units | INT | 정원 단위. EQUAL = 인원 수, BY_QUANTITY = total_quantity |
| min_units_per_person | INT | BY_QUANTITY일 때 1인 최소 수량, EQUAL은 1 |
| reserved_units | INT | 결제 대기 + 결제 완료로 잡힌 단위 (동시성 카운터) |
| paid_units | INT | 결제 완료된 단위 |
| status | VARCHAR(15) | DRAFT / OPEN / CONFIRMED / PICKUP / COMPLETED / FAILED |
| fail_reason | VARCHAR(20) NULL | DEADLINE_UNMET / HOST_CANCELED |
| demo_type | VARCHAR(10) NULL | NULL = 실제 공구, SEED = 시드(예시), TRIAL = 체험 모드. CHECK로 이 세 값만 허용 (V3, T09.5) |
| trial_user_id | BIGINT FK NULL | 체험 공구를 만든 방문자. TRIAL일 때만 값이 있음(CHECK). 인덱스 (trial_user_id, created_at) (V5, T16.5) |
| location | POINT SRID 4326 NOT NULL | 개설자 동네 좌표, SPATIAL INDEX |
| neighborhood_name | VARCHAR(50) | |
| deadline_at | DATETIME(6) | 모집 마감 |
| confirmed_at, completed_at | DATETIME(6) NULL | |
| version | BIGINT | 낙관적 락 (상태 전이용) |
| created_at, updated_at | DATETIME(6) | |

인덱스: (status, deadline_at) 스케줄러용, SPATIAL(location), (host_id)

### group_buy_images
id, group_buy_id FK, url VARCHAR(500), sort_order INT

### participations (참여)
| 컬럼 | 타입 | 설명 |
| --- | --- | --- |
| id | BIGINT PK | |
| group_buy_id | BIGINT FK | |
| user_id | BIGINT FK | |
| is_host | BOOLEAN | 개설자 본인 몫 여부 |
| units | INT | 차지한 정원 단위 (EQUAL은 1) |
| amount | BIGINT | 결제 금액(원) |
| status | VARCHAR(20) | PENDING_PAYMENT / PAID / CANCELED / REFUND_PENDING / REFUNDED / RECEIVED |
| expires_at | DATETIME(6) | 결제 대기 만료 시각 (생성 + 10분) |
| received_at | DATETIME(6) NULL | |
| created_at, updated_at | DATETIME(6) | |

제약: UNIQUE(group_buy_id, user_id) — 한 공구에 한 사람당 한 행. 결제 대기 만료로 CANCELED가 된 행은 같은 행을 PENDING_PAYMENT로 되돌려 재참여를 허용하고(결제창을 실수로 닫아도 다시 참여 가능), 결제 후 본인 취소(REFUNDED)는 재참여를 막는다 (2026-11-16 변경). 결제 거절은 참여를 CANCELED로 바꾸지 않고 결제 대기로 둬서 대기 시간 안에 다른 수단으로 다시 결제할 수 있다 (T11 결정)

### payments
| 컬럼 | 타입 | 설명 |
| --- | --- | --- |
| id | BIGINT PK | |
| participation_id | BIGINT FK | |
| method | VARCHAR(10) | TOSS / DEMO(체험 봇, 토스 호출 없음). 기본 TOSS, CHECK (V5, T16.5) |
| order_id | VARCHAR(64) UNIQUE | 서버가 발급, 토스 orderId |
| payment_key | VARCHAR(200) NULL | 토스 승인 후 저장 |
| amount | BIGINT | |
| status | VARCHAR(15) | READY / DONE / CANCELED / FAILED |
| approved_at, canceled_at | DATETIME(6) NULL | |
| cancel_reason | VARCHAR(100) NULL | |

### pickup_options / pickup_votes
- pickup_options: id, group_buy_id FK, pickup_at DATETIME(6), place_name VARCHAR(100), selected BOOLEAN
- pickup_votes: id, group_buy_id, pickup_option_id FK, user_id FK, UNIQUE(group_buy_id, user_id) — 공구당 1표, 다시 투표하면 변경

### settlements
id, group_buy_id UNIQUE, host_id, gross_amount, fee_rate DECIMAL(5,4), fee_amount, net_amount, status(PENDING / DONE), settled_at · 계산과 시점은 4장 "수령 확인과 정산"

### notifications (T15, V4)
id, user_id FK, type(GROUP_BUY_CONFIRMED / GROUP_BUY_FAILED / GROUP_BUY_CANCELED), group_buy_id FK, message VARCHAR(200, 만들 때 완성된 문장), read_at NULL, created_at · 인덱스 (user_id, id) · 30일 지나면 매일 04:10 삭제

### inquiries / notices
- inquiries: id, user_id, type(BUG / FEATURE / ETC), title, content, status(OPEN / ANSWERED), answer, answered_at, issue_url(GitHub Issue 링크)
- notices: id, title, content, created_at

## 3. 상태 전이

### 공구 (group_buys.status)
| 현재 | 이벤트 | 다음 | 처리 |
| --- | --- | --- | --- |
| DRAFT | 개설자 본인 몫 결제 승인 | OPEN | 목록 공개 |
| DRAFT | 결제 대기 만료(10분) | 삭제 | 이미지 정리 |
| OPEN | paid_units == capacity_units | CONFIRMED | 투표 1위 수령 일정 확정, 알림 |
| OPEN | 마감 시각 경과 & 미달 | FAILED(DEADLINE_UNMET) | 전원 결제 취소 |
| OPEN | 개설자 취소 | FAILED(HOST_CANCELED) | 전원 결제 취소 |
| CONFIRMED | 개설자 "구매했어요" | PICKUP | |
| PICKUP | 참여자 전원 수령 확인 또는 **수령 일정 + 3일** 경과 (T16에서 "확정 후 3일"을 바꿈: 수령일이 마감 후 최대 14일이라 받기 전에 정산될 수 있었음) | COMPLETED | 정산 기록 생성 |

- 확정 이후 참여 취소 불가 (확정 = 개설자가 살 수 있는 상태)
- 전이는 엔티티 메서드(`open()`, `confirm()`, `fail(reason)` 등)에서만 하고, 허용되지 않은 전이는 예외

### 참여 (participations.status)
PENDING_PAYMENT → PAID (승인) / CANCELED (만료 T14, 결제 전 본인 취소 T12) · 결제 거절은 PENDING_PAYMENT 유지 · PAID → REFUND_PENDING → REFUNDED (마감 전 본인 취소, 개설자 취소, 공구 실패) · CANCELED → PENDING_PAYMENT (같은 공구 재참여, 행 재사용) · PAID → REFUNDED (마감 전 본인 취소, 공구 실패) · PAID → RECEIVED (수령 확인)

## 4. 핵심 로직

### 금액 계산 (T10 확정, `Pricing`)
- 공통식: 1자리당 금액 = floor(total_price / capacity_units), 남는 원 = total_price − 1자리당 금액 × capacity_units. 참여자 = 1자리당 금액 × 자리 수, 개설자 = 같은 식 + 남는 원 → 정원이 다 차면 합계 = 총가
- EQUAL: capacity = 인원, 한 사람 한 자리. 예: 10,000원 / 3명 → 참여자 3,333원, 개설자 3,334원
- BY_QUANTITY: capacity = 총수량. 나누어떨어지지 않으면 단가는 내림, 남는 원은 개설자 (2026-11-16 결정, 거부·올림 대신 균등과 같은 규칙). 예: 25,900원 / 30개 → 단가 863원, 개설자 10개 8,640원, 참여자 10개 8,630원
- 1자리당 금액이 1원 미만이면 개설 거부. 곱셈·덧셈은 Math.multiplyExact/addExact로 오버플로 시 예외
- 서버가 계산한 금액만 신뢰, 클라이언트 금액은 비교용

### 공구 개설·수정·삭제 (T08)
- 개설: 검증 → (트랜잭션 밖) tmp 사진을 공개 위치로 복사 → (짧은 트랜잭션) 공구 DRAFT + 개설자 참여(PENDING_PAYMENT, 금액 = Pricing.hostAmount, 만료 10분) + `reserved_units` = 개설자 몫 + 수령 후보 + 사진. DB 실패 시 복사한 사진 삭제(보상). 개설자 결제(orderId)는 T11에서 이 흐름에 붙임
- 공구 좌표는 개설자 동네 좌표를 복사. 엔티티에서는 `@ColumnTransformer`(쓰기 ST_PointFromText, 읽기 ST_AsText)로 WKT 문자열을 다룸. 스키마 검증에서 MySQL 드라이버가 공간 컬럼을 `geometry`로 보고하므로 columnDefinition을 `geometry`로 맞춤
- 규칙: 링크 http/https 필수, 마감 1시간~14일, 균등 2~20명, 수량별은 개설자 몫을 빼고도 최소 수량만큼 자리가 남아야 함, 수령 후보 1~3개는 마감 이후 14일 안
- 수정: 상품명·설명·카테고리·사진·마감 연장만. 금액·수량·인원·링크는 결제 금액이 바뀌므로 불가(삭제 후 재개설). DRAFT·OPEN에서만
- 삭제: T08은 DRAFT만. 결제된 공구 삭제는 개설자 환불이 필요해 T12
- 수정·삭제는 공구 행을 `SELECT … FOR UPDATE`로 잠근 뒤 "개설자 외 PENDING_PAYMENT·PAID 참여자 없음"을 확인 → 참여(T13 조건부 UPDATE)와 직렬화되어 확인-실행 사이 끼어들기 불가. `@Version` 낙관적 락도 둠
- DRAFT 공구는 개설자에게만 보이고 남에게는 404 (존재 여부도 숨김)
- 사진: `POST /api/uploads/presigned`가 `images/tmp/{회원}/{uuid}.{ext}` presigned PUT URL(5분, Content-Type·Content-Length 서명) 발급 → 브라우저가 S3에 직접 업로드(긴 변 1600px·webp로 줄여서) → 저장 시 서버가 키 형식·소유자·존재를 확인하고 `images/group-buys/`로 복사. tmp는 S3 수명 주기 1일. 빠진 사진은 DB 커밋 후에만 S3에서 삭제

### 마지막 자리 동시성 (T13 확정)
1. 참여 요청 시 조건부 UPDATE로 자리를 먼저 잡는다
   `UPDATE group_buys SET reserved_units = reserved_units + :u WHERE id = :id AND status = 'OPEN' AND deadline_at > NOW() AND reserved_units + :u <= capacity_units`
   영향 행이 0이면 "자리가 없어요"
2. participation(PENDING_PAYMENT, expires_at = +10분) + payment(READY, order_id) 생성 → orderId·금액 반환
3. 결제 성공 시 paid_units 증가, paid_units == capacity_units면 확정
4. **락 순서는 "공구 → 참여"로 통일** (취소·결제 승인과 같음). 참여 행은 먼저 잠그지 않고 읽기만 → 자리가 필요하면(새 참여·재참여) 조건부 UPDATE(공구 행 잠금) → 그다음 내 참여 행을 `refresh(PESSIMISTIC_WRITE)`로 잠그고 다시 읽기. 그 사이 같은 사람의 다른 요청이 상태를 바꿨으면 `PARTICIPATION_CHANGED`(409)로 롤백
   - T11 구현은 없는 참여 행을 먼저 `FOR UPDATE`로 찾았다. MySQL REPEATABLE READ는 없는 행 자리에 **갭 락**을 걸고, 갭 락끼리는 서로 막지 않는다. 그래서 여러 명이 갭 락을 쥔 채 공구 행을 기다리고, 공구 행을 얻은 쪽의 INSERT(insert intention)는 남의 갭 락에 막혀 **데드락**. 정원은 지켜졌지만 진 쪽은 500. 세션 두 개로 재현해 `SHOW ENGINE INNODB STATUS`로 확인함 (`index uk … lock_mode X insert intention waiting` ↔ `gb PRIMARY … locks rec but not gap waiting`)
   - Hibernate는 같은 영속성 컨텍스트에 이미 있는 엔티티를 다시 조회해도 상태를 덮어쓰지 않으므로, 재확인은 `findByIdForUpdate`가 아니라 `EntityManager.refresh(…, PESSIMISTIC_WRITE)`
5. 비교: 설정 `banting.participation.seat-lock` = `CONDITIONAL`(기본) / `PESSIMISTIC`(공구 행 `SELECT … FOR UPDATE` → `GroupBuy.reserve`에서 확인·증가)
   - 둘 다 공구 행 X 락으로 직렬화되므로 정합성은 같다. 차이는 락을 쥔 동안의 왕복 수 (조건부: UPDATE 한 번, 비관적: SELECT FOR UPDATE + 커밋 때 UPDATE)
   - Redis 분산 락: **제외**. 서버 1대·DB 1개라 DB 행 락으로 충분하고, Redis를 띄우면 t4g.micro(1GB) 메모리와 크레딧이 더 들며 락과 DB 커밋 사이 불일치라는 새 문제가 생긴다. 서버를 여러 대로 늘리고 DB 락 경합이 병목이 될 때 다시 검토
6. 측정 (2026-11-20, 로컬: Windows + Docker MySQL 8.4, `loadtest/run-last-seat.sh`). 회원 100명이 남은 5자리 공구에 동시에 참여 × 공구 10개 = 1,000건, 방식마다 2~3회

   | 방식 | 201 | 409 | 500 | 정원 초과 | 참여 요청 avg | p95 |
   |---|---|---|---|---|---|---|
   | 수정 전 (T11, 참여 행 먼저 잠금) | 50 | 925 / 934 | **25 / 16** | 0 | 222~253ms | 413~530ms |
   | 조건부 UPDATE (기본) | 50 | 950 | 0 | 0 | 208~313ms | 370~654ms |
   | 비관적 락 | 50 | 950 | 0 | 0 | 287~358ms | 406~627ms |

   - 결론: 정원은 세 경우 모두 지켜졌고, 문제는 락 순서로 생긴 데드락(500)이었다. 조건부 UPDATE가 평균 기준 조금 빠르지만 실행마다 편차가 겹칠 정도라 결정적 차이는 아님. 코드가 짧고(조건이 SQL 한 문장) 락 왕복이 적은 조건부 UPDATE를 기본으로 유지
   - 테스트: `LastSeatConcurrencyTest`(두 방식 각각) 균등 남은 5자리에 100명 → 5명 성공·95명 자리 없음·그 밖의 오류 0, 수량별 남은 3개에 2개씩 100명 → 1명 성공

### 결제 승인 (T11 확정)
1. 참여 `POST /api/group-buys/{id}/participations {units}`: 조건부 UPDATE로 자리 확보 → participation(PENDING_PAYMENT, 만료 10분) + payment(READY, 서버가 만든 orderId `bt-UUID`) → orderId·금액(서버 계산) 반환
   - 개설자도 같은 API로 내 몫 결제를 시작한다 (개설 때 잡은 자리 그대로, 주문번호만 새로). 개설 API는 DRAFT까지만
   - 결제 대기 중 다시 요청하면 같은 수량일 때 대기 시간만 새로 주고 새 주문번호 발급, 이전 READY 주문은 FAILED(SUPERSEDED)로 막아 탭 두 개로 두 번 결제되는 일을 막음. 수량이 다르면 거부
   - 시드 공구 거부, 균등은 1자리, 수량별은 최소 수량~정원
2. 프론트가 토스 위젯으로 결제 → successUrl로 paymentKey, orderId, amount 전달
3. `POST /api/payments/confirm` 검증 (트랜잭션 밖, 실패 시 토스 호출 안 함 = 돈이 안 나감): 내 주문인지(남의 주문은 404), 금액 일치, 결제 대기·만료 전·마감 전
4. 토스 승인 API 호출 (트랜잭션 밖, Idempotency-Key = `confirm-{orderId}`, 연결 3초·응답 30초)
   - 4xx 거절 → payment FAILED(토스 코드 기록), 참여는 결제 대기 유지 → 다시 참여 요청으로 재결제
   - 5xx·타임아웃(결과 모름) → 승인됐을 수 있으니 취소 API 호출 후 FAILED. "취소할 결제 없음" 거절은 정상으로 봄
   - `ALREADY_PROCESSED_PAYMENT`(동시 중복 요청) → 이미 DONE이면 성공, 아니면 "처리 중"
5. 성공 → 짧은 트랜잭션: 공구 → 참여 → 결제 순서로 행을 잠그고 payment DONE, participation PAID, paid_units 증가, 개설자면 DRAFT → OPEN, 정원이 차면 CONFIRMED
6. 5가 실패하면(예: 그 사이 공구가 실패 처리됨) 토스 취소 API로 보상 → payment CANCELED(payment_key·사유 기록). 취소마저 실패하면 FAILED + "자동 취소 실패, 수동 확인 필요" + error 로그
7. 같은 orderId 재요청은 이미 DONE이면 토스를 다시 부르지 않고 그대로 성공 (멱등)
- 만료된 결제 대기 건의 자리 반환은 스케줄러(T14)가 만료 2분 뒤에 한다. 그 전에 같은 사람이 다시 요청하면 그 자리로 재결제
- 확정 시 수령 일정 선택(투표 1위)과 알림은 같은 반영 트랜잭션에서 (T15)
- 테스트: 가짜 토스 클라이언트로 성공·거절·응답 없음·반영 실패 보상·보상 실패·멱등·만료·금액 불일치, 실제 클라이언트는 로컬 HTTP 서버로 요청 형식 검증

### 참여 취소·환불 (T12 확정)
- 참여자 취소 `DELETE /api/participations/{id}`: 모집 중·마감 전만 (확정 뒤엔 개설자가 살 수 있는 상태라 불가). 개설자는 참여 취소 대신 공구 취소
  - 결제 전(PENDING_PAYMENT): 토스 호출 없이 CANCELED, 자리 반환, 쓰던 READY 주문은 FAILED로 막음. 같은 공구 재참여 가능
  - 결제 후(PAID): ① 짧은 트랜잭션(공구 → 참여 잠금): 참여 REFUND_PENDING, reserved·paid 반환 ② 토스 취소(트랜잭션 밖, Idempotency-Key `cancel-{orderId}`) ③ 짧은 트랜잭션: 참여 REFUNDED, 결제 CANCELED
  - ②가 실패하면 REFUND_PENDING으로 남고 202 응답. 같은 취소를 다시 하면 ②부터 재시도, T14 스케줄러가 남은 건을 자동 재시도. 토스 `ALREADY_CANCELED_PAYMENT`는 이미 환불된 것으로 보고 기록만 마무리
  - 자리를 ②보다 먼저 돌려주는 이유: 나가겠다는 사람의 자리는 바로 다른 이웃이 쓸 수 있어야 하고, 돌려줄 돈은 REFUND_PENDING 기록으로 남아 반드시 처리된다. 반대로(토스 먼저) 하면 환불 후 DB 반영이 실패할 때 돈 안 낸 사람이 자리를 차지하고 바로잡을 기록도 없다
- 개설자 취소 `POST /api/group-buys/{id}/cancel`: 모집 중만 → FAILED(HOST_CANCELED). 한 트랜잭션에서 PAID는 전부 REFUND_PENDING, 결제 대기는 CANCELED → 토스 취소는 한 건씩. 일부 실패 시 202 + 남은 수, 다시 요청하면 남은 건만 재시도. 무산된 공구의 reserved·paid는 기록으로 그대로 둔다
- 환불 한 건 처리는 `RefundService.refund(participationId)` 하나로 모아 T14(마감 실패 환불, 재시도)도 같은 경로를 쓴다

### 스케줄러 (T14 확정)
- `GroupBuyScheduler`(실행만) → `GroupBuyLifecycleService`(로직). `banting.scheduler.enabled=true`일 때만 등록 (local·prod 켬, 테스트 꺼짐: 몰래 돌아 다른 테스트 데이터를 바꾸지 않게)
- 1분마다 (fixedDelay, 시작 30초 후)
  1. 결제 대기 만료: 참여자 PENDING_PAYMENT → CANCELED, 자리 반환, READY 주문 FAILED
  2. 개설 대기 만료: 개설자 결제 대기가 만료된 DRAFT 삭제(사진·수령 후보·결제 대기 주문 포함, 사진 파일은 커밋 후). 돈이 오간 기록(DONE·CANCELED 결제)이 있으면 지우지 않고 error 로그(수동 확인)
  3. 마감 미달: OPEN → FAILED(DEADLINE_UNMET). PAID는 REFUND_PENDING, PENDING은 CANCELED(+READY 주문 FAILED) → 트랜잭션 밖에서 `RefundService`로 한 건씩 토스 취소. 자리 수는 기록으로 둠
- 10분마다: 남은 REFUND_PENDING 재시도 (마감 실패·참여 취소·개설자 취소 모두). 토스가 계속 실패할 때 1분마다 두드리지 않게 주기를 나눔
- 매일 04:00: 만료된 refresh 토큰 삭제. 폐기됐어도 만료 전이면 남김 (재사용 시 탈취 감지에 필요)
- **여유 2분** (만료·마감 시각 + 2분이 지난 건만 처리): 결제 승인은 "만료·마감 전" 확인 뒤 토스 응답까지 최대 33초(연결 3 + 응답 30). 그 사이 끼어들면 READY 주문이 먼저 FAILED가 되어 뒤따르는 보상 취소가 결제 키·CANCELED를 기록하지 못한다(돈은 돌려주지만 기록이 빔). 화면·결제 확인은 마감 시각에 바로 막히므로 사용자 영향 없음
- 멱등: 대상은 ID만 100건씩 조회 → 한 건씩 짧은 트랜잭션에서 공구 → 참여 순으로 잠그고 상태 재확인 후 변경. 중간에 멈추거나 한 건이 예외여도 다음 실행에서 남은 건만 처리. 같은 트랜잭션에서 일반 조회 후 잠금 조회를 하면 Hibernate가 캐시된 옛 값을 돌려주므로 잠금 조회로 처음 읽음
- 시드 공구(`demo_type`)는 제외. 서버 1대 기준(스케줄러 스레드 하나라 작업끼리 겹치지 않음). 여러 대가 되면 ShedLock
- "자동 확정"은 마지막 결제 승인 순간 이미 처리(T11). PICKUP → COMPLETED + 정산은 T16
- T08 버그 수정: DRAFT 직접 삭제가 T11에서 생긴 결제 대기 주문(payments)을 지우지 않아 개설자가 결제창을 연 뒤 삭제하면 FK 위반(500). 삭제를 `GroupBuyService.deleteDraftRows`로 모아 스케줄러와 함께 씀
- 테스트 `GroupBuyLifecycleIntegrationTest` 14개: 만료·여유 시간·이중 반환 없음, DRAFT 삭제·돈 기록 있으면 보존, 마감 미달 전원 환불·확정/마감 전/시드 제외, 환불 실패 → 재시도, 이중 환불 없음(토스 취소 호출 수), 실패 처리 사이 도착한 승인 보상, refresh 토큰 정리

### 수령 투표와 확정 알림 (T15 확정)
- 투표 `POST /api/group-buys/{id}/pickup-votes {optionId}`: 결제 완료(PAID)한 참여자·개설자만, OPEN·마감 전에만. 다시 보내면 같은 행의 후보만 바꿈(UNIQUE(공구, 회원)). 남의 공구 후보 404, 시드 공구 거부
- 투표는 공구 행을 FOR UPDATE로 잠그고 한다. 결제 승인(확정)도 같은 행을 잠그므로 확정 순간 들어온 표가 반영됐는지 애매하지 않음 (락 순서 공구 → … 는 T13과 같음)
- 득표는 **지금 PAID·RECEIVED인 사람의 표만** 집계(참여와 조인). 투표 후 취소·환불한 사람의 표는 행을 지우지 않아도 빠지므로 취소·환불 코드가 투표를 몰라도 됨
- 1위: 득표 많은 순 → 수령 시각이 이른 순 → 먼저 등록한 순. 표가 없으면 가장 이른 후보. 후보가 1개면 화면에서 투표 UI를 숨김
- 확정(마지막 결제 승인)과 같은 트랜잭션에서 1위 후보 `selected = true` + 결제한 사람 전원(개설자 포함)에게 알림. 확정·수령 일정·알림이 함께 커밋되거나 함께 롤백(롤백되면 토스는 보상 취소)
- 알림은 인앱만 (이메일·푸시 없음: 도메인·비용·외부 호출 실패 처리 부담). `NotificationService`의 보내기 메서드는 `Propagation.MANDATORY`라 상태 변경 트랜잭션 밖에서 부르면 예외 → "상태는 바뀌었는데 알림이 없는" 불일치를 구조로 막음
  - 확정: 결제 완료한 전원 / 마감 미달 무산: 결제했던 전원(T14 스케줄러 트랜잭션) / 개설자 취소: 개설자를 뺀 결제 참여자(T12 트랜잭션)
  - `GET /api/notifications?before={id}` 최신순 20개 + 안 읽은 수 + `hasNext` (2026-11-23: 20개 넘으면 볼 수 없던 문제 → id 기준 페이지, 인덱스 (user_id, id)로 OFFSET 없이), `POST /api/notifications/{id}/read`(남의 알림 404), `POST /api/notifications/read-all`
  - 화면: 헤더 종 + 안 읽은 수, 페이지 이동 때마다 다시 조회(실시간 연결 없음). 휴대폰에선 목록을 화면 양옆 16px에 고정
- 상세 응답: `pickupOptions[].voteCount·selected`, `myPickupVoteOptionId`
- 테스트 `PickupVoteIntegrationTest` 14개: 투표·표 바꾸기·개설자 투표, 결제 전·미참여·남의 후보·확정 후 거부, 취소한 사람 표 제외, 확정 시 1위 저장(완료 조건)·동점·무투표, 미확정이면 선택·알림 없음, 무산·개설자 취소 알림 대상, 알림 조회·읽음·남의 알림 404·비로그인 401

### 수령 확인과 정산 (T16 확정)
```
CONFIRMED ─ 개설자 "구매했어요" ─▶ PICKUP ─┬ 참여자 전원 "받았어요" ───────▶ COMPLETED + 정산 기록
                                            └ 수령 일정 + 3일 경과 (스케줄러) ▶ COMPLETED + 정산 기록
```
- `POST /api/group-buys/{id}/purchased`: 개설자만, CONFIRMED만 → PICKUP + 결제 유지 중인 참여자에게 알림
- `POST /api/participations/{id}/receive`: 본인만, PAID → RECEIVED(received_at). 공구가 PICKUP·COMPLETED일 때 (자동 완료 뒤 늦게 눌러도 기록, 수령 확인율용). 개설자 몫은 대상 아님(409), 두 번 누르면 그대로. 이걸로 참여자 전원(개설자 제외)이 받았고 공구가 PICKUP이면 같은 트랜잭션에서 완료
- 스케줄러 ⑥(1분): PICKUP이고 선택된 수령 일정 + 3일이 지난 공구 완료. 확인 안 한 참여는 PAID 그대로 둠. 개설자가 "구매했어요"를 누르지 않은 CONFIRMED는 자동 완료하지 않음(미구매는 당사자 간 문제, 신고는 이후)
- 완료 = 공구 행 잠금 → PICKUP 재확인 → COMPLETED(completed_at) + 정산 기록 + 개설자 알림을 한 트랜잭션에서. 마지막 받았어요와 스케줄러가 동시에 와도 공구 행 잠금으로 한 줄로 서고 뒤에 온 쪽은 이미 완료를 보고 아무것도 안 함. `UNIQUE(group_buy_id)`가 마지막 방어선. `SettlementService.record`는 `Propagation.MANDATORY`
- 정산 금액: 총액 = PAID·RECEIVED 참여 금액 합(정원이 찼으니 공구 총가와 같음). 수수료 = 총액 × `banting.settlement.fee-bps`(300 = 3%) / 10000 **내림**(개설자에게 유리), 정산액 = 총액 − 수수료. double(0.03)이 아니라 만분율 정수 곱셈(`Math.multiplyExact`)이라 오차 없음. `fee_rate`에는 기록용 0.0300(BigDecimal). 수수료는 개설자 몫을 포함한 총액 전체에 매김(PRD 기본안)
- 정산은 기록까지만: status PENDING, 실제 송금(DONE)은 구현하지 않음
- 상세 응답 `settlement`(개설자에게만): gross·fee·net·status
- 테스트: `FeePolicyTest` 10개(내림·합계·0%·100%·넘침·범위), `ReceiveSettlementIntegrationTest` 15개(구매했어요 권한·상태, 받았어요 전·남의 것·개설자 몫·두 번, 전원 수령 → 완료·정산 30,000/900/29,100, 기한 경과 자동 완료·기한 전·미구매 제외·두 번·자동 완료 후 받았어요·시드 제외, **마지막 받았어요와 자동 완료 동시 → 정산 1건**, 정산은 개설자에게만)

### 내 공구 화면 (S07 확정)
- `GET /api/users/me/group-buys` → `{active, hosting, past}`. 탭마다 따로 부르지 않고 한 번에 받아 탭에 개수 표시
  - 참여 중: 참여자 + (결제 대기·결제 완료·수령 확인) + 공구가 모집 중·확정·나눔 준비 / 개설한: 개설자 + 공구가 DRAFT·모집 중·확정·나눔 준비 / 지난 공구: 그 밖(완료·무산, 내가 취소해 환불·환불 대기)
  - 결제 전 취소·대기 만료(CANCELED)와 시드 공구는 어디에도 넣지 않음
- 쿼리 3번: 내 참여 + 공구 조인(최근 100개) → 대표 사진 `IN`(첫 번째 사진) → 정해진 수령 일정 `IN`. 공구마다 따로 조회하는 N+1 없음. 분류·정렬은 Java
- 지금 할 일(todo): PAY(만료 전 결제 대기) / PURCHASE(개설자, 확정) / RECEIVE(참여자, 나눔 준비 → 카드에서 바로 받았어요). 할 일 있는 카드가 맨 위, 그다음 마감 임박 순. 지난 공구는 최근 마감 순
- 헤더에 "내 공구" 링크. 휴대폰 폭에서 헤더가 두 줄로 깨져 닉네임 대신 "내 정보", 로그아웃은 내 정보 화면으로 옮김
- 테스트 `MyGroupBuyIntegrationTest` 6개: 탭 분류·할 일 정렬, 개설한 탭·PURCHASE/PAY, 만료된 결제 대기는 할 일 아님, CANCELED·시드·남의 공구 제외, 대표 사진, 비로그인 401

### 반경 검색 (T07, T09)
`WHERE status = 'OPEN' AND MBRContains(:box, location) AND ST_Distance_Sphere(location, :myPoint) <= :radius ORDER BY deadline_at`
반경 박스(MBRContains, 반경 × 1.1)로 먼저 거르고 거리로 정밀 필터해 SPATIAL INDEX를 탄다 (`GroupBuySpatialQuery`)
- T09 목록 (2026-11-18 결정)
  - 기준점은 화면이 보낸 좌표가 아니라 **서버에 저장된 내 동네 좌표**. 동네 설정 전·비로그인은 서울시청(37.5665, 126.9780), 반경 1km. 응답에는 동네 이름만 주고 좌표·거리는 넣지 않음 (임의 좌표를 받거나 거리를 주면 여러 지점에서 검색해 개설자 위치를 좁혀 낼 수 있음)
  - 반경 칩(500/1000/3000)은 `radius` 파라미터로 바로 적용하고, 내 동네가 있으면 `PUT /me/location`으로 저장
  - 조건: `OPEN` + `deadline_at > 지금`(T14 스케줄러 전에도 마감 지난 공구 숨김) + 카테고리 + 상품명 `LIKE`(%, _는 `!`로 이스케이프). 반경으로 이미 좁혀진 행이라 FULLTEXT 없이 LIKE
  - 정렬 마감임박순(기본)·최신순. 페이지당 20개, 21개를 조회해 `hasNext` 판단 (COUNT 쿼리 없음). 대표 사진은 서브쿼리로 같이 가져와 N+1 없음
- T07 확인: 공구 3,000건이 전국에 흩어진 분포에서 옵티마이저가 `sp_group_buys_location`을 고름 (EXPLAIN 테스트). 모든 행의 마감일이 같은 비현실적 데이터에서는 `(status, deadline_at)` 인덱스를 골랐음 → 실행 계획은 데이터 분포에 따라 바뀌므로 운영 데이터가 쌓이면 다시 확인 (T13 k6 때)

### 시드 데이터와 체험 모드 (2026-11-18 결정)
> 반띵은 사람이 모여야 흐름이 보이는 서비스라, 면접관이나 첫 방문자가 혼자 들어와도 비어 보이지 않고 전체 흐름을 직접 겪어 볼 수 있게 한다. 체험은 가짜 화면이 아니라 **실제 서비스 로직(상태 전이 · 정원 · 확정 · 투표 · 정산)을 그대로 거친다**.

**시드 데이터 (T09.5)**
- 기본 목록 기준점(서울시청 1km)에 예시 공구 6~8개(카테고리별, 사진 포함)를 둔다. 비로그인·동네 미설정 방문자의 첫 화면이 비어 보이지 않게
- `group_buys.demo_type`(VARCHAR, NULL / SEED / TRIAL, CHECK 제약)으로 구분 (Flyway V3, 2026-11-18 변경: BOOLEAN 하나로는 "첫 화면에 보일 시드"와 "목록에서 빠질 체험 공구"를 구분할 수 없음. V2는 refresh_tokens가 이미 사용)
- 개설자·참여자는 로그인할 수 없는 데모 회원 (`*@demo.banting.invalid`, 비밀번호 칸에 BCrypt가 아닌 값 → 어떤 비밀번호와도 불일치)
- 넣는 방법은 Flyway가 아니라 `banting.demo.seed=true`(local·prod)일 때만 도는 멱등 시더(`DemoSeeder`, ApplicationRunner). 스키마 이력에 데모 데이터를 섞지 않기 위함. 시드 실패는 로그만 남기고 기동을 막지 않음
- 멱등 키는 데모 개설자 + 상품 URL. 있으면 마감일(오늘 21시 + N일)과 수령 일정만 UPDATE → 공구 id 유지(공유 링크 안 깨짐). 기동 시 + 매일 04:30(DB 백업 04:00과 겹치지 않게). 서버를 꺼 둬도 다음 기동 때 미래로 맞춰짐
- 참여 행은 실제 모델대로(개설자·참여자 PAID, reserved_units·paid_units = 참여 수량 합). 돈이 오간 적이 없으므로 payments 행은 만들지 않음
- 사진은 프론트 정적 파일 `frontend/public/demo/*.webp`(직접 그린 일러스트, `scripts/gen-demo-images.py`), 이미지 키 `demo/…` → CloudFront `/demo/…`(프론트 버킷)
- 목록: 기본 위치(서울시청) 기준일 때만 시드를 섞고 카드에 "예시" 배지. 내 동네 목록에는 섞지 않음. 내 동네에 공구가 없으면 "근처 공구가 아직 없어요 · 체험 동네 공구 보기"(`demoArea=true`). TRIAL은 어떤 목록에도 안 나옴. 실제만 / 실제+시드 두 조건 모두 공간 인덱스를 타는지 EXPLAIN 테스트로 확인
- 상세: "예시 공구예요" 안내, 상품 링크(example.com) 숨김
- **T11~T16 주의**: 시드 공구(`demo_type = SEED`)는 참여·결제·환불·정산·스케줄러 대상이 아니다 (참여 요청은 거부, 스케줄러는 `demo_type IS NULL`만 처리)

**체험 모드 (T16.5, 2026-11-23 구현)**
- 예시(시드) 공구 상세의 "이 공구로 체험하기" → `POST /api/trials {seedGroupBuyId}` → 시드를 복사한 방문자 전용 체험 공구(균등 4명, 마감 10분). 개설자는 데모 봇(`seed-host`), 이웃 봇은 `seed-neighbor1·2`
- 봇은 화면을 흉내 내지 않고 **같은 서비스 메서드를 호출**: 봇 개설자가 `participateAsTrialBot` → `PaymentService.confirmDemo`로 내 몫을 결제해 OPEN. 방문자는 일반 참여 API + **토스 테스트 결제**
- 봇 결제는 `payments.method = DEMO` (토스 호출 없음). `confirmDemo`·봇 참여는 HTTP로 열려 있지 않고, 체험 공구가 아니면 거부(공구 행 잠금 후 한 번 더 확인). 환불·보상 취소는 DEMO면 토스를 부르지 않고 기록만
- 봇 진행(`TrialBotService`): 진행 상태를 따로 저장하지 않고 4초마다 **지금 DB 상태를 보고 다음 한 걸음만** (재시작해도 이어짐). OPEN: 방문자 결제 후 이웃 봇 1명 참여·DEMO 결제·투표 → 정원이 차면 기존 로직대로 확정·1위 수령 일정·알림 / CONFIRMED: 봇 개설자 구매했어요 / PICKUP: 봇 받았어요 → 방문자 받았어요 → 완료·정산 기록
- 격리: TRIAL은 어떤 목록·내 공구·통계에도 안 나오고, 상세·참여는 만든 방문자만(남에게는 404). 시드는 여전히 참여·투표 불가
- 스케줄러(T14)는 SEED만 제외하고 TRIAL도 처리: 방문자가 결제하지 않으면 마감 미달로 실패하고 환불(봇 몫은 DEMO라 기록만, 방문자 몫은 토스 취소)
- 남용 방지: 회원당 끝나지 않은 체험 1개(있으면 새로 만들지 않고 그 체험으로 보냄, 200 `resumed`), 24시간에 5번(429). 같은 회원의 동시 요청은 회원 행 `FOR UPDATE`로 한 줄로
- **정리 (2026-11-23 변경)**: 설계의 "24시간 뒤 자동 삭제"를 **삭제하지 않고 남기기**로 바꿈. 결제·정산 기록이 감사 이력으로 남고, 여러 테이블 FK 순서 삭제와 토스 결제 기록·DB 불일치를 피함. 데이터 양은 무시할 수준. 완료된 체험의 방문자 토스 테스트 결제는 취소하지 않음(테스트 돈)
- 로그인 필요 (게스트 체험 계정은 피드백 보고 결정), 방문자는 참여자로만
- 화면(②): 체험 상세에 진행 단계 체크(열림 → 내 결제 → 봇 참여 n/2 → 확정 → 구매 → 받았어요 → 정산), 3초마다 새로 고침. 완료 시 방문자에게도 정산 내역(실제 공구는 개설자만). 내 공구에 내가 만든 체험이 "체험" 배지로 보임

### 동네 설정 (T07)
- 좌표 → 동네 이름: 카카오 로컬 API(coord2regioncode, 행정동 H)를 **서버에서** 호출. 클라이언트가 보낸 이름은 쓰지 않음 (가짜 동네 이름 방지)
- 주소 검색: 카카오 주소 검색 → 결과 없으면 장소(키워드) 검색. 로그인 회원만 (무료 호출량 보호)
- 저장 좌표는 소수점 셋째 자리(약 100m)로 반올림 (반경 검색 반복으로 집 위치를 좁혀 내는 것 방지). 응답에 좌표 없음
- 좌표는 JPA 엔티티에 매핑하지 않고 `GeoPoint.toWkt()` + 네이티브 SQL로만 다룸 (JTS (경도, 위도) vs MySQL 4326 (위도, 경도) 축 혼동 차단)
- 카카오 호출은 DB 트랜잭션 밖에서 먼저 끝내고 저장은 UPDATE 한 번. 타임아웃 연결 2초·응답 3초, 실패 시 503 `LOCATION_SERVICE_UNAVAILABLE`
- 서비스 지역은 국내(위도 33.0~38.7, 경도 124.5~132.0)로 검증

## 5. API (MVP)
| 메서드 | 경로 | 설명 | 권한 |
| --- | --- | --- | --- |
| GET | /api/health | 헬스체크 | 공개 |
| POST | /api/auth/signup | 회원가입 | 공개 |
| POST | /api/auth/login | 로그인, access·refresh 토큰 | 공개 |
| POST | /api/auth/refresh | 토큰 재발급 (refresh 쿠키, rotation) | 공개 |
| POST | /api/auth/logout | 로그아웃 (refresh 폐기, 쿠키 삭제) | 공개 |
| GET | /api/users/me | 내 정보 | 회원 |
| PATCH | /api/users/me | 닉네임 수정 | 회원 |
| PUT | /api/users/me/location | 동네 좌표·반경 저장 (좌표 생략 시 반경만), 이름은 서버가 조회 | 회원 |
| GET | /api/locations/region | 좌표의 동네 이름 미리보기 (lat, lng) | 회원 |
| GET | /api/locations/search | 주소·장소 검색 (query, 최대 5건) | 회원 |
| DELETE | /api/users/me | 탈퇴 | 회원 |
| POST | /api/uploads/presigned | S3 업로드 URL 발급 (jpg·png·webp, 5MB) | 회원 |
| POST | /api/group-buys | 공구 개설(DRAFT) + 개설자 자리 예약 (T08). 개설자 결제는 참여 API로 시작 (T11) | 회원 |
| GET | /api/group-buys | 목록 (radius, category, keyword, sort=DEADLINE/LATEST, page, demoArea). 기준점은 로그인 시 저장된 내 동네, 아니면 서울시청 (2026-11-18 변경: lat·lng 받지 않음) | 공개 |
| GET | /api/group-buys/{id} | 상세 (진행률, 수령 후보, 내 참여 myParticipation: 상태·수량·금액·만료) | 공개 |
| PATCH | /api/group-buys/{id} | 수정: 상품명·설명·카테고리·사진·마감 연장 (개설자 외 참여자 없을 때) | 개설자 |
| DELETE | /api/group-buys/{id} | 삭제 (개설자 외 참여자 없을 때). T08은 DRAFT만, 결제된 공구는 개설자 몫 환불과 함께 T12 | 개설자 |
| POST | /api/group-buys/{id}/cancel | 모집 중 취소 → 무산, 전원 환불 → {refunded, pendingRefunds}. 일부 대기면 202 (T12) | 개설자 |
| POST | /api/group-buys/{id}/purchased | "구매했어요" (CONFIRMED → PICKUP) | 개설자 |
| POST | /api/group-buys/{id}/participations | 참여 (units) → orderId, amount, orderName, expiresAt. 개설자는 내 몫 결제 시작 | 회원 |
| DELETE | /api/participations/{id} | 마감 전 참여 취소·환불 → {status, refundAmount}. 환불 대기로 남으면 202 (T12) | 본인 |
| POST | /api/participations/{id}/receive | 수령 확인 | 본인 |
| POST | /api/payments/confirm | 토스 결제 승인 | 회원 |
| POST | /api/group-buys/{id}/pickup-votes | 수령 후보 투표 (optionId), 다시 보내면 변경 (T15) | 결제한 참여자·개설자 |
| GET | /api/notifications?before= | 내 알림 20개씩(id 기준 더 보기) + 안 읽은 수 + hasNext (T15, 11-23 페이지) | 회원 |
| POST | /api/trials | 체험 시작 (시드 id → 체험 공구, 진행 중이면 그 체험 200) (T16.5) | 회원 |
| POST | /api/notifications/{id}/read, /api/notifications/read-all | 알림 읽음 (T15) | 본인 |
| GET | /api/users/me/group-buys | 내 공구 (S07): `{active, hosting, past}` 한 번에, 카드마다 지금 할 일(todo) | 회원 |
| POST | /api/inquiries | 문의 등록 | 회원 |
| GET | /api/inquiries/me | 내 문의 | 회원 |
| GET | /api/notices | 업데이트 노트 | 공개 |
| GET | /api/admin/inquiries | 문의 목록 | 관리자 |
| POST | /api/admin/inquiries/{id}/answer | 답변 | 관리자 |
| POST | /api/admin/notices | 노트 작성 | 관리자 |

공통 응답 오류 형식: `{ "code": "GROUP_BUY_FULL", "message": "자리가 없어요" }`

## 6. 보안
- 비밀번호 BCrypt, JWT access 30분 / refresh 14일 (T06 확정)
  - access: JWT(HS256), Spring Security OAuth2 Resource Server가 검증. sub = 회원 id, role 클레임 → ROLE_*. 프론트는 메모리에만 보관하고 Authorization 헤더로 전송
  - refresh: 무작위 256비트 문자열을 **HttpOnly·Secure·SameSite=Strict·Path=/api/auth 쿠키**로만 주고받음 (JS가 읽을 수 없어 XSS로 탈취 불가). DB(`refresh_tokens`, V2)에는 SHA-256 해시만 저장
  - rotation: 재발급마다 기존 토큰 폐기 후 새로 발급. 폐기는 조건부 UPDATE(`revoked_at IS NULL`)로 원자적으로 해서 동시 요청 중 하나만 성공. 폐기된 토큰이 다시 오면 탈취로 보고 그 회원의 refresh 전부 폐기(모든 기기 로그아웃). 이 폐기는 예외를 던져도 커밋되도록 `noRollbackFor`
  - 프론트는 동시에 필요한 refresh를 한 요청으로 묶는다 (React StrictMode 이중 실행이나 여러 API의 동시 401이 "재사용"으로 오인되지 않게). 같은 브라우저의 여러 탭이 동시에 새로고침하면 오인될 수 있음 → 문제가 되면 짧은 유예 시간 도입 검토
  - 로그인 실패는 이메일 없음·비밀번호 틀림·탈퇴를 같은 오류로, 없는 이메일도 BCrypt 비교를 한 번 해서 응답 시간 차이를 줄임
  - 남은 일: 로그인 시도 횟수 제한(T18), 만료된 refresh 행 정리(T14 완료)
- 비밀 값(DB 비밀번호, JWT 키, 토스 시크릿 키, AWS 키)은 GitHub Secrets와 EC2 `.env`에만, 저장소에 커밋 금지
- 외부 API 키(카카오 등)는 AWS Parameter Store `/banting/prod/*`(SecureString)에 콘솔로 직접 입력 → 배포 때 `deploy.sh`가 서버 `.env`로 반영. 로컬은 gitignore된 `backend/application-secret.yml`
- 토스 시크릿 키는 서버에서만 사용. 로컬 `application-secret.yml`, 운영 Parameter Store `/banting/prod/toss-secret-key` → `.env`의 `TOSS_SECRET_KEY`. 지금은 토스 문서 공개 테스트 키(결제위젯용), 내 테스트 키로 바꿀 땐 값만 교체. 클라이언트 키는 공개용이라 프론트 빌드 설정에 둔다
- 상품 링크는 http/https만 허용
- 화면과 API가 같은 CloudFront 주소라 CORS는 열지 않는다
- AWS 키는 배포용 IAM 사용자에 최소 권한(이 버킷 업로드, 이 배포 무효화, 이 인스턴스 SSM 명령)만 부여. OIDC가 더 낫지만 무료 플랜 계정의 SCP가 OIDC 공급자 생성을 막아 차선책으로 씀 (docs/infra.md)

## 7. 티켓과 완료 조건
| ID | 티켓 | 완료 조건 |
| --- | --- | --- |
| T01 | 저장소·폴더 구조, CLAUDE.md, docs 4종 | 문서가 저장소에 있고 GitHub에 푸시 |
| T02 | Spring Boot·React 프로젝트 생성 | 로컬에서 /api/health 응답, 화면 뜨기 |
| T03 | CI: GitHub Actions 빌드·테스트 | PR마다 자동 실행, 실패 시 머지 불가(브랜치 보호) |
| T04 | CD: S3 + CloudFront(기본 주소, `/api/*` → EC2), EC2에 Spring + MySQL Docker Compose, 예산 알림 | main 머지 시 바뀐 쪽만 자동 배포, CloudFront 주소에서 화면과 `/api/health` 모두 HTTPS로 응답 |
| T05 | ERD 확정, Flyway V1 | 로컬·서버 DB에 테이블 생성 |
| T06 | 회원가입·로그인(JWT) | 가입 → 로그인 → 내 정보 조회 동작 |
| T07 | 위치 기반 동네 설정 | 좌표 저장, 반경 검색 쿼리 테스트 통과 |
| T08 | 공구 개설·수정·삭제, 사진 S3 (T10 다음에 진행, 2026-11-16 순서 변경) | 링크 필수 검증, 참여자 있으면 수정·삭제 막힘 |
| T09 | 공구 목록·상세·반경 필터·검색 | 내 동네 공구만, 마감임박순 정렬 |
| T09.5 | 시드 데이터 (체험 동네 예시 공구) | 비로그인 첫 화면에 예시 공구가 보이고, 실제 공구와 `demo`로 구분, 마감일 자동 갱신 |
| T10 | 금액 계산: 균등 / 수량별 | 단위 테스트 통과(나머지 원 처리 포함) |
| T11 | 참여 = 결제 (토스 테스트) | 개설자·참여자 결제 승인, 실패 시 보상 취소 |
| T12 | 참여 취소·환불 | 마감 전 취소 시 결제 취소와 상태 변경 |
| T13 | 마지막 자리 동시성 | k6 동시 100명 정원 초과 0건, 방식 비교 기록 (Redis는 검토 후 제외) |
| T14 | 스케줄러: 결제 대기 만료, 마감 미달 환불 | 중간에 멈춰도 다시 돌면 이어서 처리(멱등). 자동 확정은 T11, 정산은 T16 |
| T15 | 수령 후보·투표·확정 알림 | 확정 시 1위 일정이 수령 일정으로 저장 |
| T16 | 수령 확인·정산(수수료 차감) | 전원 확인 또는 기한(수령 일정 + 3일) 경과 시 정산 기록 |
| T16.5 | 체험 모드 (봇 참여자) | 혼자서 참여·테스트 결제 → 자동 확정 → 투표 → 수령 → 정산까지 1~2분 안에 완료, 실제 목록·통계와 격리 |
| T17 | 문의 게시판, 업데이트 노트, 중개자 고지 | 문의 등록·관리자 답변 동작, 푸터 고지 |
| T18 | 운영 준비: Sentry, 실사용자 모집 | 에러 알림 수신, 첫 외부 사용자 가입 |
| T19~ | 피드백 반영 + 추가 기능 | 이슈별로 완료 조건을 그때 정함 |
