import type { ReactNode } from 'react'
import { Navigate, useLocation } from 'react-router'
import { useAuth } from '../auth/context'

// 로그인이 필요한 화면. 로그인 후 원래 가려던 곳으로 돌아오도록 from을 넘긴다
export function RequireAuth({ children }: { children: ReactNode }) {
  const { me, loading } = useAuth()
  const location = useLocation()

  if (loading) {
    return <p className="text-center text-gray-500">불러오는 중…</p>
  }
  if (!me) {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />
  }
  return children
}
