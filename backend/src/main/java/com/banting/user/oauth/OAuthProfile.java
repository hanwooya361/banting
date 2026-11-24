package com.banting.user.oauth;

/**
 * 공급자에게서 받은 사용자 정보 (T23).
 *
 * @param providerUserId 카카오 회원번호 / 구글 sub. 이 값으로 같은 사람인지 판단한다 (이메일은 바뀔 수 있음)
 * @param email          없을 수 있다 (카카오 이메일 동의 안 함 등)
 * @param emailVerified  공급자가 소유를 확인한 이메일인지. 확인된 이메일만 기존 계정 연결·계정 이메일로 쓴다
 * @param nickname       닉네임 제안용 (가입 화면에서 사용자가 고침)
 */
public record OAuthProfile(OAuthProvider provider, String providerUserId, String email, boolean emailVerified,
                           String nickname) {
}
