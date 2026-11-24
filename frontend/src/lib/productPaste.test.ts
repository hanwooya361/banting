import { describe, expect, it } from 'vitest'
import { parseQuantity, parseSharedProduct } from './productPaste'

// T19: 쇼핑 앱 "공유 → 복사" 글에서 링크·상품명·수량을 읽는다.
// 수량은 금액(1인 금액 = 총가 ÷ 수량)에 바로 쓰이므로, 확실하지 않으면 채우지 않는 쪽이 안전하다 (null)
describe('parseQuantity', () => {
  it.each([
    // [상품명, 기대 수량, 기대 단위]
    ['탐사 3겹 데코 화장지 30m, 30롤, 1팩', 30, '롤'],
    ['깨끗한나라 순수 3겹 화장지 27m, 30롤, 3팩', 90, '롤'],
    ['제주삼다수 2L, 24개', 24, '개'],
    ['아이시스 생수 500ml x 20개', 20, '개'],
    ['코카콜라 제로 355ml × 24캔', 24, '캔'],
    ['크리넥스 데코 키친타월 150매 6롤', 6, '롤'],
    ['지퍼백 대형 100매', 100, '매'],
    ['듀라셀 AA 건전지 40개입', 40, '개'],
    ['냉동 닭가슴살 100g 30팩', 30, '팩'],
    ['신라면 120g, 40개', 40, '개'],
    ['비비고 왕교자 1.05kg x 2봉', 2, '봉'],
    ['캡형 물티슈 70매 10팩', 10, '팩'],
    ['다우니 섬유유연제 리필 1.6L 4개', 4, '개'],
    ['스파클 생수 2L 12병', 12, '병'],
    ['오뚜기 진라면 매운맛 120g 5개입 8봉', 40, '개'],
    ['동원참치 라이트 85g 10캔', 10, '캔'],
  ])('%s → %i%s', (title, quantity, unit) => {
    expect(parseQuantity(title)).toEqual({ quantity, unit })
  })

  it.each([
    ['샴푸 1000ml'],                     // 용량만 있고 개수 없음
    ['3겹 화장지'],                      // 겹은 개수가 아님
    ['블루투스 이어폰 1개'],             // 1개는 나눌 수 없음 (공구는 2 이상)
    ['무선 청소기 V15'],                 // 숫자가 모델명
    ['쌀 10kg'],                         // 무게
    ['생수 2L 999999개'],                // 너무 큼 (총수량 최대 10,000)
    [''],
  ])('%s → 채우지 않음', (title) => {
    expect(parseQuantity(title)).toBeNull()
  })

  it('1+1은 2개', () => {
    expect(parseQuantity('헤드앤숄더 샴푸 750ml 1+1')).toEqual({ quantity: 2, unit: '개' })
  })
})

describe('parseSharedProduct', () => {
  it('쿠팡 공유 글: 상품명 줄 + 링크 줄', () => {
    const text = '탐사 3겹 데코 화장지 30m, 30롤, 1팩\nhttps://link.coupang.com/a/bXyZ12'
    expect(parseSharedProduct(text)).toEqual({
      url: 'https://link.coupang.com/a/bXyZ12',
      title: '탐사 3겹 데코 화장지 30m, 30롤, 1팩',
      quantity: 30,
      unit: '롤',
    })
  })

  it('앞에 붙은 [쇼핑몰] 표시와 안내 문구는 상품명에서 뺀다', () => {
    const text = '[쿠팡] 제주삼다수 2L, 24개\n지금 확인해 보세요!\nhttps://www.coupang.com/vp/products/123?itemId=4'
    expect(parseSharedProduct(text)).toMatchObject({
      url: 'https://www.coupang.com/vp/products/123?itemId=4',
      title: '제주삼다수 2L, 24개',
      quantity: 24,
      unit: '개',
    })
  })

  it('한 줄에 상품명과 링크가 같이 있어도 나눈다', () => {
    expect(parseSharedProduct('지퍼백 대형 100매 https://smartstore.naver.com/shop/products/9')).toMatchObject({
      url: 'https://smartstore.naver.com/shop/products/9',
      title: '지퍼백 대형 100매',
      quantity: 100,
      unit: '매',
    })
  })

  it('링크만 붙여 넣으면 링크만 채운다', () => {
    expect(parseSharedProduct('  https://www.11st.co.kr/products/1  ')).toEqual({
      url: 'https://www.11st.co.kr/products/1',
      title: null,
      quantity: null,
      unit: null,
    })
  })

  it('http/https가 아닌 주소는 링크로 쓰지 않는다', () => {
    expect(parseSharedProduct('javascript:alert(1) 화장지 30롤').url).toBeNull()
  })

  it('상품명은 100자까지', () => {
    expect(parseSharedProduct('가'.repeat(150)).title).toHaveLength(100)
  })
})
