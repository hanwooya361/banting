import { Link, Outlet, useLocation, useNavigate } from 'react-router'
import { useAuth } from '../auth/context'
import { NotificationBell } from './NotificationBell'

// 표지(S00)는 화면을 가로로 꽉 채우고 헤더를 배경 위에 투명하게 띄운다
const FULL_BLEED_PATHS = ['/', '/intro']

export function Layout() {
  const { me, loading, logout } = useAuth()
  const navigate = useNavigate()
  const fullBleed = FULL_BLEED_PATHS.includes(useLocation().pathname)

  const handleLogout = async () => {
    await logout()
    navigate('/')
  }

  return (
    <div className="flex min-h-screen flex-col bg-white text-gray-900">
      <header
        className={
          fullBleed
            ? 'absolute inset-x-0 top-0 z-20'
            : 'sticky top-0 z-20 border-b border-gray-100 bg-white/90 backdrop-blur'
        }
      >
        <div className={`mx-auto flex h-14 items-center justify-between px-4 ${fullBleed ? 'max-w-5xl' : 'max-w-3xl'}`}>
          {/* 로그인한 사람에게 로고는 "목록으로", 처음 온 사람에게는 "표지로" */}
          <Link to={me ? '/group-buys' : '/'} className="flex items-center gap-1.5 text-xl font-bold text-orange-500">
            <img src="/favicon.svg" alt="" className="h-6 w-6" />
            반띵
          </Link>
          <nav className="flex items-center gap-3 text-sm">
            {loading ? null : me ? (
              <>
                <NotificationBell />
                <Link to="/group-buys/new" className="rounded-full bg-orange-500 px-3 py-1.5 font-medium text-white">
                  공구 열기
                </Link>
                <Link to="/me" className="font-medium hover:underline">
                  {me.nickname}님
                </Link>
                <button type="button" onClick={handleLogout} className="text-gray-500 hover:text-gray-900">
                  로그아웃
                </button>
              </>
            ) : (
              <>
                <Link to="/login" className="hover:underline">
                  로그인
                </Link>
                <Link to="/signup" className="rounded-full bg-orange-500 px-3 py-1.5 font-medium text-white">
                  가입하기
                </Link>
              </>
            )}
          </nav>
        </div>
      </header>
      <main className={fullBleed ? 'w-full flex-1' : 'mx-auto w-full max-w-3xl flex-1 px-4 py-8'}>
        <Outlet />
      </main>
      <footer className="border-t border-gray-100 px-4 py-6 text-center text-xs leading-relaxed text-gray-500">
        <p>
          반띵은 통신판매중개자로서 거래 당사자가 아니며, 상품 구매·전달에 대한 책임은 개설자와 참여자에게 있습니다.
        </p>
        <p className="mt-2">
          <Link to="/intro" className="underline hover:text-gray-800">
            반띵 소개
          </Link>
        </p>
      </footer>
    </div>
  )
}
