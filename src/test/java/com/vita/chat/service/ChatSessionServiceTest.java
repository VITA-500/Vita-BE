package com.vita.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.vita.chat.dto.ChatSessionTitleResponse;
import com.vita.chat.entity.ChatSession;
import com.vita.chat.repository.ChatSessionRepository;
import com.vita.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

/** 대화 이름 변경: 공백 정리, 1~30자(이모지 한 글자), 본인 세션 확인, 자동 제목으로 덮어쓰지 않음 */
class ChatSessionServiceTest {

	private final ChatSessionRepository repository = mock(ChatSessionRepository.class);
	private final ChatMessageService chatMessageService = mock(ChatMessageService.class);
	private final ChatSessionService service = new ChatSessionService(repository, chatMessageService);

	private ChatSession ownedSession() {
		ChatSession session = ChatSession.builder().userId(1L).build();
		when(chatMessageService.getOwnedSession(any(), any(), any())).thenReturn(session);
		return session;
	}

	@Test
	void trimsAndCollapsesWhitespace() {
		ownedSession();

		ChatSessionTitleResponse response = service.renameSession(10L, 1L, "  요금제\n  상담   기록 ");

		assertThat(response.title()).isEqualTo("요금제 상담 기록");
	}

	@Test
	void acceptsThirtyCharactersAndRejectsThirtyOne() {
		ownedSession();

		assertThat(service.renameSession(10L, 1L, "가".repeat(30)).title()).hasSize(30);
		assertThatThrownBy(() -> service.renameSession(10L, 1L, "가".repeat(31)))
				.isInstanceOf(BusinessException.class);
	}

	@Test
	void countsAnEmojiAsOneCharacter() {
		ownedSession();

		// 이모지는 자바 문자열 길이로 2지만 한 글자로 센다
		assertThat(service.renameSession(10L, 1L, "🙂".repeat(30)).title()).isEqualTo("🙂".repeat(30));
	}

	@Test
	void rejectsBlankTitleBeforeLookingUpTheSession() {
		assertThatThrownBy(() -> service.renameSession(10L, 1L, "   \n "))
				.isInstanceOf(BusinessException.class);
		verifyNoInteractions(chatMessageService);
	}

	@Test
	void checksOwnershipAsAMember() {
		ownedSession();

		service.renameSession(10L, 1L, "새 이름");

		verify(chatMessageService).getOwnedSession(10L, 1L, null);
	}

	@Test
	void autoTitleDoesNotOverwriteARenamedSession() {
		ChatSession session = ownedSession();

		service.renameSession(10L, 1L, "내가 지은 이름");
		session.initTitle("첫 질문으로 만들어지는 자동 제목");

		assertThat(session.getTitle()).isEqualTo("내가 지은 이름");
	}
}
