package com.banting.groupbuy;

/** 데모 공구 종류. 실제 공구는 null (tech-design 4장 "시드 데이터와 체험 모드"). */
public enum DemoType {
    SEED,   // 체험 동네 예시 공구 (T09.5). 기본 위치 목록에 "예시"로 보임
    TRIAL   // 체험 모드 공구 (T16.5). 목록에는 안 보이고 만든 방문자만 봄
}
