import { useEffect, useState, type FormEvent } from 'react'
import { Link, useSearchParams } from 'react-router'
import { ApiError } from '../lib/api'
import { completeOAuthSignup, fetchOAuthSignupInfo, safeNext, type OAuthSignupInfo } from '../lib/oauth'

const PROVIDER_LABELS = { KAKAO: '카카오', GOOGLE: 'Google' } as const

// 처음 온 간편 가입 사용자 (T23): 닉네임과 개인정보처리방침 동의를 받은 뒤에만 회원을 만든다
export function SocialSignupPage() {
  const [params] = useSearchParams()
  const next = safeNext(params.get('next'))
  const [info, setInfo] = useState<OAuthSignupInfo | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [nickname, setNickname] = useState('')
  const [agreed, setAgreed] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let active = true
    fetchOAuthSignupInfo()
      .then((i) => {
        if (!active) return
        setInfo(i)
        setNickname(i.suggestedNickname ?? '')
      })
      .catch((err) => active && setLoadError(err instanceof ApiError ? err.message : '잠시 후 다시 시도해 주세요'))
    return () => {
      active = false
    }
  }, [])

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault()
    setSubmitting(true)
    setError(null)
    try {
      await completeOAuthSignup(nickname.trim())
      // 서버가 refresh 쿠키를 구웠다. 새로 불러오면 앱이 로그인 상태로 시작하고, 첫 가입이라 동네 설정부터
      window.location.replace(`/me/location?onboarding=1&next=${encodeURIComponent(next)}`)
    } catch (err) {
      setError(err instanceof ApiError ? (err.fieldErrors[0]?.message ?? err.message) : '잠시 후 다시 시도해 주세요')
      setSubmitting(false)
    }
  }

  if (loadError) {
    return (
      <section className="mx-auto max-w-sm py-12 text-center">
        <p className="text-gray-700">{loadError}</p>
        <Link to="/login" className="mt-4 inline-block text-orange-600 underline">
          로그인 화면으로
        </Link>
      </section>
    )
  }
  if (!info) return <p className="py-16 text-center text-gray-500">불러오는 중…</p>

  return (
    <section className="mx-auto max-w-sm">
      <h1 className="text-2xl font-bold">{PROVIDER_LABELS[info.provider]}로 가입하기</h1>
      <p className="mt-1 text-sm text-gray-600">
        {info.email ? `${info.email} 계정으로 가입해요. ` : ''}반띵에서 쓸 닉네임만 정하면 끝나요.
      </p>
      <form onSubmit={handleSubmit} className="mt-6 flex flex-col gap-4">
        <label className="flex flex-col gap-1 text-sm font-medium text-gray-700">
          닉네임
          <input
            value={nickname}
            onChange={(e) => setNickname(e.target.value)}
            placeholder="한글·영문·숫자 2~20자"
            maxLength={20}
            className="rounded-lg border border-gray-300 px-3 py-2 font-normal outline-none focus:border-orange-500"
          />
        </label>
        <label className="flex items-start gap-2 text-sm text-gray-700">
          <input
            type="checkbox"
            checked={agreed}
            onChange={(e) => setAgreed(e.target.checked)}
            className="mt-0.5 h-4 w-4 accent-orange-500"
          />
          <span>
            <Link to="/privacy" target="_blank" className="font-medium underline">
              개인정보처리방침
            </Link>
            을 읽었고, {info.email ? '이메일·' : ''}닉네임·동네 정보를 모으는 데 동의해요. (필수)
          </span>
        </label>
        <p className="-mt-2 text-xs text-gray-500">반띵은 베타예요. 결제는 테스트라 실제 돈이 나가지 않아요.</p>
        {error ? <p className="text-sm text-red-600">{error}</p> : null}
        <button
          type="submit"
          disabled={submitting || !agreed || nickname.trim().length < 2}
          className="rounded-lg bg-orange-500 py-2.5 font-medium text-white disabled:opacity-50"
        >
          {submitting ? '가입 중…' : '가입하고 시작하기'}
        </button>
      </form>
    </section>
  )
}
