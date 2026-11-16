# 반띵 작업 일지

> 작업마다 무엇을, 왜 했는지 3줄. 최신이 위.

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
