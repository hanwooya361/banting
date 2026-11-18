// 참여 = 결제 (T11). 흐름: 참여(자리 확보 + 주문번호) → 결제창(토스 위젯) → successUrl → 서버 승인
import { api } from './api'
import type { GroupBuyStatus } from './groupBuy'

// 토스 결제위젯 클라이언트 키는 공개용(브라우저에 노출되는 값)이라 코드에 둔다. 시크릿 키는 서버에만 있음.
// 지금은 토스 문서의 공개 테스트 키. 내 테스트 키로 바꿀 땐 빌드 환경변수 VITE_TOSS_CLIENT_KEY로 덮어쓴다
export const TOSS_CLIENT_KEY: string =
  import.meta.env.VITE_TOSS_CLIENT_KEY ?? 'test_gck_docs_Ovk5rk1EwkEbP0W43n07xlzm'

export type ParticipationStatus = 'PENDING_PAYMENT' | 'PAID' | 'CANCELED' | 'REFUND_PENDING' | 'REFUNDED' | 'RECEIVED'

export type PaymentOrder = {
  participationId: number
  orderId: string
  amount: number
  orderName: string
  expiresAt: string
}

// 결제창으로 넘어갔다 돌아와도(페이지 이동) 주문 정보를 이어 쓰기 위해 탭 단위로 잠깐 보관
export type StoredOrder = PaymentOrder & { groupBuyId: number; host: boolean }

const ORDER_KEY = (orderId: string) => `banting:order:${orderId}`

export function saveOrder(order: StoredOrder) {
  try {
    sessionStorage.setItem(ORDER_KEY(order.orderId), JSON.stringify(order))
  } catch {
    // 저장이 막힌 환경(사생활 보호 모드 등)이면 결제 페이지가 주문을 못 찾고 상세로 돌려보낸다
  }
}

export function loadOrder(orderId: string): StoredOrder | null {
  try {
    const raw = sessionStorage.getItem(ORDER_KEY(orderId))
    return raw ? (JSON.parse(raw) as StoredOrder) : null
  } catch {
    return null
  }
}

/** 참여(개설자는 내 몫 결제 시작) → 주문번호. 금액은 서버가 계산한 값이 온다. */
export async function startPayment(groupBuyId: number, units: number, host = false): Promise<StoredOrder> {
  const order = await api<PaymentOrder>(`/api/group-buys/${groupBuyId}/participations`, {
    method: 'POST',
    body: { units },
  })
  const stored = { ...order, groupBuyId, host }
  saveOrder(stored)
  return stored
}

export type ConfirmResult = {
  groupBuyId: number
  groupBuyStatus: GroupBuyStatus
  participationStatus: ParticipationStatus
  units: number
  amount: number
}

// 같은 주문의 승인 요청은 한 번만 보낸다 (React StrictMode의 effect 두 번 실행, 새로고침 연타).
// 서버도 같은 주문 재요청을 멱등하게 처리하지만, 동시에 두 번 가면 하나가 "처리 중"을 받을 수 있어 여기서 묶는다
const inflight = new Map<string, Promise<ConfirmResult>>()

export function confirmPayment(paymentKey: string, orderId: string, amount: number): Promise<ConfirmResult> {
  let request = inflight.get(orderId)
  if (!request) {
    request = api<ConfirmResult>('/api/payments/confirm', {
      method: 'POST',
      body: { paymentKey, orderId, amount },
    })
    inflight.set(orderId, request)
  }
  return request
}

// 결제창에서 돌아온 실패 코드 → 사용자 문구 (토스 문서의 failUrl 오류 코드)
export function failMessage(code: string | null, message: string | null) {
  if (code === 'PAY_PROCESS_CANCELED') return '결제를 취소했어요.'
  if (code === 'PAY_PROCESS_ABORTED') return '결제가 중간에 멈췄어요. 다시 시도해 주세요.'
  if (code === 'REJECT_CARD_COMPANY') return '카드사에서 결제를 거절했어요. 다른 카드로 시도해 주세요.'
  return message ?? '결제를 마치지 못했어요.'
}

export type CancelResult = { status: ParticipationStatus; refundAmount: number }

/** 참여 취소 (T12). 결제 전이면 CANCELED, 결제 후면 환불(실패하면 REFUND_PENDING, 다시 부르면 재시도). */
export function cancelParticipation(participationId: number) {
  return api<CancelResult>(`/api/participations/${participationId}`, { method: 'DELETE' })
}

export type HostCancelResult = { refunded: number; pendingRefunds: number }

/** 개설자의 공구 취소 → 무산, 전원 환불. */
export function cancelGroupBuy(groupBuyId: number) {
  return api<HostCancelResult>(`/api/group-buys/${groupBuyId}/cancel`, { method: 'POST' })
}

export function minutesLeft(expiresAt: string, now: number = Date.now()) {
  return Math.max(0, Math.ceil((new Date(expiresAt).getTime() - now) / 60_000))
}
