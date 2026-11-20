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
    LOCATION_SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "위치 서비스가 잠시 응답하지 않아요. 잠시 후 다시 시도해 주세요"),

    // 공구
    GROUP_BUY_NOT_FOUND(HttpStatus.NOT_FOUND, "공구를 찾을 수 없어요"),
    NOT_GROUP_BUY_HOST(HttpStatus.FORBIDDEN, "개설자만 할 수 있어요"),
    GROUP_BUY_HAS_PARTICIPANTS(HttpStatus.CONFLICT, "참여한 이웃이 있어서 바꾸거나 지울 수 없어요"),
    GROUP_BUY_NOT_EDITABLE(HttpStatus.CONFLICT, "지금 상태에서는 공구를 바꿀 수 없어요"),
    GROUP_BUY_NOT_DELETABLE(HttpStatus.CONFLICT, "결제가 끝난 공구는 지금 삭제할 수 없어요"),
    INVALID_PRODUCT_URL(HttpStatus.BAD_REQUEST, "상품 링크는 http:// 또는 https://로 시작하는 주소여야 해요"),
    INVALID_DEADLINE(HttpStatus.BAD_REQUEST, "마감은 1시간 뒤부터 14일 안으로 정해 주세요"),
    DEADLINE_ONLY_EXTEND(HttpStatus.BAD_REQUEST, "마감은 늦추기만 할 수 있어요"),
    INVALID_UNITS(HttpStatus.BAD_REQUEST, "수량 설정을 확인해 주세요. 개설자 몫을 빼고도 한 명 이상 참여할 자리가 있어야 해요"),
    INVALID_PRICE(HttpStatus.BAD_REQUEST, "한 자리당 금액이 1원 이상이 되도록 총가나 수량을 조정해 주세요"),
    INVALID_PICKUP_OPTION(HttpStatus.BAD_REQUEST, "수령 일정은 마감 이후 14일 안으로 1~3개 정해 주세요"),

    // 참여·결제 (T11)
    GROUP_BUY_NOT_OPEN(HttpStatus.CONFLICT, "모집 중인 공구가 아니에요"),
    GROUP_BUY_FULL(HttpStatus.CONFLICT, "자리가 없어요"),
    DEMO_GROUP_BUY(HttpStatus.CONFLICT, "예시 공구라 참여할 수 없어요"),
    INVALID_PARTICIPATION_UNITS(HttpStatus.BAD_REQUEST, "참여 수량을 확인해 주세요"),
    ALREADY_PARTICIPATED(HttpStatus.CONFLICT, "이미 참여한 공구예요"),
    CANNOT_REJOIN(HttpStatus.CONFLICT, "취소한 공구에는 다시 참여할 수 없어요"),
    PARTICIPATION_PENDING(HttpStatus.CONFLICT, "결제를 기다리는 참여가 있어요. 같은 수량으로 결제를 이어 가 주세요"),
    PARTICIPATION_CHANGED(HttpStatus.CONFLICT, "참여 상태가 방금 바뀌었어요. 다시 시도해 주세요"),

    // 수령 투표 (T15)
    PICKUP_OPTION_NOT_FOUND(HttpStatus.NOT_FOUND, "수령 후보를 찾을 수 없어요"),
    PICKUP_VOTE_NOT_ALLOWED(HttpStatus.FORBIDDEN, "결제를 마친 참여자만 투표할 수 있어요"),
    PICKUP_VOTE_CLOSED(HttpStatus.CONFLICT, "투표가 끝났어요"),

    // 구매·수령 확인 (T16)
    GROUP_BUY_NOT_PURCHASABLE(HttpStatus.CONFLICT, "확정된 공구만 구매 완료로 바꿀 수 있어요"),
    RECEIVE_NOT_READY(HttpStatus.CONFLICT, "개설자가 상품을 산 뒤에 확인할 수 있어요"),
    RECEIVE_NOT_ALLOWED(HttpStatus.CONFLICT, "수령 확인을 할 수 없는 참여예요"),

    // 알림 (T15)
    NOTIFICATION_NOT_FOUND(HttpStatus.NOT_FOUND, "알림을 찾을 수 없어요"),
    PAYMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "결제 정보를 찾을 수 없어요"),
    PAYMENT_AMOUNT_MISMATCH(HttpStatus.BAD_REQUEST, "결제 금액이 맞지 않아요"),
    PAYMENT_EXPIRED(HttpStatus.CONFLICT, "결제 시간이 지났어요. 다시 참여해 주세요"),
    PAYMENT_NOT_PENDING(HttpStatus.CONFLICT, "이미 처리된 결제예요"),
    PAYMENT_IN_PROGRESS(HttpStatus.CONFLICT, "결제를 처리하고 있어요. 잠시 후 다시 확인해 주세요"),
    PAYMENT_REJECTED(HttpStatus.BAD_REQUEST, "결제가 승인되지 않았어요. 결제 수단을 확인하고 다시 시도해 주세요"),
    PAYMENT_CANCELED(HttpStatus.CONFLICT, "결제를 마무리하지 못해 바로 취소했어요. 돈은 나가지 않아요"),
    PAYMENT_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "결제 서비스가 잠시 응답하지 않아요. 결제됐다면 자동으로 취소돼요"),
    PARTICIPATION_NOT_FOUND(HttpStatus.NOT_FOUND, "참여 정보를 찾을 수 없어요"),
    PARTICIPATION_NOT_CANCELABLE(HttpStatus.CONFLICT, "확정됐거나 마감된 공구는 취소할 수 없어요"),
    HOST_USE_GROUP_CANCEL(HttpStatus.CONFLICT, "개설자는 공구 취소로 전체를 취소할 수 있어요"),
    GROUP_BUY_NOT_CANCELABLE(HttpStatus.CONFLICT, "모집 중인 공구만 취소할 수 있어요"),
    PAYMENT_NEEDS_REVIEW(HttpStatus.INTERNAL_SERVER_ERROR, "결제 확인이 필요해요. 문의를 남겨 주시면 바로 확인할게요"),

    // 사진
    INVALID_IMAGE(HttpStatus.BAD_REQUEST, "사진을 다시 올려 주세요"),
    UNSUPPORTED_IMAGE(HttpStatus.BAD_REQUEST, "jpg·png·webp 사진, 5MB 이하만 올릴 수 있어요"),
    STORAGE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "사진 저장소가 잠시 응답하지 않아요. 잠시 후 다시 시도해 주세요");

    private final HttpStatus status;
    private final String message;
}
