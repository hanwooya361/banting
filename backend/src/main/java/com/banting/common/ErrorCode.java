package com.banting.common;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * API 오류 코드. 응답은 {@code {"code": "...", "message": "..."}} 형식 (tech-design 5장).
 * code는 프론트가 분기에 쓰는 고정값, message는 사용자에게 그대로 보여줄 문구.
 */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // 공통
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "입력값을 확인해 주세요"),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "로그인이 필요해요"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "권한이 없어요"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 주소를 찾을 수 없어요"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 요청이에요"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "잠시 후 다시 시도해 주세요"),

    // 인증·회원
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 맞지 않아요"),
    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "다시 로그인해 주세요"),
    EMAIL_DUPLICATED(HttpStatus.CONFLICT, "이미 가입된 이메일이에요"),
    NICKNAME_DUPLICATED(HttpStatus.CONFLICT, "이미 사용 중인 닉네임이에요"),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "회원 정보를 찾을 수 없어요"),

    // 동네·위치
    LOCATION_OUT_OF_SERVICE_AREA(HttpStatus.BAD_REQUEST, "국내 위치만 설정할 수 있어요"),
    LOCATION_REQUIRED(HttpStatus.BAD_REQUEST, "먼저 동네 위치를 골라 주세요"),
    REGION_NOT_FOUND(HttpStatus.BAD_REQUEST, "이 위치의 동네를 찾지 못했어요. 다른 곳을 골라 주세요"),
    LOCATION_SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "위치 서비스가 잠시 응답하지 않아요. 잠시 후 다시 시도해 주세요");

    private final HttpStatus status;
    private final String message;
}
