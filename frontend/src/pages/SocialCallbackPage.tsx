import { useEffect } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router'
import { useAuth } from '../auth/context'
import { safeNext } from '../lib/oauth'

// 간편 로그인 후 서버가 보내는 곳 (T23). 서버가 이미 refresh 쿠키를 구웠으므로, 앱이 처음 뜰 때의 로그인 복원이 끝나면 다음 화면으로
export function SocialCallbackPage() {
  const { me, loading } = useAuth()
  const [params] = useSearchParams()
  const navigate = useNavigate()
  const next = safeNext(params.get('next'))

  useEffect(() => {
    if (!loading && me) navigate(next, { replace: true })
  }, [loading, me, next, navigate])

  if (loading || me) return <p className="py-16 text-center text-gray-500">로그인하는 중…</p>
  return (
    <div className="py-16 text-center">
      <p className="text-gray-700">로그인하지 못했어요.</p>
      <Link to="/login" className="mt-3 inline-block text-orange-600 underline">
        다시 로그인하기
      </Link>
    </div>
  )
}
