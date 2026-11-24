import { createContext, useContext } from 'react'

export type Me = {
  id: number
  email: string | null // 카카오 간편 가입은 이메일이 없을 수 있음 (T23)
  nickname: string
  role: 'USER' | 'ADMIN'
  neighborhoodName: string | null
  searchRadiusM: number
  hasPassword: boolean // false면 간편 가입 회원 (비밀번호 로그인·비밀번호 확인 없음)
  socialProviders: ('KAKAO' | 'GOOGLE')[]
}

export type AuthState = {
  me: Me | null
  // 첫 로딩에서 refresh 쿠키로 로그인 상태를 복원하는 중
  loading: boolean
  login: (email: string, password: string) => Promise<void>
  signup: (email: string, password: string, nickname: string) => Promise<void>
  logout: () => Promise<void>
  // 내 정보가 바뀐 뒤(동네 설정 등) 다시 불러오기
  reloadMe: () => Promise<void>
}

export const AuthContext = createContext<AuthState | null>(null)

export function useAuth(): AuthState {
  const value = useContext(AuthContext)
  if (!value) {
    throw new Error('useAuth는 AuthProvider 안에서만 쓸 수 있어요')
  }
  return value
}
