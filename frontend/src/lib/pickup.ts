// 수령 투표(T15)와 구매·수령 확인(T16)
import { api } from './api'

export function votePickup(groupBuyId: number, optionId: number) {
  return api<{ votesByOption: Record<string, number>; myOptionId: number | null }>(
    `/api/group-buys/${groupBuyId}/pickup-votes`,
    { method: 'POST', body: { optionId } },
  )
}

// 개설자 "구매했어요": 확정 → 수령 대기 (T16)
export function markPurchased(groupBuyId: number) {
  return api<void>(`/api/group-buys/${groupBuyId}/purchased`, { method: 'POST' })
}

// 참여자 "받았어요". 이걸로 전원이 받았으면 공구 완료·정산 (T16)
export function receive(participationId: number) {
  return api<{ groupBuyCompleted: boolean }>(`/api/participations/${participationId}/receive`, { method: 'POST' })
}
