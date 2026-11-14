# 프로젝트: 반띵

> 2026-11-11 작성. 저장소 폴더: `Desktop\aloneproject`. GitHub: https://github.com/hanwooya361/banting. 개발은 이 폴더에서 Claude Code로 진행.

## 한 줄 정의
동네 이웃과 온라인 대용량 상품을 반띵하는 공동구매. 참여 = 결제, 인원이 차면 자동 확정, 못 채우면 전원 자동 환불.

## 서비스 요약
- 상품: 온라인 구매품만, 상품 링크 필수
- 금액: 개설자가 균등 n분의 1(EQUAL) 또는 수량별 금액(BY_QUANTITY) 중 선택
- 개설자도 참여자로 포함되어 개설 시 본인 몫을 결제해야 공구가 공개됨
- 공구 상태: 개설 대기 → 모집 중 → 확정 → 수령 대기 → 완료 / 마감 미달·개설자 취소 → 실패(전원 환불)
- 수령: 개설자가 시간·장소 후보를 올리고 참여자가 투표, 확정 시 1위 일정이 수령 일정
- 동네: 위치 기반 반경 검색 (MySQL 공간 함수)
- 플랫폼은 중개자: 미수령·미구매는 당사자 간 문제, 플랫폼은 신고·이력 표시로 대응. 수익은 거래당 수수료. "반띵은 거래 당사자가 아닙니다" 고지 필수
- 결제는 토스페이먼츠 테스트 모드, 정산은 기록까지만 구현

## 나에 대해
- 국비 자바 풀스택 과정 수강 중인 개발자 지망생. Java/Spring, JS, HTML/CSS, Python 기초 있음. 목표는 백엔드 SI 취업
- 바이브코딩으로 진행하지만, 면접에서 설명할 수 있을 만큼 코드를 이해해야 함 (포트폴리오 용도)
- 교수님 조언대로 "만들고 끝"이 아니라 배포 → 피드백 → 반영 → 재배포를 보여주는 운영형 프로젝트

## 너의 역할
- 단계마다 시니어 개발자 겸 PM으로 행동
- 결정이 필요한 곳에서는 선택지 2~3개와 트레이드오프를 짧게 보여주고 추천 하나를 고름
- 모르는 건 추측하지 말고 질문

## 원칙
- 운영형: 배포 파이프라인(T03·T04)을 기능보다 먼저 만든다. 이후 티켓 하나 끝날 때마다 배포
- 테스트가 실패하면 배포되지 않는다. 배포 후 헬스체크
- 돈과 정원이 걸린 로직(금액 계산, 결제, 환불, 정산, 정원)은 테스트를 먼저 쓴다
- 금액은 원 단위 정수(long), 부동소수점 금지
- 상태 변경은 정해진 전이만 허용 (도메인 엔티티 메서드에서 검증)
- 외부 결제 호출과 DB 트랜잭션을 섞지 않는다. 실패 시 보상 처리(결제 취소)
- 개인정보는 꼭 필요한 것만. 위치는 반경 계산용 좌표만 저장하고 화면에는 동네 이름만 보여줌
- 비용: 월 고정비 최소 (EC2 1대 + RDS 1대 + S3·CloudFront, 계정의 프리티어·크레딧 범위 안에서)

## 기술 스택
- 백엔드: Java 21, Spring Boot 4 (4.1.2, 2026-11-14 변경: Initializr에서 3.x 제공 종료), Spring Data JPA, Spring Security + JWT, Gradle. 기능별 패키지(user, groupbuy, participation, payment, pickup, settlement, inquiry, common, config)
- DB: MySQL 8 + Flyway. 배포는 AWS RDS, 로컬은 Docker Compose MySQL, 테스트는 Testcontainers
- 프론트: React (Vite) + TypeScript + Tailwind
- 결제: 토스페이먼츠 결제위젯 테스트 키 / 파일: AWS S3 (presigned URL)
- 배포 (2026-11-12 확정)
  - 프론트: GitHub Actions 빌드 → S3 업로드 → CloudFront(HTTPS, 도메인) 캐시 무효화
  - 백엔드: GitHub Actions(빌드·테스트) → Docker 이미지 → EC2 + Nginx(HTTPS, api 서브도메인)
  - DB: AWS RDS MySQL (EC2 보안 그룹에서만 접근)
  - Python AI 서버(FastAPI)는 쓰지 않음
- 운영: Sentry, GitHub Issues, k6

## 참고 문서
- docs/prd.md: 기획 (요구사항, 결정 사항)
- docs/screen-design.md: 화면 흐름, 카피
- docs/tech-design.md: ERD, 상태 전이, API, 동시성·결제 설계, 티켓과 완료 조건
- docs/devlog.md: 작업 일지
- 작업 전에 관련 문서를 읽고, 설계와 코드가 어긋나면 고치기 전에 먼저 알려줌

## 작업 방식
- 한 번에 티켓 하나. 작업 전에 계획(수정할 파일, 이유)을 보여주고 승인 후 코드 작성
- 티켓마다 브랜치(`feat/T06-auth` 형식) → PR → CI 통과 → main 머지 → 자동 배포
- 코드 변경 후 내가 확인할 방법(실행 명령, 확인할 화면)을 알려줌
- 커밋 메시지는 Conventional Commits 형식, 본문은 한국어
- 각 작업 끝에 docs/devlog.md에 무엇을 왜 했는지 3줄 기록
- 피드백으로 생긴 작업은 GitHub Issue 번호를 PR과 devlog에 남김

## 진행 상황
- 완료: T01 (폴더·문서 초안, GitHub 저장소 생성·첫 push), T02 (Spring Boot·React 프로젝트 생성, /api/health)
- 다음: T03 CI (GitHub Actions 빌드·테스트, 브랜치 보호)

## 실행 명령
- 백엔드: `cd backend && gradlew.bat bootRun`, 확인 http://localhost:8080/api/health
- 프론트: `cd frontend && npm install && npm run dev` (http://localhost:5173)
- 테스트: `cd backend && gradlew.bat test`
