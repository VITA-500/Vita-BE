package com.vita.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import com.vita.chat.ChatMessageFeedback;
import com.vita.chat.ChatMessageRole;
import com.vita.chat.ReportStatus;
import com.vita.chat.UnresolvedReason;
import com.vita.chat.dto.ChatDismissResponse;
import com.vita.chat.dto.ChatStatsResponse;
import com.vita.chat.entity.ChatMessage;
import com.vita.chat.entity.UnresolvedQuestionReport;
import com.vita.chat.repository.ChatMessageRepository;
import com.vita.chat.repository.ChatMessageRepository.FeedbackCountRow;
import com.vita.chat.repository.ChatMessageRepository.FeedbackRow;
import com.vita.chat.repository.ChatMessageRepository.UnresolvedMessageRow;
import com.vita.chat.repository.UnresolvedQuestionReportRepository;
import com.vita.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** 관리자 미해결 목록: 자동 감지 항목도 무시, 같은 질문의 (좋아요)/(아쉬워요) 수 */
class ChatStatsServiceTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 18, 12, 0);

	private final ChatMessageRepository messageRepository = mock(ChatMessageRepository.class);
	private final UnresolvedQuestionReportRepository reportRepository = mock(UnresolvedQuestionReportRepository.class);
	private final ChatStatsService service = new ChatStatsService(messageRepository, reportRepository);

	private static ChatMessage answer(Long id, UnresolvedReason reason) {
		ChatMessage message = ChatMessage.builder().role(ChatMessageRole.ASSISTANT).content("답변").build();
		ReflectionTestUtils.setField(message, "id", id);
		ReflectionTestUtils.setField(message, "unresolvedReason", reason);
		return message;
	}

	private static UnresolvedMessageRow autoDetected(Long id, String question) {
		return new UnresolvedMessageRow() {
			public Long getId() { return id; }
			public String getReason() { return UnresolvedReason.NO_MATCH.name(); }
			public LocalDateTime getCreatedAt() { return NOW; }
			public String getQuestion() { return question; }
			public String getAnswer() { return "답변"; }
		};
	}

	private static UnresolvedQuestionReport disliked(ChatMessage message, String question) {
		UnresolvedQuestionReport report = UnresolvedQuestionReport.builder()
				.message(message).userId(1L).questionText(question).build();
		ReflectionTestUtils.setField(report, "createdAt", NOW);
		return report;
	}

	private static FeedbackRow feedback(String feedback, String question) {
		return new FeedbackRow() {
			public String getFeedback() { return feedback; }
			public String getQuestion() { return question; }
		};
	}

	@Test
	void dismissesAutoDetectedOnlyItem() {
		ChatMessage auto = answer(10L, UnresolvedReason.NO_MATCH);
		when(messageRepository.findUnresolvedWithQuestion()).thenReturn(List.of(autoDetected(10L, "위약금 얼마야")));
		when(messageRepository.findAllById(List.of(10L))).thenReturn(List.of(auto));

		ChatDismissResponse response = service.dismiss(10L);

		assertThat(response.dismissedCount()).isEqualTo(1);
		assertThat(auto.getUnresolvedDismissedAt()).isNotNull();
	}

	@Test
	void dismissesAutoDetectedAndDislikedInTheSameGroup() {
		ChatMessage auto = answer(10L, UnresolvedReason.NO_MATCH);
		ChatMessage dislikedAnswer = answer(11L, null);
		UnresolvedQuestionReport report = disliked(dislikedAnswer, "위약금 얼마야?");
		when(messageRepository.findUnresolvedWithQuestion()).thenReturn(List.of(autoDetected(10L, "위약금 얼마야")));
		when(reportRepository.findAllByStatus(ReportStatus.OPEN)).thenReturn(List.of(report));
		when(reportRepository.findAllByMessageIdInAndStatus(any(), eq(ReportStatus.OPEN))).thenReturn(List.of(report));
		when(messageRepository.findAllById(List.of(10L, 11L))).thenReturn(List.of(auto, dislikedAnswer));

		ChatDismissResponse response = service.dismiss(11L);

		assertThat(response.dismissedCount()).isEqualTo(2);
		assertThat(report.getStatus()).isEqualTo(ReportStatus.DISMISSED);
		assertThat(auto.getUnresolvedDismissedAt()).isNotNull();
		// (아쉬워요)만 받은 답변은 자동 감지가 아니라 무시 시각을 남기지 않는다
		assertThat(dislikedAnswer.getUnresolvedDismissedAt()).isNull();
	}

	@Test
	void rejectsUnknownItem() {
		assertThatThrownBy(() -> service.dismiss(999L)).isInstanceOf(BusinessException.class);
	}

	@Test
	void countsLikesAndDislikesOfTheSameQuestion() {
		when(messageRepository.findUnresolvedWithQuestion()).thenReturn(List.of(autoDetected(10L, "위약금 얼마야")));
		when(messageRepository.findFeedbackWithQuestion()).thenReturn(List.of(
				feedback("LIKE", "위약금 얼마야?"),
				feedback("LIKE", "위약금  얼마야"),
				feedback("DISLIKE", "위약금 얼마야"),
				feedback("LIKE", "로밍 요금 알려줘"),
				feedback("LIKE", null)));

		ChatStatsResponse.Candidate candidate = service.getUnresolvedStats(null, null).candidates().get(0);

		assertThat(candidate.likeCount()).isEqualTo(2);
		assertThat(candidate.dislikeCount()).isEqualTo(1);
	}

	@Test
	void zeroFeedbackWhenNoneReceived() {
		when(messageRepository.findUnresolvedWithQuestion()).thenReturn(List.of(autoDetected(10L, "위약금 얼마야")));

		ChatStatsResponse.Candidate candidate = service.getUnresolvedStats(null, null).candidates().get(0);

		assertThat(candidate.likeCount()).isZero();
		assertThat(candidate.dislikeCount()).isZero();
		assertThat(candidate.comments()).isEmpty();
	}

	@Test
	void showsRecentCommentsOfTheGroup() {
		List<UnresolvedQuestionReport> reports = new java.util.ArrayList<>();
		for (int i = 1; i <= 7; i++) {
			UnresolvedQuestionReport report = disliked(answer(10L + i, null), "위약금 얼마야");
			ReflectionTestUtils.setField(report, "createdAt", NOW.plusMinutes(i));
			// 6번째는 이유 없이 (아쉬워요)만 누름
			ReflectionTestUtils.setField(report, "comment", i == 6 ? null : "이유 " + i);
			reports.add(report);
		}
		when(reportRepository.findAllByStatus(ReportStatus.OPEN)).thenReturn(reports);

		ChatStatsResponse.Candidate candidate = service.getUnresolvedStats(null, null).candidates().get(0);

		assertThat(candidate.count()).isEqualTo(7);
		assertThat(candidate.comments()).containsExactly("이유 7", "이유 5", "이유 4", "이유 3", "이유 2");
	}

	private static UnresolvedMessageRow detected(Long id, String question, UnresolvedReason reason, LocalDateTime at) {
		return new UnresolvedMessageRow() {
			public Long getId() { return id; }
			public String getReason() { return reason.name(); }
			public LocalDateTime getCreatedAt() { return at; }
			public String getQuestion() { return question; }
			public String getAnswer() { return "답변"; }
		};
	}

	private static FeedbackCountRow feedbackCount(String feedback, long count) {
		return new FeedbackCountRow() {
			public String getFeedback() { return feedback; }
			public long getCount() { return count; }
		};
	}

	private void givenMixedReasons() {
		LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Seoul"));
		when(messageRepository.findUnresolvedWithQuestion()).thenReturn(List.of(
				detected(10L, "위약금 얼마야", UnresolvedReason.NO_MATCH, now.minusDays(3)),
				detected(11L, "로밍 요금", UnresolvedReason.LOW_SIMILARITY, now),
				detected(12L, "로밍 요금?", UnresolvedReason.ANSWER_FAILED, now.minusDays(1))));
		// 자동 감지된 답변(10)에 (아쉬워요)까지 받음
		UnresolvedQuestionReport report = disliked(answer(10L, UnresolvedReason.NO_MATCH), "위약금 얼마야");
		ReflectionTestUtils.setField(report, "createdAt", now);
		when(reportRepository.findAllByStatus(ReportStatus.OPEN)).thenReturn(List.of(report));
	}

	@Test
	void countsEveryOccurrenceByReason() {
		givenMixedReasons();

		ChatStatsResponse.Summary summary = service.getUnresolvedStats(null, null).summary();

		assertThat(summary.reasonCounts()).containsExactly(
				entry("NO_MATCH", 1L), entry("LOW_SIMILARITY", 1L), entry("ANSWER_FAILED", 1L), entry("DISLIKED", 1L));
		assertThat(summary.todayReasonCounts()).containsExactly(
				entry("NO_MATCH", 0L), entry("LOW_SIMILARITY", 1L), entry("ANSWER_FAILED", 0L), entry("DISLIKED", 1L));
	}

	@Test
	void filtersItemsContainingTheReasonButKeepsSummaryWhole() {
		givenMixedReasons();

		ChatStatsResponse noMatch = service.getUnresolvedStats(null, "NO_MATCH");
		ChatStatsResponse failed = service.getUnresolvedStats(null, "answer_failed");

		// 최근 사유가 DISLIKED여도 묶음 안에 NO_MATCH가 있으면 나온다
		assertThat(noMatch.candidates()).extracting(ChatStatsResponse.Candidate::question).containsExactly("위약금 얼마야");
		assertThat(noMatch.candidates().get(0).failReason()).isEqualTo("DISLIKED");
		assertThat(failed.candidates()).extracting(ChatStatsResponse.Candidate::question).containsExactly("로밍 요금");
		assertThat(noMatch.summary().unresolvedCount()).isEqualTo(2);
		assertThat(service.getUnresolvedStats(null, "DISLIKED").candidates()).hasSize(1);
	}

	@Test
	void rejectsUnknownReason() {
		assertThatThrownBy(() -> service.getUnresolvedStats(null, "WRONG")).isInstanceOf(BusinessException.class);
	}

	@Test
	void countsTodayFeedbackOfAllAnswers() {
		when(messageRepository.countFeedbackBetween(any(), any()))
				.thenReturn(List.of(feedbackCount("LIKE", 7), feedbackCount("DISLIKE", 2)));

		ChatStatsResponse.Summary summary = service.getUnresolvedStats(null, null).summary();

		assertThat(summary.todayLikeCount()).isEqualTo(7);
		assertThat(summary.todayDislikeCount()).isEqualTo(2);
	}

	@Test
	void feedbackTimeChangesOnlyWhenFeedbackChanges() {
		ChatMessage message = answer(10L, null);

		message.applyFeedback(ChatMessageFeedback.DISLIKE);
		LocalDateTime pressedAt = message.getFeedbackAt();
		ReflectionTestUtils.setField(message, "feedbackAt", pressedAt.minusHours(1));
		message.applyFeedback(ChatMessageFeedback.DISLIKE);   // 이유만 바꿀 때
		assertThat(message.getFeedbackAt()).isEqualTo(pressedAt.minusHours(1));

		message.applyFeedback(ChatMessageFeedback.LIKE);
		assertThat(message.getFeedbackAt()).isAfter(pressedAt.minusHours(1));

		message.applyFeedback(null);
		assertThat(message.getFeedbackAt()).isNull();
	}
}
