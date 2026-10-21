package com.vita.chat.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.vita.search.dto.PlanReference;
import java.util.List;
import org.junit.jupiter.api.Test;

/** LLM에 넘긴 요금제 중 답변에 이름이 나온 것만, 나온 순서대로 요금제 카드가 되는지 확인한다. */
class ChatPlanCardTest {

	private static PlanReference plan(long id, String name, int fee) {
		return new PlanReference(id, "VITA-" + id, name, "요약", fee, "설명", 0.8, null,
				"LTE_5G", "GENERAL", null, null, "LIMITED", 10240L, 1000, "UNLIMITED", null, "UNLIMITED", null);
	}

	private static List<String> names(List<ChatPlanCard> cards) {
		return cards.stream().map(ChatPlanCard::name).toList();
	}

	@Test
	void keepsOnlyPlansNamedInTheAnswerInTheOrderTheyAppear() {
		List<PlanReference> context = List.of(
				plan(1, "비타 라이트 5", 25_000), plan(2, "비타 밸런스 20", 39_000), plan(3, "비타 플러스 80", 59_000));

		List<ChatPlanCard> cards = ChatPlanCard.mentionedIn(
				"비타 플러스 80을 추천드려요. 저렴한 쪽은 비타 라이트 5입니다.", context);

		assertThat(names(cards)).containsExactly("비타 플러스 80", "비타 라이트 5");
	}

	@Test
	void ignoresSpacesWhenMatchingNames() {
		List<ChatPlanCard> cards = ChatPlanCard.mentionedIn("**비타라이트5**(월 25,000원)",
				List.of(plan(1, "비타 라이트 5", 25_000)));

		assertThat(names(cards)).containsExactly("비타 라이트 5");
	}

	@Test
	void doesNotPickAShorterNameInsideALongerOne() {
		List<PlanReference> context = List.of(plan(1, "비타 맥스", 89_000), plan(2, "비타 맥스 쉐어", 99_000));

		assertThat(names(ChatPlanCard.mentionedIn("비타 맥스 쉐어는 가족과 데이터를 나눠 써요.", context)))
				.containsExactly("비타 맥스 쉐어");
		assertThat(names(ChatPlanCard.mentionedIn("비타 맥스와 비타 맥스 쉐어를 비교하면", context)))
				.containsExactly("비타 맥스", "비타 맥스 쉐어");
	}

	@Test
	void doesNotMatchWhenMoreDigitsFollowTheName() {
		List<ChatPlanCard> cards = ChatPlanCard.mentionedIn("비타 라이트 50은 없는 요금제입니다.",
				List.of(plan(1, "비타 라이트 5", 25_000)));

		assertThat(cards).isEmpty();
	}

	@Test
	void listsEachPlanOnceEvenIfNamedManyTimes() {
		List<ChatPlanCard> cards = ChatPlanCard.mentionedIn("비타 라이트 5는 … 비타 라이트 5의 데이터는 …",
				List.of(plan(1, "비타 라이트 5", 25_000)));

		assertThat(cards).hasSize(1);
		assertThat(cards.get(0).monthlyFee()).isEqualTo(25_000);
	}

	@Test
	void returnsEmptyWithoutAnAnswerOrPlans() {
		assertThat(ChatPlanCard.mentionedIn("요금제 이름 없음", List.of(plan(1, "비타 라이트 5", 25_000)))).isEmpty();
		assertThat(ChatPlanCard.mentionedIn("비타 라이트 5", List.of())).isEmpty();
		assertThat(ChatPlanCard.mentionedIn(null, List.of(plan(1, "비타 라이트 5", 25_000)))).isEmpty();
	}
}
