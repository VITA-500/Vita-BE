package com.vita.chat.service;

import com.vita.chat.ReportStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vita.chat.ChatMessageRole;
import com.vita.chat.dto.ReportResponse;
import com.vita.chat.entity.ChatMessage;
import com.vita.chat.entity.UnresolvedQuestionReport;
import com.vita.chat.repository.ChatMessageRepository;
import com.vita.chat.repository.UnresolvedQuestionReportRepository;
import com.vita.common.exception.BusinessException;
import com.vita.common.exception.ErrorCode;

import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;
import java.time.ZoneId;

@Service
@RequiredArgsConstructor
public class ChatReportService {

	private final ChatMessageRepository chatMessageRepository;
	private final UnresolvedQuestionReportRepository reportRepository;
	
	@Transactional
	public ReportResponse report(Long messageId, Long userId) {
		
		ChatMessage message = chatMessageRepository.findById(messageId)
				.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "존재하지 않는 세션입니다."));
		
		if (!userId.equals(message.getSession().getUserId())) { // 회원 세션이고 내 세션일 때만 허용 (게스트 세션은 user_id가 null이라 걸러짐)
			throw new BusinessException(ErrorCode.FORBIDDEN);
		}
		
		return reportRepository.findByMessageIdAndUserId(messageId, userId)
				.map(ReportResponse::from)
				.orElseGet(() -> createReport(message, userId));
	}

	/** (아쉬워요) 이유 저장. report 다음에 부른다. null이면 지움 */
	@Transactional
	public ReportResponse changeComment(Long messageId, Long userId, String comment) {
		UnresolvedQuestionReport report = reportRepository.findByMessageIdAndUserId(messageId, userId)
				.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
		report.changeComment(comment);
		return ReportResponse.from(report);
	}

	/** (아쉬워요) 취소. 관리자가 이미 처리한 신고는 기록으로 남긴다 */
	@Transactional
	public void cancel(Long messageId, Long userId) {
		reportRepository.deleteByMessageIdAndUserIdAndStatus(messageId, userId, ReportStatus.OPEN);
	}
	
	private ReportResponse createReport(ChatMessage message, Long userId) {
		
		String question = chatMessageRepository
				.findTop5BySessionIdAndIdLessThanOrderByIdDesc(message.getSession().getId(), message.getId())
				.stream()
				.filter(m -> m.getRole() == ChatMessageRole.USER)
				.findFirst()
				.map(ChatMessage::getContent)
				.orElse(null);
		
		// 중복이면 DB가 조용히 건너뛴다. 예외를 잡는 방식은 바깥 트랜잭션(피드백 저장)까지 롤백시켜 500이 났다
		reportRepository.insertIfAbsent(message.getId(), userId, question, LocalDateTime.now(ZoneId.of("Asia/Seoul")));
		return reportRepository.findByMessageIdAndUserId(message.getId(), userId)
				.map(ReportResponse::from)
				.orElseThrow();
	}
}
