// 수령 후보 투표 (T15). 결제를 마친 참여자·개설자만, 모집 중에만. 다시 보내면 표를 바꾼다
import { api } from './api'

export function votePickup(groupBuyId: number, optionId: number) {
  return api<{ votesByOption: Record<string, number>; myOptionId: number | null }>(
    `/api/group-buys/${groupBuyId}/pickup-votes`,
    { method: 'POST', body: { optionId } },
  )
}
