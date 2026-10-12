package com.vita.chat.entity;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import com.vita.chat.UnresolvedReason;
import com.vita.chat.dto.ChatAction;
import com.vita.chat.dto.ChatStoreMap;
import com.vita.chat.ChatMessageFeedback;
import com.vita.chat.ChatMessageRole;
import com.vita.chat.ChatMessageStatus;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "chat_messages")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatMessage {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
	
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "session_id")
	private ChatSession session;
	
	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.NAMED_ENUM)
	@Column(nullable = false, columnDefinition = "chat_message_role")
	private ChatMessageRole role;
	
	@Column(columnDefinition = "TEXT")
	private String content;
	
	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.NAMED_ENUM)
	@Column(columnDefinition = "chat_message_status")
	private ChatMessageStatus status;
	
	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.NAMED_ENUM)
	@Column(columnDefinition = "feedback_type")
	private ChatMessageFeedback feedback;

	// 답변과 함께 보낸 매장 지도
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "store_map", columnDefinition = "jsonb")
	private ChatStoreMap storeMap;

	// 답변 아래 추천 버튼
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(columnDefinition = "jsonb")
	private List<ChatAction> actions;

	// 미해결 이유
	@Enumerated(EnumType.STRING)
	@Column(name = "unresolved_reason", length = 30)
	private UnresolvedReason unresolvedReason;

	// 검색 최고 유사도
	@Column(name = "top_similarity")
	private Double topSimilarity;

	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;
	
	@OneToMany(mappedBy = "chatMessage", cascade = CascadeType.ALL, orphanRemoval = true)
	private List<ChatMessageFaqRef> faqRefs = new ArrayList<>();
	
	@Column(name = "error_message", columnDefinition = "TEXT")
	private String errorMessage;
	
	@Builder
	public ChatMessage(ChatSession session, ChatMessageRole role, String content, ChatMessageStatus status) {
		this.session = session;
		this.role = role;
		this.content = content;
		this.status = status;
	}
	
	/** 
	 * 생성 시
	 */
	@PrePersist
	void onCreate() {
		this.createdAt = LocalDateTime.now(ZoneId.of("Asia/Seoul"));
	}

	public void markCompleted(String content) {
		markCompleted(content, null, null);
	}

	public void markCompleted(String content, ChatStoreMap storeMap, List<ChatAction> actions) {
		markCompleted(content, storeMap, actions, null, null);
	}

	/** 매장 지도·추천 버튼·미해결 여부와 함께 완료 처리. 빈 버튼 목록은 null로 저장 */
	public void markCompleted(String content, ChatStoreMap storeMap, List<ChatAction> actions,
	                          UnresolvedReason unresolvedReason, Double topSimilarity) {
		this.content = content;
		this.status = ChatMessageStatus.COMPLETED;
		this.storeMap = storeMap;
		this.actions = (actions == null || actions.isEmpty()) ? null : actions;
		this.unresolvedReason = unresolvedReason;
		this.topSimilarity = topSimilarity;
	}
	
	/**
	 * 실패 시
	 */
	public void markFailed(String errorMessage) {
		this.status = ChatMessageStatus.FAILED;
		this.errorMessage = errorMessage;
		this.unresolvedReason = UnresolvedReason.ANSWER_FAILED;
	}
	
	/**
	 * 
	 * @param feedback
	 */
	public void applyFeedback(ChatMessageFeedback feedback) {
        this.feedback = feedback;
    }
	
}
