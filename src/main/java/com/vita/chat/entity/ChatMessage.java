package com.vita.chat.entity;

import java.time.LocalDateTime;
import java.time.ZoneId;

import com.vita.chat.ChatMessageFeedback;
import com.vita.chat.ChatMessageRole;
import com.vita.chat.ChatMessageStatus;

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
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

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
	@Column(nullable = false)
	private ChatMessageRole role;
	
	@Column(columnDefinition = "TEXT")
	private String content;
	
	@Enumerated(EnumType.STRING)
	private ChatMessageStatus status;
	
	@Enumerated(EnumType.STRING)
	private ChatMessageFeedback feedback;
	
	@Column(name = "latency_ms")
	private Integer  latencyMs;
	
	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;
	
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

	/**
	 * 오류 없이 메시지 응답 받을 때
	 * @param content
	 * @param latencyMs
	 */
	public void markCompleted(String content, int latencyMs) {
		this.content = content;
		this.status = ChatMessageStatus.COMPLETED;
		this.latencyMs = latencyMs;
	}
	
	/**
	 * 실패 시
	 */
	public void markFailed() {
		this.status = ChatMessageStatus.FAILED;
	}
	
	/**
	 * 
	 * @param feedback
	 */
	public void applyFeedback(ChatMessageFeedback feedback) {
        this.feedback = feedback;
    }
	
}
