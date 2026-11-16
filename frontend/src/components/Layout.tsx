import { Link, Outlet, useNavigate } from 'react-router'
import { useAuth } from '../auth/context'

export function Layout() {
  const { me, loading, logout } = useAuth()
  const navigate = useNavigate()

  const handleLogout = async () => {
    await logout()
    navigate('/')
  }

  return (
    <div className="flex min-h-screen flex-col bg-white text-gray-900">
      <header className="border-b border-gray-100">
        <div className="mx-auto flex h-14 max-w-3xl items-center justify-between px-4">
          <Link to="/" className="text-xl font-bold text-orange-500">
            반띵
          </Link>
          <nav className="flex items-center gap-3 text-sm">
            {loading ? null : me ? (
              <>
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
      <main className="mx-auto w-full max-w-3xl flex-1 px-4 py-8">
        <Outlet />
      </main>
      <footer className="border-t border-gray-100 px-4 py-6 text-center text-xs leading-relaxed text-gray-500">
        반띵은 통신판매중개자로서 거래 당사자가 아니며, 상품 구매·전달에 대한 책임은 개설자와 참여자에게 있습니다.
      </footer>
    </div>
  )
}
