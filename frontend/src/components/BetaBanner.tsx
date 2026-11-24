import { useState } from 'react'

const DISMISS_KEY = 'banting:beta-banner-dismissed'

// 베타 안내 (T18). 실사용자가 실제 결제·실제 거래로 오해하지 않게 모든 화면 위에 보인다.
// 닫으면 이 브라우저에서는 다시 안 보임 (저장소를 못 쓰는 환경이면 매번 보임)
export function BetaBanner() {
  const [hidden, setHidden] = useState(() => {
    try {
      return window.localStorage.getItem(DISMISS_KEY) === '1'
    } catch {
      return false
    }
  })
  if (hidden) return null

  const dismiss = () => {
    setHidden(true)
    try {
      window.localStorage.setItem(DISMISS_KEY, '1')
    } catch {
      // 저장 못 해도 지금 화면에서는 닫힘
    }
  }

  return (
    <div role="note" className="bg-gray-900 px-4 py-2 text-xs text-white">
      <div className="mx-auto flex max-w-5xl items-start justify-between gap-3">
        <p className="leading-relaxed">
          <strong className="mr-1 rounded bg-orange-500 px-1.5 py-0.5 text-[10px] font-bold">베타</strong>
          결제는 토스 <strong>테스트 결제</strong>라 실제 돈이 나가지 않고, 실제 거래도 이뤄지지 않아요. 써 보고 오른쪽 아래 <strong>💬 피드백</strong>으로 의견을 남겨 주세요.
        </p>
        <button type="button" onClick={dismiss} aria-label="베타 안내 닫기" className="flex-none px-1 text-gray-300 hover:text-white">
          ✕
        </button>
      </div>
    </div>
  )
}
