# 반띵

동네 이웃과 온라인 대용량 상품을 반띵하는 공동구매 서비스.
참여할 때 내 몫을 결제하고, 인원이 차면 자동 확정, 못 채우면 전원 자동 환불됩니다.

- 기획: [docs/prd.md](docs/prd.md)
- 화면: [docs/screen-design.md](docs/screen-design.md)
- 설계(ERD·API·티켓): [docs/tech-design.md](docs/tech-design.md)
- 작업 일지: [docs/devlog.md](docs/devlog.md)

## 기술 스택
Java 21 · Spring Boot 3 · JPA · MySQL 8 · React(Vite, TypeScript) · 토스페이먼츠(테스트) · AWS EC2/S3 · Docker · GitHub Actions

## 운영 방식
기능(티켓) 하나가 끝날 때마다 main에 머지되고 자동 배포됩니다. 사용자 문의와 피드백은 GitHub Issues로 관리하고, 반영 내역은 서비스의 업데이트 노트에 남깁니다.
