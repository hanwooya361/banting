// 카카오·구글 간편 가입·로그인 (T23). 버튼은 서버에 키가 설정된 공급자만 보인다
import { api } from './api'

export type OAuthProviderPath = 'kakao' | 'google'

export const OAUTH_LABELS: Record<OAuthProviderPath, string> = {
  kakao: '카카오로 계속하기',
  google: 'Google로 계속하기',
}

export function fetchOAuthProviders() {
  return api<{ providers: OAuthProviderPath[] }>('/api/auth/oauth/providers')
}

// 브라우저가 서버 주소로 직접 이동해야 한다 (서버가 state 쿠키를 굽고 카카오·구글 화면으로 보냄)
export function oauthStartUrl(provider: OAuthProviderPath, next: string) {
  return `/api/auth/oauth/${provider}/start?next=${encodeURIComponent(next)}`
}

export type OAuthSignupInfo = { provider: 'KAKAO' | 'GOOGLE'; suggestedNickname: string | null; email: string | null }

export function fetchOAuthSignupInfo() {
  return api<OAuthSignupInfo>('/api/auth/oauth/signup-info')
}

export function completeOAuthSignup(nickname: string) {
  return api('/api/auth/oauth/complete', { method: 'POST', body: { nickname, agreePrivacy: true } })
}

// 서버가 /login?socialError=…로 돌려보낼 때의 안내
export const SOCIAL_ERROR_MESSAGES: Record<string, string> = {
  canceled: '간편 로그인을 취소했어요.',
  state: '로그인 요청이 만료됐어요. 다시 시도해 주세요.',
  provider: '카카오·구글에서 정보를 받지 못했어요. 잠시 후 다시 시도해 주세요.',
  disabled: '지금은 이 간편 로그인을 쓸 수 없어요.',
}

// 우리 사이트 경로만 (서버 규칙과 같음)
export function safeNext(next: string | null | undefined, fallback = '/group-buys') {
  return next && /^\/(?!\/)\S*$/.test(next) ? next : fallback
}
