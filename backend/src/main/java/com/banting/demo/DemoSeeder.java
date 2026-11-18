package com.banting.demo;

import com.banting.common.geo.GeoPoint;
import com.banting.groupbuy.PriceType;
import com.banting.groupbuy.Pricing;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 체험 동네 시드 공구를 넣고, 매일 마감일을 다시 미래로 옮긴다 (T09.5, tech-design 4장 "시드 데이터와 체험 모드").
 *
 * <p>왜 Flyway가 아니라 시더인가: 데모 데이터는 스키마 이력이 아니고, 마감일처럼 매일 바뀌어야 하는 값이 있다.
 * {@code banting.demo.seed=true}일 때만 등록되어 테스트·다른 환경에 섞이지 않는다.
 *
 * <p>멱등: 같은 데모 개설자 + 같은 상품 URL의 시드가 있으면 새로 만들지 않고 마감일·수령 일정만 갱신한다.
 * 공구 id가 그대로라 공유된 링크가 깨지지 않는다. 기동할 때 한 번 + 매일 04:30(DB 백업 04:00과 겹치지 않게).
 * 서버를 며칠 꺼 둬도 다음 기동 때 바로 미래로 맞춰진다.
 *
 * <p>시드는 돈이 오간 적이 없으므로 결제(payments) 행은 만들지 않는다. 참여 행은 실제 모델 그대로 넣어
 * reserved_units·paid_units와 참여 수량 합이 맞게 한다. 참여·환불·정산 로직은 시드 공구를 대상에서 뺀다 (T11~T16).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "banting.demo.seed", havingValue = "true")
public class DemoSeeder implements ApplicationRunner {

    static final String HOST_EMAIL = "seed-host@demo.banting.invalid";
    static final List<String> NEIGHBOR_EMAILS = List.of(
            "seed-neighbor1@demo.banting.invalid", "seed-neighbor2@demo.banting.invalid");
    private static final String HOST_NICKNAME = "반띵예시";
    private static final List<String> NEIGHBOR_NICKNAMES = List.of("예시이웃1", "예시이웃2");
    /** BCrypt 형식이 아니라서 어떤 비밀번호와도 일치하지 않는다 → 데모 회원으로는 로그인할 수 없음. */
    static final String NO_LOGIN_PASSWORD_HASH = "!demo-account-login-disabled";
    private static final LocalTime DEADLINE_TIME = LocalTime.of(21, 0);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;

    public DemoSeeder(JdbcTemplate jdbc, TransactionTemplate tx, Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        runQuietly();
    }

    @Scheduled(cron = "0 30 4 * * *", zone = "Asia/Seoul")
    public void daily() {
        runQuietly();
    }

    /** 시드는 부가 기능이라 실패해도 앱 기동(=배포 헬스체크)을 막지 않는다. 로그로만 남긴다. */
    private void runQuietly() {
        try {
            seedOrRefresh();
        } catch (RuntimeException e) {
            log.error("데모 시드 실패 (서비스는 계속 동작)", e);
        }
    }

    /** 반환: [새로 만든 수, 갱신한 수]. */
    int[] seedOrRefresh() {
        LocalDateTime now = LocalDateTime.now(clock);
        int[] counts = tx.execute(status -> {
            long hostId = ensureUser(HOST_EMAIL, HOST_NICKNAME, now);
            List<Long> neighborIds = new ArrayList<>();
            for (int i = 0; i < NEIGHBOR_EMAILS.size(); i++) {
                neighborIds.add(ensureUser(NEIGHBOR_EMAILS.get(i), NEIGHBOR_NICKNAMES.get(i), now));
            }
            int created = 0;
            int refreshed = 0;
            for (SeedGroupBuys.Item item : SeedGroupBuys.ITEMS) {
                LocalDateTime deadline = deadlineFor(item, now.toLocalDate());
                List<Long> existing = jdbc.queryForList(
                        "SELECT id FROM group_buys WHERE host_id = ? AND product_url = ? AND demo_type = 'SEED'",
                        Long.class, hostId, item.productUrl());
                if (existing.isEmpty()) {
                    insert(item, hostId, neighborIds, deadline, now);
                    created++;
                } else {
                    refresh(existing.get(0), deadline, now);
                    refreshed++;
                }
            }
            return new int[] {created, refreshed};
        });
        log.info("데모 시드: 새로 {}개, 마감일 갱신 {}개", counts[0], counts[1]);
        return counts;
    }

    static LocalDateTime deadlineFor(SeedGroupBuys.Item item, LocalDate today) {
        return today.atTime(DEADLINE_TIME).plusDays(item.deadlineDays());
    }

    /** 수령 후보 i번째: 마감 다음 날부터 하루씩, 저녁 7시 반. 항상 마감 이후 14일 안. */
    static LocalDateTime pickupAt(LocalDateTime deadline, int index) {
        return deadline.toLocalDate().plusDays(index + 1L).atTime(19, 30);
    }

    private long ensureUser(String email, String nickname, LocalDateTime now) {
        List<Long> ids = jdbc.queryForList("SELECT id FROM users WHERE email = ?", Long.class, email);
        if (!ids.isEmpty()) {
            return ids.get(0);
        }
        jdbc.update("INSERT INTO users (email, password_hash, nickname, role, search_radius_m, created_at, updated_at) "
                + "VALUES (?, ?, ?, 'USER', 1000, ?, ?)", email, NO_LOGIN_PASSWORD_HASH, nickname, now, now);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private void insert(SeedGroupBuys.Item item, long hostId, List<Long> neighborIds, LocalDateTime deadline,
                        LocalDateTime now) {
        Pricing pricing = item.priceType() == PriceType.EQUAL
                ? Pricing.equal(item.totalPrice(), item.capacity())
                : Pricing.byQuantity(item.totalPrice(), item.totalQuantity());
        int filled = item.hostUnits() + item.otherUnits().stream().mapToInt(Integer::intValue).sum();

        jdbc.update("""
                INSERT INTO group_buys (host_id, title, description, category, product_url, price_type, total_price,
                    total_quantity, unit_label, capacity_units, min_units_per_person, reserved_units, paid_units,
                    status, demo_type, location, neighborhood_name, deadline_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'OPEN', 'SEED', ST_PointFromText(?, 4326), ?, ?, ?, ?)
                """, hostId, item.title(), item.description(), item.category().name(), item.productUrl(),
                item.priceType().name(), item.totalPrice(), item.totalQuantity(), item.unitLabel(), item.capacity(),
                item.minUnitsPerPerson(), filled, filled,
                new GeoPoint(item.latitude(), item.longitude()).rounded().toWkt(), item.neighborhoodName(),
                deadline, now, now);
        long groupBuyId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        insertParticipation(groupBuyId, hostId, true, item.hostUnits(), pricing.hostAmount(item.hostUnits()), now);
        for (int i = 0; i < item.otherUnits().size(); i++) {
            int units = item.otherUnits().get(i);
            insertParticipation(groupBuyId, neighborIds.get(i), false, units, pricing.participantAmount(units), now);
        }
        for (int i = 0; i < item.pickupPlaces().size(); i++) {
            jdbc.update("INSERT INTO pickup_options (group_buy_id, pickup_at, place_name, created_at, updated_at) "
                    + "VALUES (?, ?, ?, ?, ?)", groupBuyId, pickupAt(deadline, i), item.pickupPlaces().get(i), now, now);
        }
        jdbc.update("INSERT INTO group_buy_images (group_buy_id, url, sort_order, created_at, updated_at) "
                + "VALUES (?, ?, 0, ?, ?)", groupBuyId, item.image(), now, now);
    }

    private void insertParticipation(long groupBuyId, long userId, boolean host, int units, long amount,
                                     LocalDateTime now) {
        jdbc.update("""
                INSERT INTO participations (group_buy_id, user_id, is_host, units, amount, status, expires_at,
                    created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'PAID', ?, ?, ?)
                """, groupBuyId, userId, host, units, amount, now, now, now);
    }

    /** 마감일과 수령 일정만 다시 미래로. 진행률·사진 등은 그대로. */
    private void refresh(long groupBuyId, LocalDateTime deadline, LocalDateTime now) {
        jdbc.update("UPDATE group_buys SET deadline_at = ?, updated_at = ? WHERE id = ?", deadline, now, groupBuyId);
        List<Long> optionIds = jdbc.queryForList(
                "SELECT id FROM pickup_options WHERE group_buy_id = ? ORDER BY id", Long.class, groupBuyId);
        for (int i = 0; i < optionIds.size(); i++) {
            jdbc.update("UPDATE pickup_options SET pickup_at = ?, updated_at = ? WHERE id = ?",
                    pickupAt(deadline, i), now, optionIds.get(i));
        }
    }
}
