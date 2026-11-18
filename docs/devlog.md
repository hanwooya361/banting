# 반띵 작업 일지

> 작업마다 무엇을, 왜 했는지 3줄. 최신이 위.

## 2026-11-18 · T09.5 시드 데이터 (체험 동네 예시 공구)
- 혼자 들어온 방문자의 첫 화면이 비지 않게 서울시청 1km에 예시 공구 8개를 넣음. 문서의 `demo BOOLEAN`은 T16.5 체험 공구와 구분이 안 돼 `demo_type`(NULL/SEED/TRIAL + CHECK)으로 바꾸고, V2가 이미 쓰여 있어 V3로 마이그레이션. 시드는 기본 위치 목록에만 "예시"로 섞고 내 동네 목록엔 넣지 않으며, 비어 있으면 "체험 동네 공구 보기"로 따로 보여 줌
- 데이터는 Flyway가 아니라 `banting.demo.seed`일 때만 도는 멱등 시더로 넣음(데모 개설자+상품 URL이 키, 있으면 마감일·수령 일정만 UPDATE해 id 유지). 기동 시 + 매일 04:30(백업 04:00 회피)에 마감을 미래로 옮기고, 실패해도 기동을 막지 않게 함. 데모 회원은 BCrypt가 아닌 해시로 로그인 불가, 결제 행 없이 참여 수량 합만 맞춤
- 테스트: 시더 5개(생성·멱등·마감 갱신·수량 합·로그인 불가), 목록 3개, CHECK 제약, demo_type 조건 2가지 모두 공간 인덱스 유지(EXPLAIN). 사진은 PIL로 직접 그린 일러스트(라이선스 걱정 없음)를 프론트 정적 파일로 배포. 헤드리스 브라우저로 비로그인 첫 화면·빈 동네 → 체험 동네 → 상세 흐름 확인. 다음은 T11

## 2026-11-18 · T09 공구 목록·반경 필터·검색
- 설계는 목록 API가 lat·lng를 받게 되어 있었지만, 임의 좌표를 받거나 거리를 응답하면 여러 지점에서 검색해 개설자 위치를 좁혀 낼 수 있어 바꿈: 기준점은 서버에 저장된 내 동네 좌표(없으면 서울시청), 응답엔 동네 이름만. 반경 칩은 바로 적용하고 내 동네가 있으면 저장
- 쿼리는 T07의 MBR + ST_Distance_Sphere 2단계에 카테고리·LIKE 검색(%·_ 이스케이프)·마감임박/최신 정렬을 붙이고, 마감 지난 공구는 스케줄러(T14) 전에도 숨김. 대표 사진은 서브쿼리로 같이 가져와 N+1을 없애고, 21개를 조회해 hasNext를 판단해 COUNT 쿼리 생략. 테스트의 마감 시각을 DB NOW() 대신 앱 기준 한국 시간으로 넣어 컨테이너 시간대 영향 제거
- 테스트 추가(쿼리 조건 11개 + API 8개 + WKT 파싱), EXPLAIN으로 공간 인덱스 유지 확인. 헤드리스 브라우저로 홈 화면 반경·카테고리·검색·빈 결과를 확인하다 긴 상품명 때문에 모바일에서 카드가 가로로 넘치는 문제(그리드 열 min-width)를 찾아 고침. 추가 차별점 후보는 prd 남은 결정에 기록. 다음은 T11

## 2026-11-16 · T08 공구 개설·수정·삭제, 사진 S3
- 개설은 검증 → 트랜잭션 밖 사진 복사 → 짧은 트랜잭션(공구 DRAFT + 개설자 결제 대기 참여 + 자리 예약 + 수령 후보)로 나누고, DB가 실패하면 복사한 사진을 지우는 보상, 삭제는 커밋 후에만 S3 정리. 수정·삭제는 공구 행을 FOR UPDATE로 잠근 뒤 참여자 유무를 확인해 참여와 직렬화
- 사진은 presigned PUT으로 브라우저 → S3 직접 업로드(1GB 서버 보호), 키에 회원 id를 넣어 남의 사진·임의 경로 끌어오기를 막고 tmp는 수명 주기로 자동 정리. 같은 CloudFront /images/*로 서비스해 도메인·CORS 추가 없음
- POINT 컬럼을 @ColumnTransformer로 매핑하다 ddl validate가 드라이버의 "geometry" 타입명 때문에 실패해 columnDefinition을 맞춤. 테스트 92개(DB 정리 도우미로 테스트 간 격리). 브라우저 E2E에서 수정 화면이 늦게 온 응답으로 입력값을 덮어쓰는 경쟁 상태를 찾아 effect 정리로 고침. 로컬 S3는 SDK가 aws login 세션을 못 읽어 ProcessCredentialsProvider(aws configure export-credentials, 15분 키 자동 갱신)로 해결. 다음은 T09

## 2026-11-16 · T10 금액 계산 (T08보다 먼저)
- 개설 시 개설자 몫을 계산해 자리를 잡아야 해서 순서를 바꿔 T10을 먼저 함. 돈 로직이라 테스트 19개를 먼저 쓰고 Pricing을 구현
- 균등·수량별을 "1자리당 금액 × 자리 수, 남는 원은 개설자"라는 하나의 식으로 통일. 수량별이 나누어떨어지지 않을 때 거부·올림 대신 이 규칙을 써서 정원이 차면 합계가 정확히 총가가 되게 함 (조합별 합계 검증 테스트 포함)
- 금액은 long, 곱셈·덧셈은 multiplyExact/addExact로 오버플로를 예외로. 다음은 T08

## 2026-11-16 · T07 위치 기반 동네 설정
- 동네 이름은 카카오 로컬 API를 서버에서 호출해 정하고(가짜 이름 방지), 좌표는 약 100m로 반올림해 저장·응답에서 제외. 좌표는 JPA에 매핑하지 않고 GeoPoint.toWkt()+네이티브 SQL로만 다뤄 (위도, 경도) 축 혼동을 막고, 외부 호출은 트랜잭션 밖에서 끝낸 뒤 UPDATE 한 번으로 저장
- 반경 검색은 MBR 박스로 공간 인덱스를 태운 뒤 ST_Distance_Sphere로 정밀 필터. 처음 EXPLAIN 테스트가 실패해 보니 비현실적 데이터(마감일 전부 동일)에서 옵티마이저가 (status, deadline_at) 인덱스를 골랐고, 실제 분포로 넣자 공간 인덱스를 골라 "실행 계획은 데이터 분포에 따라 바뀐다"를 기록
- 카카오 키는 사용자가 Parameter Store에 직접 넣고 EC2 역할은 /banting/prod/*만 읽게 해 deploy.sh가 .env로 반영(대화·GitHub에 키 없음). 테스트 39개(가짜 RegionClient, JDK HttpServer로 카카오 응답 흉내 포함). 다음은 T08

## 2026-11-16 · T06 회원가입·로그인(JWT)
- access는 JWT(Spring Security 리소스 서버, 메모리 보관), refresh는 HttpOnly·SameSite=Strict 쿠키 + DB 해시 저장으로 나눠 XSS로 토큰을 훔칠 수 없게 하고, 재발급마다 교체(rotation)·폐기 토큰 재사용 시 전체 로그아웃(탈취 탐지)을 넣음. 동시 재발급은 조건부 UPDATE로 하나만 성공시키고, 탐지 후 예외에도 폐기가 롤백되지 않게 noRollbackFor
- 개발 중 React StrictMode가 효과를 두 번 실행해 refresh가 두 번 나가면 탈취로 오인돼 바로 로그아웃되는 문제를 미리 보고, 프론트에서 진행 중인 refresh를 공유하도록 함. 슬라이스 테스트에서 @ConfigurationPropertiesScan이 안 먹는 것, @Email이 공백 정리보다 먼저 도는 것도 테스트로 잡아 고침
- 테스트 19개(가입·로그인·조회, 중복·검증, 만료·위조 토큰, rotation, 탈취 탐지, 로그아웃)와 헤드리스 브라우저로 가입→/me→새로고침 유지→로그아웃 흐름 확인. 운영 JWT 키는 서버에서 생성해 .env에만 둠. 다음은 T07

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
