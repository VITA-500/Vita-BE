package com.vita.chat.service;

import org.springframework.dao.DataIntegrityViolationException;
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
	
	private ReportResponse createReport(ChatMessage message, Long userId) {
		
		String question = chatMessageRepository
				.findTop5BySessionIdAndIdLessThanOrderByIdDesc(message.getSession().getId(), message.getId())
				.stream()
				.filter(m -> m.getRole() == ChatMessageRole.USER)
				.findFirst()
				.map(ChatMessage::getContent)
				.orElse(null);
		
		try {
			return ReportResponse.from(reportRepository.saveAndFlush(
					UnresolvedQuestionReport.builder()
					.message(message)
					.userId(userId)
					.questionText(question)
					.build()
					));
		}
		catch (DataIntegrityViolationException e) {
			// 동시 요청으로 unique 제약에 걸린 경우 → 기존 신고 반환
			return reportRepository.findByMessageIdAndUserId(message.getId(), userId)
				.map(ReportResponse::from)
				.orElseThrow(() -> e);
		}
	}
}
