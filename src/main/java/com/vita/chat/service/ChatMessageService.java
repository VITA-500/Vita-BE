package com.vita.chat.service;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vita.chat.ChatMessageRole;
import com.vita.chat.ChatMessageStatus;
import com.vita.chat.client.BedrockChatClient;
import com.vita.chat.dto.ChatMessageResponse;
import com.vita.chat.dto.ChatMessageSendRequest;
import com.vita.chat.dto.ChatSessionListResponse;
import com.vita.chat.dto.ChatSessionSummaryResponse;
import com.vita.chat.dto.MessageResponse;
import com.vita.chat.dto.PlanIntent;
import com.vita.chat.dto.SessionMessagesResponse;
import com.vita.chat.entity.ChatMessage;
import com.vita.chat.entity.ChatSession;
import com.vita.chat.repository.ChatMessageRepository;
import com.vita.chat.repository.ChatSessionRepository;
import com.vita.common.exception.BusinessException;
import com.vita.common.exception.ErrorCode;
import com.vita.search.dto.FaqReference;
import com.vita.search.dto.FaqRetrievalContext;
import com.vita.search.dto.PlanReference;
import com.vita.search.service.FaqRetrievalService;
import com.vita.search.service.PlanLookupService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.core.exception.SdkException;

@Service
@RequiredArgsConstructor
@Slf4j
public class ChatMessageService {

	private static final int TOP_K = 3; // 검색해올 FAQ 후보 개수 — threshold 필터는 BE3 쪽에서 처리됨
	
	private final BedrockChatClient bedrockChatClient;
	private final FaqRetrievalService faqRetrievalService;
	private final ChatMessagePersistence persistence;
	private final ChatMessageRepository chatMessageRepository;
	private final ChatSessionRepository chatSessionRepository;
	private final PlanLookupService planLookupService;
	private final PlanIntentClassifier planIntentClassifier;
	
	@Transactional
	public ChatMessageResponse sendMessage(Long sessionId, Long userId, UUID guestId, ChatMessageSendRequest request) {
		
		ChatMessage assistantMessage = persistence.saveUserAndPendingAssistant(sessionId, request);
		
		// 2) 조립 재료 준비
		String context = buildContext(request.content()); // 파라미터 수정 필요
		String conversationHistory = buildConversationHistory(sessionId);
		
		log.info("service context: " + context);
		
		long startTime = System.currentTimeMillis();
		
		try {
			String answer = bedrockChatClient.ask(request.content(), context, conversationHistory);
			persistence.markCompleted(assistantMessage.getId(), answer);
			
		} catch (ApiCallTimeoutException | ApiCallAttemptTimeoutException e) {
		    log.error("Bedrock 응답 타임아웃 - sessionId: {}", sessionId, e);
		    persistence.markFailed(assistantMessage.getId(), e.getMessage());
		} catch (SdkException e) {
		    log.error("Bedrock 호출 실패 - sessionId: {}", sessionId, e);
		    persistence.markFailed(assistantMessage.getId(), e.getMessage());
		} catch(Exception e) {
			log.error("AI 응답 생성 실패 - sessionId: {}", sessionId, e);  // 마지막 인자로 e를 넘기면 SLF4J가 스택 트레이스 전체를 출력해줌
			persistence.markFailed(assistantMessage.getId(), e.getMessage());
		}
		long latencyMs = System.currentTimeMillis() - startTime;
        
		return ChatMessageResponse.of(assistantMessage, latencyMs);
		
	}
	
	private String buildContext(String query) {
		FaqRetrievalContext retrievalContext = faqRetrievalService.search(query, TOP_K);
		
		List<FaqReference> faqs = retrievalContext.hasRelevantFaq() ? retrievalContext.references() : List.of();
		List<PlanReference> plans = retrievalContext.planReferences();

		// 요금제 검색이 히트했을 때만 극값 여부 판단
		List<PlanReference> extremePlans = List.of();
		log.info("plans={}, faqs={}, topSimilarity={}", plans.size(), faqs.size(), retrievalContext.topSimilarity());

		if (!plans.isEmpty()) {
			PlanIntent intent = planIntentClassifier.classify(query);
			log.info("intent={}", intent);
			if (intent.extreme()) {
				extremePlans = planLookupService.findExtreme(intent.sortKey(), intent.limit());
			}
		}
		
		
		if (faqs.isEmpty() && plans.isEmpty() && extremePlans.isEmpty()) {
			log.info("관련 FAQ/요금제 없음 (topSimilarity={}). query={}", retrievalContext.topSimilarity(), query);
			return "";
		}

		Set<Long> extremeIds = extremePlans.stream()
				.map(PlanReference::planId)
				.collect(Collectors.toSet());

		StringBuilder sb = new StringBuilder();

		if (!extremePlans.isEmpty()) {
			sb.append("<comparison_result>\n")
			  .append(extremePlans.stream().map(this::toPlanXml).collect(Collectors.joining("\n")))
			  .append("\n</comparison_result>\n");
		}

		plans.stream()
				.filter(p -> !extremeIds.contains(p.planId()))   // 중복 제거
				.map(this::toPlanXml)
				.forEach(xml -> sb.append(xml).append("\n"));

		faqs.stream()
				.map(this::toFaqXml)
				.forEach(xml -> sb.append(xml).append("\n"));

		return sb.toString();
	}
	
	private String toFaqXml(FaqReference faq) {
		return """
				<document>
				<category>%s / %s</category>
				<question>%s</question>
				<answer>%s</answer>
				</document>
				""".formatted(faq.category(), faq.subcategory(), faq.question(), faq.answer());
	}

	private String toPlanXml(PlanReference p) {
		return """
				<plan>
				<name>%s</name>
				<monthly_fee>월 %,d원</monthly_fee>
				<summary>%s</summary>
				<description>%s</description>
				</plan>
				""".formatted(p.name(), p.monthlyFee(), p.summary(), p.description());
	}
	
	private String buildConversationHistory (Long sessionId) {
		
		List<ChatMessage> previousMessages =
				chatMessageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
		
		return previousMessages.stream()
				.filter(m -> m.getStatus() == ChatMessageStatus.COMPLETED)
				.map(m -> "%s: %s".formatted(m.getRole(), m.getContent()))
				.collect(Collectors.joining("\n"));
		
	}
	
	@Transactional(readOnly = true)
	public SessionMessagesResponse getMessages(Long sessionId, Long userId, UUID guestId) {
		
		ChatSession session = chatSessionRepository.findById(sessionId)
				.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "존재하지 않는 세션입니다."));
		
		boolean isOwner;
		if (session.getUserId() != null) {
		    isOwner = session.getUserId().equals(userId);
		} else {
		    isOwner = session.getGuestId().equals(guestId);
		}
		
		if(!isOwner){
			throw new BusinessException(ErrorCode.FORBIDDEN, "타인의 세션에는 접근할 수 없습니다.");
		}
		List<MessageResponse> messages = chatMessageRepository
				.findAllBySession_IdOrderByCreatedAtAsc(sessionId)
				.stream()
				.map(MessageResponse::from)
				.toList();
		
		return new SessionMessagesResponse(sessionId, messages);
	}
}
