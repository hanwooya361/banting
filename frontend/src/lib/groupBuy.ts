// 공구 타입, 표시용 라벨, 금액 미리보기 계산

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
  pickupOptions: { id: number; pickupAt: string; placeName: string }[]
  host: { nickname: string }
  mine: boolean
  myAmount: number | null
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
}

export function listPath(query: ListQuery, page: number) {
  const params = new URLSearchParams({ sort: query.sort, page: String(page) })
  if (query.radius !== null) params.set('radius', String(query.radius))
  if (query.category) params.set('category', query.category)
  if (query.keyword.trim()) params.set('keyword', query.keyword.trim())
  return `/api/group-buys?${params}`
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
