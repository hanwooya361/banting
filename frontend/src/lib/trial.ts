// 체험 모드 (T16.5): 예시 공구를 복사해 나만 보는 체험 공구를 만든다. 이웃은 봇, 내 몫만 토스 테스트 결제
import { api } from './api'

export type TrialStart = { groupBuyId: number; resumed: boolean }

// 진행 중인 체험이 있으면 새로 만들지 않고 그 체험을 돌려준다 (resumed = true)
export function startTrial(seedGroupBuyId: number) {
  return api<TrialStart>('/api/trials', { method: 'POST', body: { seedGroupBuyId } })
}

// 봇이 움직이는 동안 상세를 다시 불러오는 간격. 서버 봇은 4초마다 한 걸음
export const TRIAL_POLL_MS = 3000
