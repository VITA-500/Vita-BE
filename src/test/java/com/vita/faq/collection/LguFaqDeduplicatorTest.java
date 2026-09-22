package com.vita.faq.collection;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LguFaqDeduplicatorTest {

	private final LguFaqDeduplicator deduplicator = new LguFaqDeduplicator();

	@Test
	void deduplicatesBySourceFaqId() {
		LguFaqCollectedRecord first = faq("KNOW-1", "첫 번째 답변");
		LguFaqCollectedRecord duplicate = faq("KNOW-1", "나중 답변");

		LguFaqDeduplicationResult result = deduplicator.deduplicate(List.of(first, duplicate));

		assertThat(result.uniqueFaqs()).containsExactly(first);
		assertThat(result.duplicateSourceFaqIds()).containsExactly("KNOW-1");
	}

	@Test
	void reportsSameQuestionWithDifferentIdsWithoutDeletingIt() {
		LguFaqCollectedRecord first = faq("KNOW-1", "첫 번째 답변");
		LguFaqCollectedRecord second = faq("KNOW-2", "두 번째 답변");

		LguFaqDeduplicationResult result = deduplicator.deduplicate(List.of(first, second));

		assertThat(result.uniqueFaqs()).containsExactly(first, second);
		assertThat(result.duplicateQuestionCandidates())
			.containsKey("같은 질문입니다.");
		assertThat(result.duplicateQuestionCandidates().get("같은 질문입니다."))
			.containsExactly(first, second);
	}

	@Test
	void returnsEmptyResultForNoFaqs() {
		LguFaqDeduplicationResult result = deduplicator.deduplicate(List.of());

		assertThat(result.uniqueFaqs()).isEmpty();
		assertThat(result.duplicateSourceFaqIds()).isEmpty();
		assertThat(result.duplicateQuestionCandidates()).isEmpty();
	}

	private LguFaqCollectedRecord faq(String sourceFaqId, String answer) {
		return new LguFaqCollectedRecord(
			sourceFaqId,
			"요금/납부",
			"요금조회",
			"같은 질문입니다.",
			answer,
			"개인 > 요금 및 납부 > 요금조회"
		);
	}
}
