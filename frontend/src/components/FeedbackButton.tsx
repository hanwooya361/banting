import { useEffect, useRef, useState, type FormEvent } from 'react'
import { Link, useLocation } from 'react-router'
import { useAuth } from '../auth/context'
import { ApiError } from '../lib/api'
import { createInquiry, INQUIRY_CONTENT_MAX, INQUIRY_TYPE_LABELS, type InquiryType } from '../lib/inquiry'

// 문의 화면·관리자 화면에서는 이미 피드백을 쓰는 중이라 숨긴다
const HIDDEN_PATHS = ['/inquiries', '/admin']
const TITLE_FROM_CONTENT = 40

// 떠 있는 피드백 버튼 (T20). 푸터의 "문의·피드백" 링크는 맨 아래라 찾기 어렵다는 피드백을 반영.
// 지금 보는 화면을 떠나지 않고 바로 남기고, 어느 화면에서 남겼는지(경로)를 같이 저장해 버그를 재현하기 쉽게 한다
export function FeedbackButton() {
  const { pathname } = useLocation()
  const { me } = useAuth()
  const [open, setOpen] = useState(false)
  const [type, setType] = useState<InquiryType>('BUG')
  const [content, setContent] = useState('')
  const [sending, setSending] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [sent, setSent] = useState(false)
  const textRef = useRef<HTMLTextAreaElement>(null)

  useEffect(() => {
    if (!open) return
    textRef.current?.focus()
    const esc = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false)
    }
    document.addEventListener('keydown', esc)
    return () => document.removeEventListener('keydown', esc)
  }, [open])

  if (HIDDEN_PATHS.some((p) => pathname.startsWith(p))) return null
  const openSheet = () => {
    setSent(false)
    setError(null)
    setOpen(true)
  }

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault()
    const text = content.trim()
    setSending(true)
    setError(null)
    try {
      // 제목 칸 없이 빠르게: 내용 앞부분을 제목으로
      const title = text.length > TITLE_FROM_CONTENT ? `${text.slice(0, TITLE_FROM_CONTENT)}…` : text
      await createInquiry({ type, title, content: text, pagePath: pathname })
      setContent('')
      setSent(true)
    } catch (err) {
      setError(err instanceof ApiError ? (err.fieldErrors[0]?.message ?? err.message) : '잠시 후 다시 시도해 주세요')
    } finally {
      setSending(false)
    }
  }

  return (
    <>
      <button
        type="button"
        onClick={openSheet}
        aria-haspopup="dialog"
        // 화면 아래에 붙는 버튼(data-sticky-cta, 예: 공구 상세의 참여하기)이 있을 때만 그 위로 올린다 (CSS :has)
        className="fixed bottom-5 right-4 z-30 flex items-center gap-1.5 rounded-full bg-gray-900 px-4 py-2.5 text-sm font-semibold text-white shadow-lg hover:bg-gray-800 [body:has([data-sticky-cta])_&]:bottom-24"
      >
        <span aria-hidden="true">💬</span> 피드백
      </button>

      {open ? (
        <div className="fixed inset-0 z-40 flex items-end justify-center bg-black/30 sm:items-center" onClick={() => setOpen(false)}>
          <div
            role="dialog"
            aria-modal="true"
            aria-labelledby="feedback-title"
            onClick={(e) => e.stopPropagation()}
            className="w-full max-w-md rounded-t-2xl bg-white p-5 shadow-xl sm:rounded-2xl"
          >
            <div className="flex items-start justify-between">
              <div>
                <h2 id="feedback-title" className="text-lg font-bold">
                  피드백 남기기
                </h2>
                <p className="mt-0.5 text-xs text-gray-500">불편한 점, 바라는 기능 무엇이든 좋아요. 답변은 알림으로 알려 드려요.</p>
              </div>
              <button type="button" onClick={() => setOpen(false)} aria-label="닫기" className="px-1 text-gray-400 hover:text-gray-700">
                ✕
              </button>
            </div>

            {!me ? (
              <div className="mt-4 rounded-xl bg-gray-50 p-4 text-center text-sm">
                <p className="text-gray-700">답변을 알려 드리려면 로그인이 필요해요.</p>
                <Link
                  to="/login"
                  state={{ from: pathname }}
                  onClick={() => setOpen(false)}
                  className="mt-3 inline-block rounded-lg bg-orange-500 px-4 py-2 font-medium text-white"
                >
                  로그인하고 남기기
                </Link>
              </div>
            ) : sent ? (
              <div className="mt-4 rounded-xl bg-green-50 p-4 text-sm text-green-800" role="status">
                <p className="font-medium">고마워요! 피드백을 받았어요.</p>
                <p className="mt-1">
                  답변이 달리면 알림으로 알려 드려요.{' '}
                  <Link to="/inquiries" onClick={() => setOpen(false)} className="underline">
                    내 문의 보기
                  </Link>
                </p>
              </div>
            ) : (
              <form onSubmit={handleSubmit} className="mt-4 flex flex-col gap-3">
                <div className="flex gap-2" role="radiogroup" aria-label="유형">
                  {(Object.keys(INQUIRY_TYPE_LABELS) as InquiryType[]).map((t) => (
                    <button
                      key={t}
                      type="button"
                      role="radio"
                      aria-checked={type === t}
                      onClick={() => setType(t)}
                      className={`rounded-full border px-3 py-1.5 text-sm ${type === t ? 'border-orange-500 bg-orange-50 font-medium text-orange-700' : 'border-gray-300 text-gray-600'}`}
                    >
                      {INQUIRY_TYPE_LABELS[t]}
                    </button>
                  ))}
                </div>
                <textarea
                  ref={textRef}
                  value={content}
                  onChange={(e) => setContent(e.target.value)}
                  maxLength={INQUIRY_CONTENT_MAX}
                  rows={4}
                  aria-label="피드백 내용"
                  placeholder="예: 결제하고 나서 화면이 하얗게 멈췄어요"
                  className="rounded-lg border border-gray-300 px-3 py-2 text-sm outline-none focus:border-orange-500"
                />
                <p className="-mt-1 text-xs text-gray-400">지금 보고 있는 화면({pathname})도 함께 보내요.</p>
                {error ? <p className="text-sm text-red-600">{error}</p> : null}
                <button
                  type="submit"
                  disabled={sending || !content.trim()}
                  className="rounded-lg bg-orange-500 py-2.5 font-semibold text-white disabled:opacity-50"
                >
                  {sending ? '보내는 중…' : '보내기'}
                </button>
              </form>
            )}
          </div>
        </div>
      ) : null}
    </>
  )
}
