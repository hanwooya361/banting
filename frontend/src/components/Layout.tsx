import { useEffect, useState } from 'react'
import { Link, Outlet, useLocation, useNavigate } from 'react-router'
import { useAuth } from '../auth/context'
import { BetaBanner } from './BetaBanner'
import { FeedbackButton } from './FeedbackButton'
import { NotificationBell } from './NotificationBell'

// 표지(S00)는 화면을 가로로 꽉 채우고 헤더를 배경 위에 투명하게 띄운다
const FULL_BLEED_PATHS = ['/', '/intro']

export function Layout() {
  const { me, loading, logout } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const fullBleed = FULL_BLEED_PATHS.includes(location.pathname)
  // 다른 화면이 이동하면서 넘긴 안내 한 줄 (예: 탈퇴 완료, T22)
  const notice = (location.state as { notice?: string } | null)?.notice

  const handleLogout = async () => {
    await logout()
    navigate('/')
  }

  return (
    <div className="flex min-h-screen flex-col bg-white text-gray-900">
      {/* 베타 배너는 헤더 밖, 문서 흐름 안에 둔다. 헤더 안에 두면 휴대폰에서 두 줄이 될 때 표지의 떠 있는 헤더가
          그만큼 길어져 본문을 가렸다 (T22 중 발견). 아래 상자가 relative라 표지 헤더는 배너 바로 아래부터 뜬다 */}
      <BetaBanner />
      <div className="relative flex flex-1 flex-col">
      <header
        className={
          fullBleed
            ? 'absolute inset-x-0 top-0 z-20'
            : 'sticky top-0 z-20 border-b border-gray-100 bg-white/90 backdrop-blur'
        }
      >
        <div className={`mx-auto flex h-14 items-center justify-between px-4 ${fullBleed ? 'max-w-5xl' : 'max-w-3xl'}`}>
          {/* 로그인한 사람에게 로고는 "목록으로", 처음 온 사람에게는 "표지로" */}
          <Link to={me ? '/group-buys' : '/'} className="flex shrink-0 items-center gap-1.5 whitespace-nowrap text-xl font-bold text-orange-500">
            <img src="/favicon.svg" alt="" className="h-6 w-6" />
            반띵
          </Link>
          {/* 휴대폰 폭에서도 한 줄: 줄바꿈 금지, 닉네임 대신 "내 정보", 로그아웃은 내 정보 화면으로 */}
          <nav className="flex items-center gap-2 whitespace-nowrap text-sm sm:gap-3">
            {loading ? null : me ? (
              <>
                <NotificationBell />
                <Link to="/group-buys/new" className="rounded-full bg-orange-500 px-3 py-1.5 font-medium text-white">
                  공구 열기
                </Link>
                <Link to="/me/group-buys" className="font-medium hover:underline">
                  내 공구
                </Link>
                <Link to="/me" className="font-medium hover:underline">
                  <span className="sm:hidden">내 정보</span>
                  <span className="hidden sm:inline">{me.nickname}님</span>
                </Link>
                <button
                  type="button"
                  onClick={handleLogout}
                  className="hidden text-gray-500 hover:text-gray-900 sm:inline"
                >
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
      {/* 아래 여백: 떠 있는 피드백 버튼(T20)이 마지막 내용을 가리지 않게 */}
      <main className={fullBleed ? 'w-full flex-1' : 'mx-auto w-full max-w-3xl flex-1 px-4 pb-24 pt-8'}>
        <Outlet />
      </main>
      <footer className="border-t border-gray-100 px-4 py-6 text-center text-xs leading-relaxed text-gray-500">
        <p>
          반띵은 통신판매중개자로서 거래 당사자가 아니며, 상품 구매·전달에 대한 책임은 개설자와 참여자에게 있습니다.
        </p>
        <p className="mt-2 flex flex-wrap justify-center gap-x-4 gap-y-1">
          <Link to="/intro" className="underline hover:text-gray-800">
            반띵 소개
          </Link>
          <Link to="/notices" className="underline hover:text-gray-800">
            업데이트 노트
          </Link>
          <Link to="/inquiries" className="underline hover:text-gray-800">
            문의·피드백
          </Link>
          <Link to="/privacy" className="font-semibold underline hover:text-gray-800">
            개인정보처리방침
          </Link>
          {me?.role === 'ADMIN' ? (
            <Link to="/admin" className="underline hover:text-gray-800">
              관리자
            </Link>
          ) : null}
        </p>
      </footer>
      </div>
      <FeedbackButton />
      {notice ? <Toast key={location.key} message={notice} /> : null}
    </div>
  )
}

// 화면 위에 잠깐 떠 있는 안내 (T22 탈퇴 완료 등). 레이아웃을 밀지 않게 fixed, 5초 뒤 사라짐
function Toast({ message }: { message: string }) {
  const [visible, setVisible] = useState(true)
  useEffect(() => {
    const timer = window.setTimeout(() => setVisible(false), 5000)
    return () => window.clearTimeout(timer)
  }, [])
  if (!visible) return null
  return (
    <p
      role="status"
      className="fixed inset-x-4 top-4 z-50 mx-auto max-w-md rounded-xl bg-gray-900 px-4 py-3 text-center text-sm text-white shadow-lg"
    >
      {message}
    </p>
  )
}
