package com.vita.chat.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vita.chat.ReportStatus;
import com.vita.chat.UnresolvedReason;
import com.vita.chat.dto.ChatStatsResponse;
import com.vita.chat.repository.ChatMessageRepository;
import com.vita.chat.repository.UnresolvedQuestionReportRepository;
import com.vita.common.exception.BusinessException;
import com.vita.common.exception.ErrorCode;

import lombok.RequiredArgsConstructor;

/**
 * 관리자 대시보드 미해결 질문 통계.
 * 자동 감지와 사용자 신고, 피드백을 합쳐 같은 질문끼리 묶는다.
 * 띄어쓰기, 문장부호 대소문자만 다른 질문은 같은 질문으로 본다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChatStatsService {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;
    private static final String SOURCE_CHAT = "CHAT";

    private final ChatMessageRepository chatMessageRepository;
    private final UnresolvedQuestionReportRepository reportRepository;

    private record Occurrence(Long messageId, String question, String reason, LocalDateTime occurredAt) {
    }

    public ChatStatsResponse getUnresolvedStats(Integer limit) {
        int size = limit == null ? DEFAULT_LIMIT : limit;
        if (size < 1 || size > MAX_LIMIT) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "limit은 1~" + MAX_LIMIT + " 사이여야 합니다.");
        }

        List<Occurrence> occurrences = new ArrayList<>();
        chatMessageRepository.findUnresolvedWithQuestion().forEach(row ->
                occurrences.add(new Occurrence(row.getId(), row.getQuestion(), row.getReason(), row.getCreatedAt())));
        reportRepository.findAllByStatus(ReportStatus.OPEN).forEach(report ->
                occurrences.add(new Occurrence(report.getMessage().getId(), report.getQuestionText(),
                        UnresolvedReason.USER_REPORT.name(), report.getCreatedAt())));
        chatMessageRepository.findDislikedWithQuestion().forEach(row ->
                occurrences.add(new Occurrence(row.getId(), row.getQuestion(), row.getReason(), row.getCreatedAt())));

        // 같은 질문끼리 묶기
        Map<String, List<Occurrence>> groups = new LinkedHashMap<>();
        occurrences.stream()
                .filter(o -> o.question() != null && !o.question().isBlank())
                .forEach(o -> groups.computeIfAbsent(normalize(o.question()), k -> new ArrayList<>()).add(o));

        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        List<ChatStatsResponse.Candidate> candidates = groups.values().stream()
                .map(list -> {
                    Occurrence latest = list.stream().max(Comparator.comparing(Occurrence::occurredAt)).orElseThrow();
                    return new ChatStatsResponse.Candidate(latest.messageId(), latest.question().trim(), SOURCE_CHAT,
                            latest.reason(), list.size(), latest.occurredAt());
                })
                .sorted(Comparator.comparingLong(ChatStatsResponse.Candidate::count).reversed()
                        .thenComparing(ChatStatsResponse.Candidate::lastOccurredAt, Comparator.reverseOrder()))
                .toList();

        long repeatCount = candidates.stream().mapToLong(ChatStatsResponse.Candidate::count).sum();
        long todayCount = candidates.stream().filter(c -> c.lastOccurredAt().toLocalDate().equals(today)).count();

        return new ChatStatsResponse(
                new ChatStatsResponse.Summary(candidates.size(), repeatCount, todayCount),
                candidates.stream().limit(size).toList());
    }

    /** 띄어쓰기, 문장부호, 대소문자 차이를 없앤 비교용 문자열 */
    static String normalize(String question) {
        return question.toLowerCase().replaceAll("[\\s\\p{Punct}·…~？！。、]", "");
    }
}