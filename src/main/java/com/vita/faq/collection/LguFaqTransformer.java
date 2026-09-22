package com.vita.faq.collection;

import org.springframework.stereotype.Component;

import java.util.Optional;

/** LG U+ FAQ 상세 응답을 검증하고 정제된 FAQ 데이터로 변환한다. */
@Component
public class LguFaqTransformer {

	private final LguFaqHtmlCleaner htmlCleaner;

	public LguFaqTransformer(LguFaqHtmlCleaner htmlCleaner) {
		this.htmlCleaner = htmlCleaner;
	}

	public Optional<LguFaqCollectedRecord> transform(
		LguFaqDetailResponse detail,
		LguFaqCategorySelection categorySelection
	) {
		if (detail == null || isBlank(detail.kbId()) || categorySelection == null
			|| isBlank(categorySelection.mainCategoryName()) || isBlank(categorySelection.subcategoryName())) {
			return Optional.empty();
		}

		String question = toSingleLine(htmlCleaner.clean(detail.title()));
		String answer = htmlCleaner.clean(detail.contents());
		if (question.isBlank() || answer.isBlank()) {
			return Optional.empty();
		}

		return Optional.of(new LguFaqCollectedRecord(
			detail.kbId().strip(),
			categorySelection.mainCategoryName(),
			categorySelection.subcategoryName(),
			question,
			answer,
			resolveSourcePath(detail)
		));
	}

	private String resolveSourcePath(LguFaqDetailResponse detail) {
		if (!isBlank(detail.pathNodeStr())) {
			return detail.pathNodeStr().strip();
		}
		if (detail.pathNode() == null) {
			return "";
		}
		return detail.pathNode().stream()
			.filter(node -> !isBlank(node))
			.map(String::strip)
			.reduce((left, right) -> left + " > " + right)
			.orElse("");
	}

	private String toSingleLine(String value) {
		return value.replaceAll("\\s+", " ").strip();
	}

	private boolean isBlank(String value) {
		return value == null || value.isBlank();
	}
}
