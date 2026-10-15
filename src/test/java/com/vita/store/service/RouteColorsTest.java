package com.vita.store.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 길찾기 구간 색. 노선명은 "포함"으로 찾으므로 비슷한 이름끼리 섞이지 않는지 확인 */
class RouteColorsTest {

    @Test
    @DisplayName("인천1호선은 1호선 색이 아니라 인천1호선 색")
    void longerLineNameWins() {
        assertThat(RouteColors.transit("SUBWAY", "인천1호선", null))
                .isNotEqualTo(RouteColors.transit("SUBWAY", "1호선", null));
        assertThat(RouteColors.transit("SUBWAY", "수도권 1호선", null))
                .isEqualTo(RouteColors.transit("SUBWAY", "1호선", null));
    }

    @Test
    @DisplayName("수인분당선과 분당선은 같은 색")
    void bundangLines() {
        assertThat(RouteColors.transit("SUBWAY", "수인분당선", null))
                .isEqualTo(RouteColors.transit("SUBWAY", "분당선", null));
    }

    @Test
    @DisplayName("버스는 노선 번호가 아니라 차량 종류로 색을 정한다")
    void busUsesVehicleType() {
        assertThat(RouteColors.transit("BUS", "470", "간선"))
                .isEqualTo(RouteColors.transit("BUS", "9999", "간선"))
                .isNotEqualTo(RouteColors.transit("BUS", "470", "지선"));
    }

    @Test
    @DisplayName("모르는 노선·종류, 값 없음은 같은 기본색")
    void unknownFallsBackToDefault() {
        String fallback = RouteColors.transit("SUBWAY", "없는선", null);

        assertThat(RouteColors.transit("SUBWAY", null, null)).isEqualTo(fallback);
        assertThat(RouteColors.transit("BUS", "470", "모르는종류")).isEqualTo(fallback);
        assertThat(RouteColors.transit("FERRY", null, null)).isEqualTo(fallback);
    }

    @Test
    @DisplayName("환승 도보는 도보 색")
    void walking() {
        assertThat(RouteColors.transit("WALKING", null, null)).isEqualTo(RouteColors.WALK);
    }
}
