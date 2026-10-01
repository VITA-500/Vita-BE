package com.vita.search.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.vita.search.dto.PlanReference;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** findExtremeForQuery가 질문에서 읽은 대상 그룹으로 조회 범위를 정하는지 검증한다. */
class PlanLookupServiceTest {

	/** 호출된 조회 범위만 기록하는 가짜 구현. */
	private static final class RecordingLookup implements PlanLookupService {
		final List<String> groups = new ArrayList<>();

		@Override
		public List<PlanReference> findExtreme(PlanSortKey sortKey, int limit, String targetGroup) {
			groups.add(targetGroup);
			return List.of();
		}
	}

	private static String groupUsedFor(String query) {
		RecordingLookup lookup = new RecordingLookup();
		lookup.findExtremeForQuery(PlanSortKey.CHEAPEST, 1, query);
		return lookup.groups.get(0);
	}

	@Test
	void narrowsToTheGroupNamedInTheQuestion() {
		assertThat(groupUsedFor("워치 요금제 중에 제일 싼 거 알려줘")).isEqualTo("WATCH");
		assertThat(groupUsedFor("태블릿 요금제 중 데이터 가장 많이 주는 건?")).isEqualTo("TABLET");
		assertThat(groupUsedFor("청년 요금제 중에 가장 저렴한 거 알려줘")).isEqualTo("YOUTH");
		assertThat(groupUsedFor("시니어 요금제 중 제일 싼 요금제가 뭐예요?")).isEqualTo("SENIOR");
	}

	@Test
	void fallsBackToGeneralWhenNoSingleGroupIsNamed() {
		assertThat(groupUsedFor("가장 저렴한 요금제 알려줘")).isEqualTo("GENERAL");
		assertThat(groupUsedFor("청년이랑 시니어 중 제일 싼 요금제")).isEqualTo("GENERAL");
		assertThat(groupUsedFor(null)).isEqualTo("GENERAL");
	}

	@Test
	void keepsTheTwoArgumentVersionOnGeneralPlans() {
		RecordingLookup lookup = new RecordingLookup();
		lookup.findExtreme(PlanSortKey.CHEAPEST, 1);
		assertThat(lookup.groups).containsExactly("GENERAL");
	}
}
