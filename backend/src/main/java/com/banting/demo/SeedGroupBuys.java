package com.banting.demo;

import com.banting.groupbuy.Category;
import com.banting.groupbuy.PriceType;
import java.util.List;

/**
 * 체험 동네(서울시청 1km) 예시 공구 8개 (T09.5). 비로그인 첫 화면이 비어 보이지 않게 하는 용도.
 *
 * <p>productUrl은 시드를 알아보는 키다 (같은 데모 개설자 + 같은 URL이면 이미 있는 시드로 보고 갱신만).
 * 좌표는 저장 규칙대로 소수점 셋째 자리, 사진은 프론트 정적 파일(frontend/public/demo/)이라 키가 "demo/..."다.
 */
final class SeedGroupBuys {

    private SeedGroupBuys() {
    }

    /**
     * @param capacity      균등이면 인원 수, 수량별이면 총수량 (= capacity_units)
     * @param hostUnits     개설자 몫 (균등은 1)
     * @param otherUnits    다른 참여자들의 몫. 진행률이 제각각으로 보이게
     * @param deadlineDays  오늘 21시 기준 며칠 뒤 마감인지 (매일 갱신되어 항상 미래)
     */
    record Item(String key, String title, String description, Category category, PriceType priceType,
                long totalPrice, int totalQuantity, String unitLabel, int capacity, int minUnitsPerPerson,
                int hostUnits, List<Integer> otherUnits, int deadlineDays, double latitude, double longitude,
                String neighborhoodName, String image, List<String> pickupPlaces) {

        String productUrl() {
            return "https://example.com/banting-demo/" + key;
        }
    }

    static final List<Item> ITEMS = List.of(
            new Item("tissue", "3겹 화장지 30롤", "30롤은 혼자 쓰기엔 너무 많아서 3명이 10롤씩 나눠요.",
                    Category.LIVING, PriceType.EQUAL, 19_800, 30, "롤", 3, 1, 1, List.of(1), 1,
                    37.567, 126.979, "중구 명동", "demo/tissue.webp", List.of("명동역 6번 출구", "시청역 4번 출구")),
            new Item("water", "생수 2L 24병", "6병 단위로 나눠요. 무거우니 가까운 곳에서 받아요.",
                    Category.FOOD, PriceType.BY_QUANTITY, 12_900, 24, "병", 24, 6, 6, List.of(6), 2,
                    37.565, 126.982, "중구 명동", "demo/water.webp", List.of("명동성당 입구", "을지로입구역 5번 출구")),
            new Item("kitchen-towel", "키친타월 12롤", "6롤씩 둘이서 반띵해요.",
                    Category.KITCHEN, PriceType.EQUAL, 13_800, 12, "롤", 2, 1, 1, List.of(), 3,
                    37.569, 126.977, "종로구 종로1·2·3·4가동", "demo/kitchen-towel.webp", List.of("광화문역 5번 출구")),
            new Item("detergent", "세탁세제 리필 4개입", "리필 1개씩 4명이 나눠요.",
                    Category.LIVING, PriceType.EQUAL, 31_600, 4, "개", 4, 1, 1, List.of(1, 1), 4,
                    37.564, 126.975, "중구 소공동", "demo/detergent.webp", List.of("시청역 7번 출구", "덕수궁 대한문 앞")),
            new Item("chicken", "냉동 닭가슴살 30팩", "5팩 단위로 원하는 만큼 가져가요.",
                    Category.FOOD, PriceType.BY_QUANTITY, 39_000, 30, "팩", 30, 5, 10, List.of(10), 2,
                    37.562, 126.981, "중구 회현동", "demo/chicken.webp", List.of("회현역 3번 출구")),
            new Item("zipper-bag", "지퍼백 대형 100매", "20매 단위로 나눠요.",
                    Category.KITCHEN, PriceType.BY_QUANTITY, 9_900, 100, "매", 100, 20, 40, List.of(), 5,
                    37.570, 126.982, "종로구 종로1·2·3·4가동", "demo/zipper-bag.webp", List.of("종각역 4번 출구", "청계천 광교 앞")),
            new Item("battery", "AA 건전지 40개입", "10개씩 나눠요. 거의 다 모였어요!",
                    Category.ETC, PriceType.BY_QUANTITY, 16_000, 40, "개", 40, 10, 10, List.of(10, 10), 3,
                    37.566, 126.974, "중구 소공동", "demo/battery.webp", List.of("시청역 1번 출구")),
            new Item("wet-wipes", "캡형 물티슈 10팩", "5팩씩 둘이서 나눠요.",
                    Category.LIVING, PriceType.EQUAL, 15_900, 10, "팩", 2, 1, 1, List.of(), 6,
                    37.563, 126.978, "중구 명동", "demo/wet-wipes.webp", List.of("명동역 8번 출구", "남대문시장 입구")));
}
