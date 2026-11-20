// 공구 타입, 표시용 라벨, 금액 미리보기 계산
import type { ParticipationStatus } from './payment'

export type Category = 'LIVING' | 'FOOD' | 'KITCHEN' | 'ETC'
export type PriceType = 'EQUAL' | 'BY_QUANTITY'
export type GroupBuyStatus = 'DRAFT' | 'OPEN' | 'CONFIRMED' | 'PICKUP' | 'COMPLETED' | 'FAILED'

export type GroupBuyDetail = {
  id: number
  title: string
  description: string
  category: Category
  productUrl: string
  priceType: PriceType
  totalPrice: number
  totalQuantity: number
  unitLabel: string
  capacityUnits: number
  minUnitsPerPerson: number
  reservedUnits: number
  paidUnits: number
  unitPrice: number
  status: GroupBuyStatus
  neighborhoodName: string
  deadlineAt: string
  createdAt: string
  images: { key: string; url: string }[]
  // voteCount = 지금 결제 완료인 사람의 표, selected = 확정 시 고른 수령 일정 (T15)
  pickupOptions: { id: number; pickupAt: string; placeName: string; voteCount: number; selected: boolean }[]
  host: { nickname: string }
  mine: boolean
  myAmount: number | null
  demo: boolean // 체험 동네 예시 공구 (T09.5)
  // 보는 사람의 참여 (T11). 없으면 null
  myParticipation: {
    id: number
    status: ParticipationStatus
    units: number
    amount: number
    host: boolean
    expiresAt: string
  } | null
  failReason: 'DEADLINE_UNMET' | 'HOST_CANCELED' | null
  myPickupVoteOptionId: number | null // 내가 투표한 수령 후보 (T15)
  // 개설자에게만, 완료된 공구의 정산 기록 (T16). 실제 송금은 하지 않고 기록만
  settlement: { grossAmount: number; feeAmount: number; netAmount: number; status: 'PENDING' | 'DONE' } | null
}

export const CATEGORY_LABELS: Record<Category, string> = {
  LIVING: '생활용품',
  FOOD: '식품',
  KITCHEN: '주방',
  ETC: '기타',
}

// 화면 설계 3장 "상태 배지와 카피"
export const STATUS_BADGES: Record<GroupBuyStatus, { label: string; className: string }> = {
  DRAFT: { label: '결제 대기', className: 'bg-gray-100 text-gray-700' },
  OPEN: { label: '모집 중', className: 'bg-orange-100 text-orange-700' },
  CONFIRMED: { label: '확정', className: 'bg-green-100 text-green-700' },
  PICKUP: { label: '나눔 준비', className: 'bg-blue-100 text-blue-700' },
  COMPLETED: { label: '완료', className: 'bg-gray-100 text-gray-500' },
  FAILED: { label: '무산', className: 'bg-red-100 text-red-700' },
}

export function won(amount: number) {
  return `${amount.toLocaleString('ko-KR')}원`
}

const WEEKDAYS = ['일', '월', '화', '수', '목', '금', '토']

// "2026-11-20T18:00:00" (서버는 한국 시간) → "11/20(금) 18:00"
export function formatDateTime(value: string) {
  const d = new Date(value)
  const hh = String(d.getHours()).padStart(2, '0')
  const mm = String(d.getMinutes()).padStart(2, '0')
  return `${d.getMonth() + 1}/${d.getDate()}(${WEEKDAYS[d.getDay()]}) ${hh}:${mm}`
}

// <input type="datetime-local">용 "YYYY-MM-DDTHH:mm"
export function toLocalInput(d: Date) {
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`
}

/**
 * 서버(Pricing)와 같은 규칙으로 금액을 미리 보여준다. 실제 결제 금액은 서버가 계산한 값만 쓴다.
 * 1자리당 금액 = floor(총가 / 정원), 남는 원은 개설자 몫.
 */
export function previewPrice(totalPrice: number, capacityUnits: number, hostUnits: number) {
  if (!(totalPrice > 0) || !(capacityUnits > 0)) return null
  const unitPrice = Math.floor(totalPrice / capacityUnits)
  if (unitPrice < 1) return null
  const remainder = totalPrice - unitPrice * capacityUnits
  return { unitPrice, remainder, hostAmount: unitPrice * hostUnits + remainder }
}

// ───── 목록 (T09) ─────

export type GroupBuySort = 'DEADLINE' | 'LATEST'

export type GroupBuyListItem = {
  id: number
  title: string
  category: Category
  priceType: PriceType
  unitPrice: number
  unitLabel: string
  capacityUnits: number
  minUnitsPerPerson: number
  reservedUnits: number
  paidUnits: number
  neighborhoodName: string
  deadlineAt: string
  createdAt: string
  thumbnailUrl: string | null
  demo: boolean
}

// 기준 위치 좌표는 서버가 주지 않는다 (동네 이름만)
export type GroupBuyListResponse = {
  neighborhoodName: string
  radiusM: number
  defaultLocation: boolean
  items: GroupBuyListItem[]
  page: number
  hasNext: boolean
}

export const RADIUS_OPTIONS = [
  { value: 500, label: '500m' },
  { value: 1000, label: '1km' },
  { value: 3000, label: '3km' },
] as const

export type ListQuery = {
  radius: number | null // null이면 서버가 저장된 반경(비로그인은 1km)을 씀
  category: Category | null
  keyword: string
  sort: GroupBuySort
  demoArea: boolean // true면 내 동네 대신 체험 동네(서울시청, 예시 공구 포함)
}

export function listPath(query: ListQuery, page: number) {
  const params = new URLSearchParams({ sort: query.sort, page: String(page) })
  if (query.radius !== null) params.set('radius', String(query.radius))
  if (query.category) params.set('category', query.category)
  if (query.keyword.trim()) params.set('keyword', query.keyword.trim())
  if (query.demoArea) params.set('demoArea', 'true')
  return `/api/group-buys?${params}`
}

// 마감 전인지 (참여 취소 버튼 노출용. 최종 판단은 서버가 한다)
export function beforeDeadline(deadlineAt: string, now: number = Date.now()) {
  return new Date(deadlineAt).getTime() > now
}

// 마감까지 남은 시간: "3일 남음" / "5시간 남음" / "12분 남음" / "마감"
export function timeLeft(deadlineAt: string, now: number = Date.now()) {
  const minutes = Math.floor((new Date(deadlineAt).getTime() - now) / 60_000)
  if (minutes <= 0) return '마감'
  if (minutes < 60) return `${minutes}분 남음`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours}시간 남음`
  return `${Math.floor(hours / 24)}일 남음`
}

// 화면 설계 3장 모집 중 카피: "N명 더 모이면 반띵 확정!" / "N개 남았어요"
export function remainingCopy(g: Pick<GroupBuyListItem, 'priceType' | 'capacityUnits' | 'reservedUnits' | 'unitLabel'>) {
  const left = g.capacityUnits - g.reservedUnits
  if (left <= 0) return '자리가 다 찼어요'
  return g.priceType === 'EQUAL' ? `${left}명 더 모이면 반띵 확정!` : `${left}${g.unitLabel} 남았어요`
}
