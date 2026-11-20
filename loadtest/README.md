# 부하 테스트 (k6)

로컬 전용. 운영에서 돌리면 가짜 회원과 공구가 운영 DB에 남는다.

## 마지막 자리 동시성 (T13)

회원 100명이 남은 5자리 공구에 동시에 참여한다. 공구는 라운드 수만큼(기본 10개) 만든다.
성공(201)은 공구마다 정확히 5건이어야 하고, 나머지는 409(자리 없음)여야 하며, 500은 0건이어야 한다.

준비: `docker compose up -d`(로컬 MySQL), k6 설치(`scoop install k6`)

```bash
# 1) 백엔드 실행 (기본 = 조건부 UPDATE)
cd backend && ./gradlew.bat bootRun
# 2) 다른 터미널(Git Bash)에서
loadtest/run-last-seat.sh        # 라운드 수를 바꾸려면 인자로: run-last-seat.sh 20

# 비관적 락과 비교: 백엔드를 이렇게 다시 띄우고 2)를 반복
./gradlew.bat bootRun --args="--banting.participation.seat-lock=PESSIMISTIC"
```

스크립트가 하는 일: 공구를 DB에 직접 넣기(개설자 결제까지 끝난 상태) → k6 setup에서 회원 100명 가입·로그인 →
동시 참여 → 공구마다 `reserved_units`와 참여 행 합계 검사 → 이번 실행 데이터 삭제(중간에 실패해도 삭제).
결제 승인 단계는 없으므로 토스는 호출하지 않는다.

결과 해석: `{ name:participate }` 줄이 참여 요청만의 응답 시간이다(가입·로그인 제외).
측정 기록은 `docs/tech-design.md` "마지막 자리 동시성".
