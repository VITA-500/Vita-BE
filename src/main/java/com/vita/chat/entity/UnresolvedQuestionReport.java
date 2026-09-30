package com.vita.chat.entity;

import java.time.LocalDateTime;
import java.time.ZoneId;

import com.vita.chat.ReportStatus;

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
@Table(name = "unresolved_question_reports")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UnresolvedQuestionReport {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "message_id", nullable = false)
	private ChatMessage message;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Column(name = "question_text", columnDefinition = "TEXT")
	private String questionText;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private ReportStatus status;

	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	@Builder
	public UnresolvedQuestionReport(ChatMessage message, Long userId, String questionText) {
		this.message = message;
		this.userId = userId;
		this.questionText = questionText;
		this.status = ReportStatus.OPEN;
	}

	@PrePersist
	void onCreate() {
		this.createdAt = LocalDateTime.now(ZoneId.of("Asia/Seoul"));
	}
}