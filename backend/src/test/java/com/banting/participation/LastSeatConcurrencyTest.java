package com.banting.participation;

import static org.assertj.core.api.Assertions.assertThat;

import com.banting.TestcontainersConfiguration;
import com.banting.common.BusinessException;
import com.banting.common.geo.GeoPoint;
import com.banting.support.DatabaseCleaner;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 마지막 자리 동시성 (T13 완료 조건: 동시 100명 → 정원 초과 0건).
 *
 * <p>서로 다른 회원 100명이 출발선(CountDownLatch)에 모였다가 한꺼번에 같은 공구에 참여한다.
 * 정확히 남은 자리만큼만 성공하고, 나머지는 전부 "자리가 없어요"(GROUP_BUY_FULL)여야 한다.
 * 그 밖의 예외(데드락, 락 대기 초과, CHECK 위반 등)가 하나라도 나오면 실패 = 사용자에게 500.
 *
 * <p>자리 잡는 방식마다 하위 클래스가 설정값만 바꿔 같은 시나리오를 돌린다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
abstract class LastSeatConcurrencyTest {

    private static final int USERS = 100;
    private static final LocalDateTime NOW =
            LocalDateTime.now(ZoneId.of("Asia/Seoul")).truncatedTo(ChronoUnit.SECONDS);

    @Autowired
    ParticipationService participationService;

    @Autowired
    JdbcTemplate jdbc;

    private long hostId;
    private List<Long> userIds;

    @BeforeEach
    void setUp() {
        DatabaseCleaner.clean(jdbc);
        hostId = insertUser("host");
        userIds = new ArrayList<>();
        for (int i = 0; i < USERS; i++) {
            userIds.add(insertUser("u" + i));
        }
    }

    @Test
    void 균등_남은_5자리에_100명이_동시에_참여하면_정확히_5명만_성공한다() throws Exception {
        // 6명 균등, 개설자 1자리 결제됨 → 남은 5자리
        long id = insertOpenGroupBuy("EQUAL", 6, 1);

        Result result = runConcurrently(id, 1);

        assertThat(result.unexpected()).as("자리 없음 외의 오류 (500)").isEmpty();
        assertThat(result.success()).isEqualTo(5);
        assertThat(result.full()).isEqualTo(USERS - 5);
        assertSeatsConsistent(id, 6);
    }

    @Test
    void 수량별_남은_3개에_2개씩_100명이_동시에_요청하면_1명만_성공한다() throws Exception {
        // 10개, 최소 2개, 개설자 7개 → 남은 3개. 2개씩이면 한 명만 들어갈 수 있다
        long id = insertOpenGroupBuy("BY_QUANTITY", 10, 7);

        Result result = runConcurrently(id, 2);

        assertThat(result.unexpected()).as("자리 없음 외의 오류 (500)").isEmpty();
        assertThat(result.success()).isEqualTo(1);
        assertThat(result.full()).isEqualTo(USERS - 1);
        assertThat(jdbc.queryForObject("SELECT reserved_units FROM group_buys WHERE id = ?", Integer.class, id))
                .isEqualTo(9);
    }

    // ───── 헬퍼 ─────

    record Result(int success, int full, Map<String, Integer> unexpected) {
    }

    private Result runConcurrently(long groupBuyId, int units) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(USERS);
        CountDownLatch ready = new CountDownLatch(USERS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(USERS);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger full = new AtomicInteger();
        Map<String, Integer> unexpected = new ConcurrentHashMap<>();

        for (Long userId : userIds) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    participationService.participate(userId, groupBuyId, units);
                    success.incrementAndGet();
                } catch (BusinessException e) {
                    if (e.getErrorCode() == com.banting.common.ErrorCode.GROUP_BUY_FULL) {
                        full.incrementAndGet();
                    } else {
                        unexpected.merge(e.getErrorCode().name(), 1, Integer::sum);
                    }
                } catch (Throwable e) {
                    unexpected.merge(e.getClass().getSimpleName(), 1, Integer::sum);
                } finally {
                    done.countDown();
                }
            });
        }
        ready.await();
        start.countDown();
        assertThat(done.await(60, TimeUnit.SECONDS)).as("60초 안에 모두 끝남").isTrue();
        pool.shutdown();
        return new Result(success.get(), full.get(), unexpected);
    }

    /** 잡힌 자리 = 정원, 참여 행의 자리 합 = 잡힌 자리 (카운터와 실제 행이 어긋나지 않음). */
    private void assertSeatsConsistent(long groupBuyId, int capacity) {
        Integer reserved = jdbc.queryForObject("SELECT reserved_units FROM group_buys WHERE id = ?", Integer.class,
                groupBuyId);
        Integer rowUnits = jdbc.queryForObject("""
                SELECT SUM(units) FROM participations
                WHERE group_buy_id = ? AND status IN ('PENDING_PAYMENT', 'PAID')
                """, Integer.class, groupBuyId);
        assertThat(reserved).isEqualTo(capacity);
        assertThat(rowUnits).isEqualTo(capacity);
    }

    private long insertUser(String name) {
        jdbc.update("""
                INSERT INTO users (email, password_hash, nickname, role, created_at, updated_at)
                VALUES (?, 'x', ?, 'USER', ?, ?)
                """, name + "@test.com", name, NOW, NOW);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    /** 모집 중 공구 + 개설자 결제 완료 참여. 1단위 1,000원. */
    private long insertOpenGroupBuy(String priceType, int capacity, int hostUnits) {
        int minUnits = "EQUAL".equals(priceType) ? 1 : 2;
        jdbc.update("""
                INSERT INTO group_buys (host_id, title, description, category, product_url, price_type,
                    total_price, total_quantity, unit_label, capacity_units, min_units_per_person,
                    reserved_units, paid_units, status, location, neighborhood_name, deadline_at, created_at, updated_at)
                VALUES (?, '휴지 30롤', '반띵해요', 'LIVING', 'https://example.com/p', ?, ?, ?, '롤', ?, ?, ?, ?, 'OPEN',
                    ST_PointFromText(?, 4326), '중구 명동', ?, ?, ?)
                """, hostId, priceType, capacity * 1_000L, capacity, capacity, minUnits, hostUnits, hostUnits,
                new GeoPoint(37.5665, 126.978).toWkt(), NOW.plusDays(2), NOW, NOW);
        long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO participations (group_buy_id, user_id, is_host, units, amount, status, expires_at,
                    created_at, updated_at)
                VALUES (?, ?, TRUE, ?, ?, 'PAID', ?, ?, ?)
                """, id, hostId, hostUnits, hostUnits * 1_000L, NOW.plusMinutes(10), NOW, NOW);
        return id;
    }
}

/** 조건부 UPDATE (기본 방식). */
class ConditionalUpdateLastSeatTest extends LastSeatConcurrencyTest {
}

/** 비관적 락 (비교용). */
@org.springframework.test.context.TestPropertySource(properties = "banting.participation.seat-lock=PESSIMISTIC")
class PessimisticLockLastSeatTest extends LastSeatConcurrencyTest {
}
