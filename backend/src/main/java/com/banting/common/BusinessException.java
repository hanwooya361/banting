package com.banting.common;

import lombok.Getter;

/** 예상 가능한 업무 오류. GlobalExceptionHandler가 ErrorCode의 상태 코드와 문구로 응답한다. */
@Getter
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }
}
