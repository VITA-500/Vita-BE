package com.vita.faq.collection;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LguFaqHtmlCleanerTest {

	private final LguFaqHtmlCleaner cleaner = new LguFaqHtmlCleaner();

	@Test
	void cleansFaqAnswerHtml() {
		String html = """
			<span>보험료 전액을 U+가 대신 내드려요.<br />&nbsp;<br />
			<a href="https://example.com">자세히 보기</a></span>
			""";

		String result = cleaner.clean(html);

		assertThat(result).isEqualTo("보험료 전액을 U+가 대신 내드려요.\n\n자세히 보기");
	}

	@Test
	void preservesLineBreaksAndLinkText() {
		String html = """
			<div>신청 방법</div>
			<ol><li>U+one 앱에 접속하세요.</li><li><a href="https://example.com/app">신청 화면</a>을 선택하세요.</li></ol>
			""";

		String result = cleaner.clean(html);

		assertThat(result).isEqualTo("신청 방법\nU+one 앱에 접속하세요.\n신청 화면을 선택하세요.");
	}

	@Test
	void doesNotInsertSpacesBetweenInlineElements() {
		String html = "<span>LTE 음</span><strong>성통화</strong> 로밍을 이용할 수 있어요.";

		String result = cleaner.clean(html);

		assertThat(result).isEqualTo("LTE 음성통화 로밍을 이용할 수 있어요.");
	}

	@Test
	void returnsEmptyTextForBlankHtml() {
		assertThat(cleaner.clean(null)).isEmpty();
		assertThat(cleaner.clean("   ")).isEmpty();
	}
}
