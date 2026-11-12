# 반띵 기술 설계

> T05에서 확정. 그 전까지는 초안이며, 바꿀 때는 이 문서를 먼저 고친다.

## 1. 아키텍처
> 2026-11-12 확정: 프론트 S3 + CloudFront, 백엔드 EC2, DB RDS. Python AI 서버는 두지 않는다.

```
사용자 ─ https://도메인 ─→ CloudFront ─→ S3 (React 빌드 결과)
   └── https://api.도메인 ─→ EC2 [Nginx → Spring Boot] ─→ RDS MySQL
                                     ├→ S3 (상품 사진, presigned URL)
                                     ├→ 토스페이먼츠 (결제 승인·취소)
                                     └→ Sentry (에러)
```

- 프론트와 API가 모두 HTTPS여야 한다 (HTTPS 화면에서 HTTP API를 부르면 브라우저가 막음). 프론트는 CloudFront + ACM 인증서, API는 Nginx + Let's Encrypt
- 도메인이 다르므로 Spring에서 CORS를 프론트 도메인만 허용
- RDS는 퍼블릭 접근을 끄고, EC2 보안 그룹에서 오는 3306만 허용
- 사진용 S3 버킷과 프론트용 S3 버킷은 분리
- 배포 (GitHub Actions, 바뀐 폴더만)
  - `frontend/**` 변경: 빌드 → `aws s3 sync` → CloudFront 캐시 무효화
  - `backend/**` 변경: 테스트 → Docker 이미지 빌드·푸시 → EC2에서 `docker compose pull && up -d` → `/api/health` 확인, 실패 시 이전 이미지로 롤백
  - DB 스키마는 백엔드 시작 시 Flyway가 RDS에 적용

## 2. ERD (MVP)

```
users 1 ─ N group_buys (host)
users 1 ─ N participations N ─ 1 group_buys
participations 1 ─ N payments
group_buys 1 ─ N group_buy_images
group_buys 1 ─ N pickup_options 1 ─ N pickup_votes N ─ 1 users
group_buys 1 ─ 1 settlements
users 1 ─ N inquiries
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
| status | VARCHAR(20) | PENDING_PAYMENT / PAID / CANCELED / REFUNDED / RECEIVED |
| expires_at | DATETIME(6) | 결제 대기 만료 시각 (생성 + 10분) |
| received_at | DATETIME(6) NULL | |
| created_at, updated_at | DATETIME(6) | |

제약: UNIQUE(group_buy_id, user_id) — 한 공구에 한 번만 참여. 취소 후 재참여는 같은 행을 재사용하지 않고 막는다(MVP).

### payments
| 컬럼 | 타입 | 설명 |
| --- | --- | --- |
| id | BIGINT PK | |
| participation_id | BIGINT FK | |
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
id, group_buy_id UNIQUE, host_id, gross_amount, fee_rate DECIMAL(5,4), fee_amount, net_amount, status(PENDING / DONE), settled_at

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
| PICKUP | 전원 수령 확인 또는 확정 후 3일 경과 | COMPLETED | 정산 기록 생성 |

- 확정 이후 참여 취소 불가 (확정 = 개설자가 살 수 있는 상태)
- 전이는 엔티티 메서드(`open()`, `confirm()`, `fail(reason)` 등)에서만 하고, 허용되지 않은 전이는 예외

### 참여 (participations.status)
PENDING_PAYMENT → PAID (승인) / CANCELED (만료·결제 실패) · PAID → REFUNDED (마감 전 본인 취소, 공구 실패) · PAID → RECEIVED (수령 확인)

## 4. 핵심 로직

### 금액 계산 (T10)
- EQUAL: 1인 금액 = floor(total_price / 인원). 나머지 원은 개설자 몫에 더한다. 예: 10,000원 / 3명 → 참여자 3,333원, 개설자 3,334원
- BY_QUANTITY: 단가 = total_price / total_quantity (나누어떨어지지 않으면 개설 시 거부하거나 단위당 원 단위 올림 — T10에서 결정), 금액 = 단가 × 수량
- 서버가 계산한 금액만 신뢰, 클라이언트 금액은 비교용

### 마지막 자리 동시성 (T13)
1. 참여 요청 시 조건부 UPDATE로 자리를 먼저 잡는다
   `UPDATE group_buys SET reserved_units = reserved_units + :u WHERE id = :id AND status = 'OPEN' AND deadline_at > NOW() AND reserved_units + :u <= capacity_units`
   영향 행이 0이면 "자리가 없어요"
2. participation(PENDING_PAYMENT, expires_at = +10분) + payment(READY, order_id) 생성 → orderId·금액 반환
3. 결제 성공 시 paid_units 증가, paid_units == capacity_units면 확정
4. 비교 대상: 비관적 락(SELECT ... FOR UPDATE), Redis 분산 락. k6로 동시 100명 → 정원 초과 0건, 응답 시간 비교 후 devlog에 기록

### 결제 승인 (T11)
1. 프론트가 토스 위젯으로 결제 → successUrl로 paymentKey, orderId, amount 전달
2. `POST /api/payments/confirm`: order_id로 payment 조회, 금액 일치 확인, 만료 확인
3. 토스 승인 API 호출 (트랜잭션 밖)
4. 성공 → 트랜잭션 안에서 payment DONE, participation PAID, paid_units 증가, 필요 시 공구 OPEN/CONFIRMED
5. 4가 실패하면 토스 결제 취소 API로 보상하고 FAILED 기록
6. 같은 orderId 재요청은 이미 DONE이면 그대로 성공 응답 (멱등)

### 스케줄러 (T14)
- 1분마다: (1) expires_at 지난 PENDING_PAYMENT → CANCELED, reserved_units 반환 (2) deadline_at 지난 OPEN → FAILED + 환불 (3) 확정 후 3일 지난 PICKUP → COMPLETED + 정산
- 환불은 참여 건별로 처리하고 상태로 진행도를 남겨, 중간에 멈춰도 다시 돌면 남은 건만 처리 (멱등)
- 서버 1대 기준. 여러 대가 되면 ShedLock 도입

### 반경 검색 (T07, T09)
`WHERE status = 'OPEN' AND ST_Distance_Sphere(location, :myPoint) <= :radius ORDER BY deadline_at`
반경 박스(MBRContains)로 먼저 거르고 거리로 정밀 필터해 SPATIAL INDEX를 탄다

## 5. API (MVP)
| 메서드 | 경로 | 설명 | 권한 |
| --- | --- | --- | --- |
| GET | /api/health | 헬스체크 | 공개 |
| POST | /api/auth/signup | 회원가입 | 공개 |
| POST | /api/auth/login | 로그인, access·refresh 토큰 | 공개 |
| POST | /api/auth/refresh | 토큰 재발급 | 공개 |
| GET | /api/users/me | 내 정보 | 회원 |
| PATCH | /api/users/me | 닉네임 수정 | 회원 |
| PUT | /api/users/me/location | 동네 좌표·이름·반경 저장 | 회원 |
| DELETE | /api/users/me | 탈퇴 | 회원 |
| POST | /api/uploads/presigned | S3 업로드 URL 발급 | 회원 |
| POST | /api/group-buys | 공구 개설(DRAFT) + 개설자 결제 준비 → orderId | 회원 |
| GET | /api/group-buys | 목록 (lat, lng, radius, category, keyword, sort, page) | 공개 |
| GET | /api/group-buys/{id} | 상세 (진행률, 수령 후보, 내 참여 여부) | 공개 |
| PATCH | /api/group-buys/{id} | 수정 (개설자 외 참여자 없을 때) | 개설자 |
| DELETE | /api/group-buys/{id} | 삭제 (개설자 외 참여자 없을 때, 개설자 몫 환불) | 개설자 |
| POST | /api/group-buys/{id}/cancel | 모집 중 취소 → 전원 환불 | 개설자 |
| POST | /api/group-buys/{id}/purchased | "구매했어요" (CONFIRMED → PICKUP) | 개설자 |
| POST | /api/group-buys/{id}/participations | 참여 (units) → orderId, amount | 회원 |
| DELETE | /api/participations/{id} | 마감 전 참여 취소·환불 | 본인 |
| POST | /api/participations/{id}/receive | 수령 확인 | 본인 |
| POST | /api/payments/confirm | 토스 결제 승인 | 회원 |
| POST | /api/group-buys/{id}/pickup-votes | 수령 후보 투표 (optionId) | 참여자 |
| GET | /api/users/me/group-buys | 내 공구 (role=host/participant, status) | 회원 |
| POST | /api/inquiries | 문의 등록 | 회원 |
| GET | /api/inquiries/me | 내 문의 | 회원 |
| GET | /api/notices | 업데이트 노트 | 공개 |
| GET | /api/admin/inquiries | 문의 목록 | 관리자 |
| POST | /api/admin/inquiries/{id}/answer | 답변 | 관리자 |
| POST | /api/admin/notices | 노트 작성 | 관리자 |

공통 응답 오류 형식: `{ "code": "GROUP_BUY_FULL", "message": "자리가 없어요" }`

## 6. 보안
- 비밀번호 BCrypt, JWT access 30분 / refresh 14일
- 비밀 값(DB 비밀번호, JWT 키, 토스 시크릿 키, AWS 키)은 GitHub Secrets와 EC2 `.env`에만, 저장소에 커밋 금지
- 토스 시크릿 키는 서버에서만 사용
- 상품 링크는 http/https만 허용
- CORS는 프론트 도메인(CloudFront)과 로컬 개발 주소만 허용
- AWS 키는 배포용 IAM 사용자에 최소 권한(S3 업로드, CloudFront 무효화, 이미지 푸시)만 부여

## 7. 티켓과 완료 조건
| ID | 티켓 | 완료 조건 |
| --- | --- | --- |
| T01 | 저장소·폴더 구조, CLAUDE.md, docs 4종 | 문서가 저장소에 있고 GitHub에 푸시 |
| T02 | Spring Boot·React 프로젝트 생성 | 로컬에서 /api/health 응답, 화면 뜨기 |
| T03 | CI: GitHub Actions 빌드·테스트 | PR마다 자동 실행, 실패 시 머지 불가(브랜치 보호) |
| T04 | CD: 프론트 S3+CloudFront, 백엔드 EC2+Nginx+HTTPS, RDS 생성 | main 머지 시 바뀐 쪽만 자동 배포, 두 도메인 모두 HTTPS, 헬스체크 통과 |
| T05 | ERD 확정, Flyway V1 | 로컬·서버 DB에 테이블 생성 |
| T06 | 회원가입·로그인(JWT) | 가입 → 로그인 → 내 정보 조회 동작 |
| T07 | 위치 기반 동네 설정 | 좌표 저장, 반경 검색 쿼리 테스트 통과 |
| T08 | 공구 개설·수정·삭제, 사진 S3 | 링크 필수 검증, 참여자 있으면 수정·삭제 막힘 |
| T09 | 공구 목록·상세·반경 필터·검색 | 내 동네 공구만, 마감임박순 정렬 |
| T10 | 금액 계산: 균등 / 수량별 | 단위 테스트 통과(나머지 원 처리 포함) |
| T11 | 참여 = 결제 (토스 테스트) | 개설자·참여자 결제 승인, 실패 시 보상 취소 |
| T12 | 참여 취소·환불 | 마감 전 취소 시 결제 취소와 상태 변경 |
| T13 | 마지막 자리 동시성 | k6 동시 100명 정원 초과 0건, 방식 비교 기록 |
| T14 | 스케줄러: 결제 대기 만료, 자동 확정·환불 | 중간에 멈춰도 다시 돌면 이어서 처리(멱등) |
| T15 | 수령 후보·투표·확정 알림 | 확정 시 1위 일정이 수령 일정으로 저장 |
| T16 | 수령 확인·정산(수수료 차감) | 전원 확인 또는 기한 경과 시 정산 기록 |
| T17 | 문의 게시판, 업데이트 노트, 중개자 고지 | 문의 등록·관리자 답변 동작, 푸터 고지 |
| T18 | 운영 준비: Sentry, 실사용자 모집 | 에러 알림 수신, 첫 외부 사용자 가입 |
| T19~ | 피드백 반영 + 추가 기능 | 이슈별로 완료 조건을 그때 정함 |
