package com.vita.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.vita.chat.ChatMessageFeedback;
import com.vita.chat.ChatMessageRole;
import com.vita.chat.ChatMessageStatus;
import com.vita.chat.dto.ChatFeedbackResponse;
import com.vita.chat.dto.ReportResponse;
import com.vita.chat.entity.ChatMessage;
import com.vita.chat.entity.ChatSession;
import com.vita.chat.repository.ChatMessageRepository;
import com.vita.chat.repository.ChatSessionRepository;
import com.vita.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** (아쉬워요) 이유: (아쉬워요)에만, 앞뒤 공백 제외 200자, null이면 유지, 빈 문자열이면 지움 */
class ChatFeedbackCommentTest {

	private final ChatMessageRepository messageRepository = mock(ChatMessageRepository.class);
	private final ChatSessionRepository sessionRepository = mock(ChatSessionRepository.class);
	private final ChatReportService reportService = mock(ChatReportService.class);
	private final ChatMessageService service = new ChatMessageService(
			mock(ChatAnswerStreamer.class), mock(ChatMessagePersistence.class),
			messageRepository, sessionRepository, reportService);

	@BeforeEach
	void ownedCompletedAnswer() {
		ChatSession session = ChatSession.builder().userId(1L).build();
		ReflectionTestUtils.setField(session, "id", 5L);
		ChatMessage answer = ChatMessage.builder().session(session).role(ChatMessageRole.ASSISTANT)
				.content("답변").status(ChatMessageStatus.COMPLETED).build();
		ReflectionTestUtils.setField(answer, "id", 10L);
		when(messageRepository.findById(10L)).thenReturn(Optional.of(answer));
		when(sessionRepository.findById(5L)).thenReturn(Optional.of(session));
		when(reportService.report(10L, 1L)).thenReturn(new ReportResponse(10L, null, "예전 이유"));
		when(reportService.changeComment(anyLong(), anyLong(), any()))
				.thenAnswer(inv -> new ReportResponse(10L, null, inv.getArgument(2)));
	}

	@Test
	void savesTrimmedComment() {
		ChatFeedbackResponse response = service.applyFeedback(10L, 1L, ChatMessageFeedback.DISLIKE, "  요금이 틀려요  ");

		verify(reportService).changeComment(10L, 1L, "요금이 틀려요");
		assertThat(response.comment()).isEqualTo("요금이 틀려요");
	}

	@Test
	void keepsExistingCommentWhenNotSent() {
		ChatFeedbackResponse response = service.applyFeedback(10L, 1L, ChatMessageFeedback.DISLIKE, null);

		verify(reportService, never()).changeComment(anyLong(), anyLong(), any());
		assertThat(response.comment()).isEqualTo("예전 이유");
	}

	@Test
	void clearsCommentWhenBlank() {
		ChatFeedbackResponse response = service.applyFeedback(10L, 1L, ChatMessageFeedback.DISLIKE, "   ");

		verify(reportService).changeComment(eq(10L), eq(1L), isNull());
		assertThat(response.comment()).isNull();
	}

	@Test
	void acceptsTwoHundredCharactersAndRejectsMore() {
		// 이모지도 한 글자로 센다
		assertThat(service.applyFeedback(10L, 1L, ChatMessageFeedback.DISLIKE, "🙂".repeat(200)).comment())
				.isEqualTo("🙂".repeat(200));
		assertThatThrownBy(() -> service.applyFeedback(10L, 1L, ChatMessageFeedback.DISLIKE, "가".repeat(201)))
				.isInstanceOf(BusinessException.class);
	}

	@Test
	void rejectsCommentWithoutDislike() {
		assertThatThrownBy(() -> service.applyFeedback(10L, 1L, ChatMessageFeedback.LIKE, "좋아요 이유"))
				.isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> service.applyFeedback(10L, 1L, null, "취소 이유"))
				.isInstanceOf(BusinessException.class);
		verifyNoInteractions(reportService);
	}

	@Test
	void likeHasNoComment() {
		ChatFeedbackResponse response = service.applyFeedback(10L, 1L, ChatMessageFeedback.LIKE, null);

		verify(reportService).cancel(10L, 1L);
		assertThat(response.comment()).isNull();
	}
}
