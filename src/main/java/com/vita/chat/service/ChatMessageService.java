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
	
	private static final Set<String> EXTREME_SIGNAL_KEYWORDS = Set.of(
			"가장", "제일", "최고", "최저", "가성비", "제일싼", "가장싼"
	);

	private boolean hasExtremeSignal(String query) {
		return EXTREME_SIGNAL_KEYWORDS.stream().anyMatch(query::contains);
	}
	

	public ChatMessageResponse sendMessage(Long sessionId, Long userId, UUID guestId, ChatMessageSendRequest request) {
		
		// 1) 사용자 메시지 저장 + AI 답변 자리(PENDING 상태)를 먼저 DB에 만들어둠
		//    아직 LLM 응답은 안 왔지만, "생성중"이라는 행을 미리 확보하는 것
		ChatMessage assistantMessage = persistence.saveUserAndPendingAssistant(sessionId, request);
		Long assistantId = assistantMessage.getId();
		
		
		
		long startTime = System.currentTimeMillis();
		
		try {
			// 2) 조립 재료 준비
			ContextResult context = buildContext(request.content());
			String conversationHistory = buildConversationHistory(sessionId);
			
			log.info("service context: " + context.text());
			
			// 3) 실제 LLM 호출 — 질문 + 참고자료 + 대화이력을 함께 전달
			String answer = bedrockChatClient.ask(request.content(), context.text(), conversationHistory);
			
			List<Long> faqIds = context.faqs().stream().map(FaqReference::faqId).toList();
			persistence.markCompleted(assistantId, answer, faqIds);
			
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
        
		// 커밋된 최신 상태를 트랜잭션 안에서 조회해 응답까지 만들어 반환
	    return persistence.getResponse(assistantId, latencyMs);
		
	}
	
	private record ContextResult(String text, List<FaqReference> faqs) {
	    static ContextResult empty() { return new ContextResult("", List.of()); }
	}
	
	/**
	 * 질문과 관련된 FAQ/요금제 정보를 검색해서 LLM에게 줄 하나의 문자열(context)로 조립한다.
	 * 순서: 극값 비교 결과 → 일반 요금제 → FAQ
	 */
	private ContextResult buildContext(String query) {
		// BE3의 벡터 검색 호출 — FAQ와 요금제 양쪽 결과를 함께 담고 있는 객체를 받음
		FaqRetrievalContext retrievalContext = faqRetrievalService.search(query, TOP_K);
		
		// hasRelevantFaq(): FAQ 중 가장 유사한 것도 threshold 미만이면 false
		// false인 경우 관련 없는 FAQ를 억지로 쓰지 않도록 빈 리스트 처리
		List<FaqReference> faqs = retrievalContext.hasRelevantFaq() ? retrievalContext.references() : List.of();
		List<PlanReference> plans = retrievalContext.planReferences();

		// 요금제 검색이 히트했을 때만 극값 여부 판단
		List<PlanReference> extremePlans = List.of();
		if (!plans.isEmpty() || hasExtremeSignal(query)) {
			PlanIntent intent = planIntentClassifier.classify(query);
			if (intent.extreme()) {
				extremePlans = planLookupService.findExtreme(intent.sortKey(), intent.limit());
			}
		}
		
		List<PlanReference> generalPlans = extremePlans.isEmpty()
		        ? plans
		        : List.of();   // 극값 질문이면 일반 검색 결과 제외
		
		
		if (faqs.isEmpty() && plans.isEmpty() && extremePlans.isEmpty()) {
			log.info("관련 FAQ/요금제 없음 (topSimilarity={}). query={}", retrievalContext.topSimilarity(), query);
			return ContextResult.empty();
		}

		StringBuilder sb = new StringBuilder();

		// 1) 극값 결과를 <comparison_result> 태그로 감싸서 가장 먼저 넣음 (모델이 "이게 비교 정답"이라고 알 수 있게)
		if (!extremePlans.isEmpty()) {
			sb.append("<comparison_result>\n")
			  .append(extremePlans.stream().map(this::toPlanXml).collect(Collectors.joining("\n")))
			  .append("\n</comparison_result>\n");
		}

		// 2) 일반 요금제 검색 결과 (극값 질문이면 비어 있음)
		generalPlans.stream()
		        .map(this::toPlanXml)
		        .forEach(xml -> sb.append(xml).append("\n"));

		// 3) FAQ 결과를 마지막에 추가
		faqs.stream()
				.map(this::toFaqXml)
				.forEach(xml -> sb.append(xml).append("\n"));

		log.info("context plan count: extreme={}, general={}, extremeNames={}, generalNames={}",
		        extremePlans.size(), generalPlans.size(),
		        extremePlans.stream().map(PlanReference::name).toList(),
		        generalPlans.stream().map(PlanReference::name).toList());
		
		return new ContextResult(sb.toString(), faqs);
	}
	
	/** FAQ 한 건을 LLM이 읽기 좋은 XML 비슷한 텍스트 블록으로 변환 */
	private String toFaqXml(FaqReference faq) {
		return """
				<document>
				<category>%s / %s</category>
				<question>%s</question>
				<answer>%s</answer>
				</document>
				""".formatted(faq.category(), faq.subcategory(), faq.question(), faq.answer());
	}

	/** 요금제 한 건을 LLM이 읽기 좋은 텍스트 블록으로 변환 (%,d는 숫자에 천 단위 콤마 표시) */
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
	
	/**
	 * 같은 세션의 이전 대화 내용을 "USER: ~~\nASSISTANT: ~~" 형태의 한 문자열로 이어붙인다.
	 * LLM에게 이전 맥락을 알려주기 위한 용도.
	 */
	private String buildConversationHistory (Long sessionId) {
		
		List<ChatMessage> previousMessages =
				chatMessageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
		
		return previousMessages.stream()
				.filter(m -> m.getStatus() == ChatMessageStatus.COMPLETED)
				.map(m -> "%s: %s".formatted(m.getRole(), m.getContent()))
				.collect(Collectors.joining("\n"));
		
	}
	
	/**
	 * 특정 세션의 메시지 목록을 조회하는 API용 메소드.
	 * @Transactional(readOnly = true): 조회만 하는 트랜잭션이라고 명시 (DB 최적화 + 실수로 쓰기 방지)
	 */
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
