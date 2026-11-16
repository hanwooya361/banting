# 반띵 작업 일지

> 작업마다 무엇을, 왜 했는지 3줄. 최신이 위.

## 2026-11-16 · T05 ERD 확정, Flyway V1, DB 백업
- ERD를 V1__init.sql로 확정: 10개 테이블, 공간 인덱스, FK, CHECK(정원 초과·금액·정산 합계). 결제창을 닫으면 영영 재참여 못 하는 문제를 발견해 만료·결제 실패 행은 재사용하도록 설계를 바꿈. 엔티티는 각 티켓에서 만들고 ddl-auto=validate로 어긋남을 기동 시점에 잡음
- Testcontainers MySQL 8.4로 마이그레이션·제약을 테스트하다 MySQL CHECK 위반이 DataIntegrityViolationException이 아니라 UncategorizedSQLException으로 번역되는 것을 확인해 T13용으로 기록. 로컬은 compose(3310), 서버는 기존 .env 비밀번호로 연결해 새 비밀 값 없이 배포
- 매일 mysqldump → S3(14일 보관) 백업을 systemd timer(Persistent)로 구성해 서버를 꺼서 놓친 백업도 부팅 때 실행. 서버 역할엔 삭제 권한을 주지 않고, 별도 DB 복원으로 백업이 실제로 복원되는지 확인. 다음은 T06

## 2026-11-16 · T04 CD (S3+CloudFront, EC2 자동 배포)
- CloudFront 기본 주소 하나로 `/*`는 S3, `/api/*`는 EC2(t4g.micro, Spring + MySQL compose)로 묶고, main 머지 시 paths-filter로 바뀐 쪽만 배포(프론트: S3 sync·무효화, 백엔드: ARM 이미지 → GHCR → SSM으로 deploy.sh, 헬스체크 실패 시 직전 SHA 롤백). 리소스는 CLI로 만들고 docs/infra.md에 기록
- 무료 플랜 SCP가 OIDC 공급자 생성을 막아 리소스 단위로 권한을 좁힌 배포 전용 IAM 사용자 키로 대체. 개발 기간엔 EC2를 쓸 때만 켜도록 ec2-start/stop 스크립트를 만들고, 꺼져 있으면 백엔드 배포는 경고 후 건너뛰고 켤 때 최신 이미지로 맞춤
- 검증: HTTPS 화면·`/api/health` UP, SPA 새로고침·API 404 JSON 유지, EC2 직접 접근 차단, 가짜 이미지로 롤백, 서버 끈 상태 머지 → 건너뜀 → 켜기 스크립트로 동기화, 프론트만 바뀐 PR은 프론트만 배포. 로컬 포트는 다른 프로젝트와 겹치지 않게 8090/5180/3310으로 변경. 다음은 T05

## 2026-11-16 · 배포 구성 변경 (AWS 무료 플랜)
- AWS 새 가입 방식 무료 플랜(크레딧 100달러, 2027-05-16까지, 시드니 리전 고정)으로 계정을 만들어 문서의 배포 구성을 다시 잡음
- 모든 사용량이 크레딧에서 차감되므로 RDS를 빼고 EC2 안 Docker MySQL(볼륨 + S3 백업)로 바꿈. 도메인 없이 CloudFront 기본 주소 하나로 `/*`는 S3, `/api/*`는 EC2로 라우팅해 HTTPS·CORS 문제를 한 번에 해결
- T04 완료 조건을 "CloudFront 주소에서 화면과 /api/health 모두 HTTPS 응답"으로 조정. 다음은 T04

## 2026-11-16 · T03 CI (GitHub Actions, 브랜치 보호)
- PR마다 backend(`./gradlew build`)와 frontend(`npm ci`→lint→build) job을 돌리는 ci.yml 추가. 리눅스 러너에서 막히지 않도록 gradlew에 실행 권한(100755)을 줌
- main 보호 규칙으로 두 검사 통과·PR 필수, 관리자 우회 금지(enforce_admins). 경로 필터는 필수 검사가 영영 대기하는 문제가 있어 두 job 모두 항상 실행
- 헬스체크 테스트를 일부러 깨뜨려 backend 실패 → PR BLOCKED·머지 거부를 확인하고 되돌림. 다음은 T04 배포

## 2026-11-14 · T02 Spring Boot·React 프로젝트 생성
- 백엔드는 Spring Initializr로 생성(Boot 4.1.2, Java 21, Web·Validation·Lombok만). Initializr가 3.x를 더 이상 제공하지 않고 3.5 무료 지원도 끝나 4.x로 바꾸고 CLAUDE.md를 고침. JPA·Flyway·Security는 DB 없이 앱이 안 뜨는 문제를 피하려고 T05·T06에서 추가
- `GET /api/health`(HealthController)와 MockMvc 테스트를 만들어 T03 CI와 T04 헬스체크가 쓸 대상을 준비
- 프론트는 Vite React+TS에 Tailwind v4를 붙이고, /api를 8080으로 프록시해 화면에서 서버 상태 UP 표시까지 확인. 다음은 T03

## 2026-11-11 · T01 폴더·문서 초안
- 교수님 상담 후 운영형(매일 배포 + 피드백 반영) 프로젝트로 방향을 잡고 주제를 자취생 공동구매 "반띵"으로 정함
- 기획서 결정 사항(온라인 상품만, 균등/수량별 금액, 개설자도 결제, 중개자 모델, 위치 기반)을 prd.md에 옮김
- ERD·상태 전이·API·티켓 초안을 tech-design.md에 작성하고 GitHub public 저장소(hanwooya361/banting)에 첫 push. 다음은 T02
