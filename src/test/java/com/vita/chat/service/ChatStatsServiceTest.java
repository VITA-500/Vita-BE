package com.vita.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import com.vita.chat.ChatMessageRole;
import com.vita.chat.ReportStatus;
import com.vita.chat.UnresolvedReason;
import com.vita.chat.dto.ChatDismissResponse;
import com.vita.chat.dto.ChatStatsResponse;
import com.vita.chat.entity.ChatMessage;
import com.vita.chat.entity.UnresolvedQuestionReport;
import com.vita.chat.repository.ChatMessageRepository;
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

		ChatStatsResponse.Candidate candidate = service.getUnresolvedStats(null).candidates().get(0);

		assertThat(candidate.likeCount()).isEqualTo(2);
		assertThat(candidate.dislikeCount()).isEqualTo(1);
	}

	@Test
	void zeroFeedbackWhenNoneReceived() {
		when(messageRepository.findUnresolvedWithQuestion()).thenReturn(List.of(autoDetected(10L, "위약금 얼마야")));

		ChatStatsResponse.Candidate candidate = service.getUnresolvedStats(null).candidates().get(0);

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

		ChatStatsResponse.Candidate candidate = service.getUnresolvedStats(null).candidates().get(0);

		assertThat(candidate.count()).isEqualTo(7);
		assertThat(candidate.comments()).containsExactly("이유 7", "이유 5", "이유 4", "이유 3", "이유 2");
	}
}
