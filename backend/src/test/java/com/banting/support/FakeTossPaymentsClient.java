package com.banting.support;

import com.banting.payment.TossPaymentsClient;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * 토스 대신 메모리에서 승인·취소를 기록하는 가짜. 테스트에서 "토스를 불렀는지, 몇 번 불렀는지, 취소했는지"를 확인한다.
 * - reject(code): 다음 승인을 거절 (카드 한도 초과 등)
 * - unavailable(): 다음 승인이 응답 없이 실패 (타임아웃 등 결과를 모르는 경우)
 * - onConfirm(hook): 승인 직후(우리 DB 반영 전) 실행. 그 사이에 다른 처리가 끼어든 상황을 흉내
 */
public class FakeTossPaymentsClient implements TossPaymentsClient {

    public record Call(String paymentKey, String orderId, long amount) {
    }

    private final List<Call> confirms = new ArrayList<>();
    private final List<String> cancels = new ArrayList<>();
    private String rejectCode;
    private boolean unavailable;
    private boolean failCancel;
    private Runnable onConfirm;

    public synchronized void reset() {
        confirms.clear();
        cancels.clear();
        rejectCode = null;
        unavailable = false;
        failCancel = false;
        onConfirm = null;
    }

    public synchronized void reject(String code) {
        this.rejectCode = code;
    }

    public synchronized void unavailable() {
        this.unavailable = true;
    }

    public synchronized void failCancel() {
        this.failCancel = true;
    }

    public synchronized void onConfirm(Runnable hook) {
        this.onConfirm = hook;
    }

    public synchronized List<Call> confirms() {
        return List.copyOf(confirms);
    }

    public synchronized List<String> cancels() {
        return List.copyOf(cancels);
    }

    @Override
    public Approval confirm(String paymentKey, String orderId, long amount) {
        Runnable hook;
        synchronized (this) {
            confirms.add(new Call(paymentKey, orderId, amount));
            if (rejectCode != null) {
                String code = rejectCode;
                rejectCode = null;
                throw new TossRejectedException(code, "테스트 거절");
            }
            if (unavailable) {
                unavailable = false;
                throw new TossUnavailableException("테스트 타임아웃", null);
            }
            hook = onConfirm;
            onConfirm = null;
        }
        if (hook != null) {
            hook.run();
        }
        return new Approval(paymentKey, orderId, "DONE", amount, OffsetDateTime.now(ZoneOffset.ofHours(9)));
    }

    @Override
    public synchronized void cancel(String paymentKey, String orderId, String reason) {
        cancels.add(paymentKey);
        if (failCancel) {
            throw new TossUnavailableException("테스트 취소 실패", null);
        }
    }
}
