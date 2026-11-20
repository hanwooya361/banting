// T13 마지막 자리 동시성: 회원 100명이 같은 공구(남은 5자리)에 동시에 참여한다.
// 공구·회원 준비와 결과 검증·정리는 run-last-seat.sh가 한다. 직접 실행하지 말고 그 스크립트로.
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://localhost:8090';
const RUN = __ENV.RUN_ID;                       // 이번 실행의 회원 이메일·닉네임 접두사
const GB_IDS = __ENV.GB_IDS.split(',');         // 라운드마다 공구 하나 (각각 남은 5자리)
const USERS = 100;

const joined = new Counter('joined_201');
const full = new Counter('full_409');
const unexpected = new Counter('unexpected');   // 201·409 외 = 사용자에게 오류 (데드락이면 500)

export const options = {
  setupTimeout: '180s',
  scenarios: {
    last_seat: {
      executor: 'per-vu-iterations',
      vus: USERS,
      iterations: GB_IDS.length,   // VU 하나가 라운드마다 공구 하나에 참여
      maxDuration: '120s',
    },
  },
  thresholds: {
    unexpected: ['count==0'],
    // 참여 요청만 따로 집계 (setup의 가입·로그인 제외). 기준값은 요약에 표시하려는 용도
    'http_req_duration{name:participate}': ['p(95)<5000'],
  },
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};

const json = { headers: { 'Content-Type': 'application/json' } };

// 회원 100명 가입·로그인 → 토큰. 비밀번호 해시 때문에 느려서 20명씩 묶어 보낸다
export function setup() {
  const tokens = [];
  for (let start = 0; start < USERS; start += 20) {
    const ids = [...Array(20).keys()].map((i) => start + i);
    http.batch(ids.map((i) => ['POST', `${BASE}/api/auth/signup`,
      JSON.stringify({ email: `${RUN}-${i}@load.test`, password: 'password123', nickname: `${RUN}${i}` }), json]));
    const res = http.batch(ids.map((i) => ['POST', `${BASE}/api/auth/login`,
      JSON.stringify({ email: `${RUN}-${i}@load.test`, password: 'password123' }), json]));
    res.forEach((r) => tokens.push(r.json('accessToken')));
  }
  return { tokens };
}

export default function (data) {
  const token = data.tokens[__VU - 1];
  const gbId = GB_IDS[__ITER];
  const res = http.post(`${BASE}/api/group-buys/${gbId}/participations`, JSON.stringify({ units: 1 }), {
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
    tags: { name: 'participate' },
  });
  if (res.status === 201) joined.add(1);
  else if (res.status === 409) full.add(1);
  else unexpected.add(1);
  check(res, { '201 또는 409': (r) => r.status === 201 || r.status === 409 });
}
