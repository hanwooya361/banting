// 내 공구 (S07): 참여 중 / 개설한 / 지난 공구
import { api } from './api'
import type { GroupBuyStatus } from './groupBuy'
import type { ParticipationStatus } from './payment'

export type MyTodo = 'PAY' | 'PURCHASE' | 'RECEIVE'

export type MyGroupBuyItem = {
  groupBuyId: number
  title: string
  status: GroupBuyStatus
  failReason: 'DEADLINE_UNMET' | 'HOST_CANCELED' | null
  thumbnailUrl: string | null
  host: boolean
  participationId: number
  participationStatus: ParticipationStatus
  units: number
  amount: number
  deadlineAt: string
  pickupAt: string | null
  pickupPlace: string | null
  todo: MyTodo | null // 지금 내가 할 일 (있으면 맨 위)
  trial: boolean // 내가 만든 체험 공구 (T16.5)
}

export type MyGroupBuys = { active: MyGroupBuyItem[]; hosting: MyGroupBuyItem[]; past: MyGroupBuyItem[] }

export function fetchMyGroupBuys() {
  return api<MyGroupBuys>('/api/users/me/group-buys')
}
