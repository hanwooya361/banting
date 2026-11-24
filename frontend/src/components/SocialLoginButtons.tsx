import { useEffect, useState } from 'react'
import { fetchOAuthProviders, OAUTH_LABELS, oauthStartUrl, type OAuthProviderPath } from '../lib/oauth'

// 간편 가입·로그인 버튼 (T23). 서버에 키가 있는 공급자만 보이고, 하나도 없으면 아무것도 안 그린다
export function SocialLoginButtons({ next }: { next: string }) {
  const [providers, setProviders] = useState<OAuthProviderPath[]>([])

  useEffect(() => {
    let active = true
    fetchOAuthProviders()
      .then((r) => active && setProviders(r.providers))
      .catch(() => {})
    return () => {
      active = false
    }
  }, [])

  if (providers.length === 0) return null

  return (
    <div className="flex flex-col gap-2">
      {providers.map((p) => (
        <a
          key={p}
          href={oauthStartUrl(p, next)}
          className={
            p === 'kakao'
              ? 'flex items-center justify-center gap-2 rounded-lg bg-[#FEE500] py-2.5 font-medium text-[#191919]'
              : 'flex items-center justify-center gap-2 rounded-lg border border-gray-300 bg-white py-2.5 font-medium text-gray-800'
          }
        >
          {p === 'kakao' ? <KakaoIcon /> : <GoogleIcon />}
          {OAUTH_LABELS[p]}
        </a>
      ))}
      <div className="my-2 flex items-center gap-3 text-xs text-gray-400">
        <span className="h-px flex-1 bg-gray-200" />
        또는 이메일로
        <span className="h-px flex-1 bg-gray-200" />
      </div>
    </div>
  )
}

function KakaoIcon() {
  return (
    <svg viewBox="0 0 24 24" className="h-4 w-4" aria-hidden="true">
      <path
        fill="#191919"
        d="M12 3C6.48 3 2 6.48 2 10.8c0 2.76 1.84 5.18 4.6 6.56l-.94 3.44c-.08.3.26.54.52.36l4.1-2.72c.56.08 1.14.12 1.72.12 5.52 0 10-3.48 10-7.76S17.52 3 12 3z"
      />
    </svg>
  )
}

function GoogleIcon() {
  return (
    <svg viewBox="0 0 24 24" className="h-4 w-4" aria-hidden="true">
      <path fill="#4285F4" d="M22.56 12.25c0-.78-.07-1.53-.2-2.25H12v4.26h5.92a5.06 5.06 0 0 1-2.2 3.32v2.77h3.57c2.08-1.92 3.27-4.74 3.27-8.1z" />
      <path fill="#34A853" d="M12 23c2.97 0 5.46-.98 7.28-2.66l-3.57-2.77c-.98.66-2.23 1.06-3.71 1.06-2.86 0-5.29-1.93-6.16-4.53H2.18v2.84A11 11 0 0 0 12 23z" />
      <path fill="#FBBC05" d="M5.84 14.1A6.6 6.6 0 0 1 5.5 12c0-.73.13-1.44.34-2.1V7.06H2.18A11 11 0 0 0 1 12c0 1.78.43 3.45 1.18 4.94l3.66-2.84z" />
      <path fill="#EA4335" d="M12 5.38c1.62 0 3.06.56 4.21 1.64l3.15-3.15C17.45 2.09 14.97 1 12 1A11 11 0 0 0 2.18 7.06l3.66 2.84C6.71 7.3 9.14 5.38 12 5.38z" />
    </svg>
  )
}
