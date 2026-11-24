// T19: 쇼핑 앱 "공유 → 복사" 글에서 상품 링크·상품명·총수량을 읽어 공구 열기 칸을 채운다.
// 서버가 쇼핑몰 페이지를 읽지 않는 이유: 운영 서버(AWS)에서 쿠팡·G마켓·SSG 등 10곳 중 8곳이 차단(403·502)했고,
// 서버가 사용자가 준 주소로 요청하면 내부망을 찌르는 SSRF 위험도 생긴다. 공유 글은 모든 쇼핑몰에서 똑같이 쓸 수 있다.
//
// 수량은 1인 금액(총가 ÷ 수량)에 바로 쓰이므로 확실할 때만 채운다. 애매하면 null → 사용자가 직접 입력.

export type SharedProduct = {
  url: string | null
  title: string | null
  quantity: number | null
  unit: string | null
}

export type ParsedQuantity = { quantity: number; unit: string }

const TITLE_MAX = 100
const UNIT_MAX = 10
const QUANTITY_MIN = 2 // 공구는 최소 둘이 나눠야 한다
const QUANTITY_MAX = 10_000 // 서버 총수량 최대와 같게

// 개수를 세는 단위. 긴 것부터 (개입이 개보다 먼저). 용량·무게·길이(ml, L, g, kg, m)와 겹은 개수가 아니라 넣지 않는다
const COUNT_UNITS = ['개입', '켤레', '캡슐', '스틱', '박스', '세트', '묶음', '개', '롤', '병', '캔', '매', '팩', '봉', '입', '장', '알', '정', '포', '족', '권', '통', 'box', 'BOX', 'EA', 'ea']
// 다른 단위를 여러 개 담는 바깥 포장. "30롤 3팩" = 90롤, "5개입 8봉" = 40개
const CONTAINERS = new Set(['팩', '봉', '박스', '세트', '묶음', 'box', 'BOX'])
// 낱개 안의 세부 정보. "키친타월 150매 6롤"의 150매는 한 롤의 장 수라 다른 단위가 있으면 무시
const DETAIL_UNITS = new Set(['매'])
// 단위 이름 정리: "40개입" → 40개
const UNIT_ALIASES: Record<string, string> = { 개입: '개', 입: '개', EA: '개', ea: '개', BOX: '박스', box: '박스' }

// 숫자 바로 뒤에 단위, 그 뒤에 글자가 붙지 않아야 한다 ("12개월", "3통조림", "1정품"은 개수가 아님)
// 숫자 앞에 소수점·숫자·영문이 붙으면 제외 ("1.05kg", "V15")
const TOKEN = new RegExp(
  `(?<![\\d.,A-Za-z])(\\d{1,3}(?:,\\d{3})+|\\d{1,6})\\s*(${COUNT_UNITS.join('|')})(?![가-힣A-Za-z])`,
  'g',
)
const ONE_PLUS_ONE = /(?<!\d)(\d)\s*\+\s*(\d)(?!\d)/

type Token = { n: number; unit: string }

export function parseQuantity(title: string): ParsedQuantity | null {
  const tokens: Token[] = [...title.matchAll(TOKEN)].map((m) => ({ n: Number(m[1].replaceAll(',', '')), unit: m[2] }))
  const counted = tokens.some((t) => !DETAIL_UNITS.has(t.unit)) ? tokens.filter((t) => !DETAIL_UNITS.has(t.unit)) : tokens

  let result: ParsedQuantity | null = null
  if (counted.length === 1) {
    result = { quantity: counted[0].n, unit: counted[0].unit }
  } else if (counted.length >= 2) {
    const [inner, outer] = counted
    // 낱개 단위 뒤에 포장 단위가 오면 곱한다. 그 밖의 조합("2개 3캔")은 어느 쪽인지 알 수 없어 채우지 않음
    if (CONTAINERS.has(outer.unit) && !CONTAINERS.has(inner.unit)) {
      result = { quantity: inner.n * outer.n, unit: inner.unit }
    }
  } else {
    const plus = title.match(ONE_PLUS_ONE)
    if (plus) {
      result = { quantity: Number(plus[1]) + Number(plus[2]), unit: '개' }
    }
  }

  if (!result || result.quantity < QUANTITY_MIN || result.quantity > QUANTITY_MAX) {
    return null
  }
  return { quantity: result.quantity, unit: (UNIT_ALIASES[result.unit] ?? result.unit).slice(0, UNIT_MAX) }
}

const URL_PATTERN = /https?:\/\/[^\s<>"']+/i

function extractUrl(text: string): string | null {
  const match = text.match(URL_PATTERN)
  if (!match) return null
  const raw = match[0].replace(/[)\].,!?]+$/, '') // 문장 끝 문장부호는 주소가 아님
  try {
    const url = new URL(raw)
    return url.protocol === 'http:' || url.protocol === 'https:' ? raw : null
  } catch {
    return null
  }
}

function extractTitle(text: string, url: string | null): string | null {
  const withoutUrl = url ? text.replace(url, ' ') : text
  const firstLine = withoutUrl
    .split(/\r?\n/)
    .map((line) => line.trim())
    .find((line) => line.length > 0)
  if (!firstLine) return null
  const title = firstLine
    .replace(/^(\[[^\]]{1,20}\]\s*)+/, '') // 앞에 붙은 [쿠팡] 같은 쇼핑몰 표시
    .replace(/\s+/g, ' ')
    .trim()
  return title ? title.slice(0, TITLE_MAX) : null
}

export function parseSharedProduct(text: string): SharedProduct {
  const url = extractUrl(text)
  const title = extractTitle(text, url)
  const parsed = title ? parseQuantity(title) : null
  return { url, title, quantity: parsed?.quantity ?? null, unit: parsed?.unit ?? null }
}
