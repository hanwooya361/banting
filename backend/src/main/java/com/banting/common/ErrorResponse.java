package com.banting.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * 오류 응답 본문. fieldErrors는 입력 검증 실패일 때만 들어간다 (폼에서 칸별로 표시).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(String code, String message, List<FieldError> fieldErrors) {

    public record FieldError(String field, String message) {
    }

    public static ErrorResponse of(ErrorCode errorCode) {
        return new ErrorResponse(errorCode.name(), errorCode.getMessage(), null);
    }

    public static ErrorResponse of(ErrorCode errorCode, List<FieldError> fieldErrors) {
        return new ErrorResponse(errorCode.name(), errorCode.getMessage(), fieldErrors);
    }
}
