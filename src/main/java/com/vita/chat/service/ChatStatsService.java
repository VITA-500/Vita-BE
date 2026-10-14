package com.vita.chat.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vita.chat.ReportStatus;
import com.vita.chat.UnresolvedReason;
import com.vita.chat.dto.ChatDismissResponse;
import com.vita.chat.dto.ChatStatsResponse;
import com.vita.chat.entity.UnresolvedQuestionReport;
import com.vita.chat.repository.ChatMessageRepository;
import com.vita.chat.repository.UnresolvedQuestionReportRepository;
import com.vita.common.exception.BusinessException;
import com.vita.common.exception.ErrorCode;

import lombok.RequiredArgsConstructor;

/**
 * 관리자 대시보드 미해결 질문 통계.
 * 자동 감지와 (싫어요)를 합쳐 같은 질문끼리 묶는다.
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

    private record Occurrence(Long messageId, String question, String answer, String reason, LocalDateTime occurredAt) {
    }

    public ChatStatsResponse getUnresolvedStats(Integer limit) {
        int size = limit == null ? DEFAULT_LIMIT : limit;
        if (size < 1 || size > MAX_LIMIT) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "limit은 1~" + MAX_LIMIT + " 사이여야 합니다.");
        }

        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        List<ChatStatsResponse.Candidate> candidates = groupByQuestion().stream()
                .map(list -> {
                    Occurrence latest = list.stream().max(Comparator.comparing(Occurrence::occurredAt)).orElseThrow();
                    // 자동 감지된 답변에 (싫어요)까지 받아도 같은 답변이면 1회로 센다
                    long count = list.stream().map(Occurrence::messageId).distinct().count();
                    return new ChatStatsResponse.Candidate(latest.messageId(), latest.question().trim(), latest.answer(),
                            SOURCE_CHAT, latest.reason(), count, latest.occurredAt());
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

    /**
     * 관리자 "무시": 목록의 한 항목(같은 질문 묶음)에 들어 있는 (싫어요)를 모두 DISMISSED로 바꾼다.
     * 무시한 (싫어요)는 같은 사용자가 다시 눌러도 목록에 다시 올라오지 않는다.
     * 자동 감지 기록은 처리 상태가 없어서 그대로 남는다.
     */
    @Transactional
    public ChatDismissResponse dismiss(Long id) {
        List<Long> messageIds = groupByQuestion().stream()
                .filter(list -> list.stream().anyMatch(o -> o.messageId().equals(id)))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "미해결 질문을 찾을 수 없습니다."))
                .stream().map(Occurrence::messageId).distinct().toList();

        List<UnresolvedQuestionReport> reports = reportRepository.findAllByMessageIdInAndStatus(messageIds, ReportStatus.OPEN);
        if (reports.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "👎 받은 답변이 없는 항목입니다. 자동 감지 항목은 아직 무시할 수 없습니다.");
        }
        reports.forEach(UnresolvedQuestionReport::dismiss);
        return new ChatDismissResponse(id, reports.size());
    }

    // 자동 감지 + (싫어요(OPEN))를 모아 같은 질문끼리 묶는다
    private Collection<List<Occurrence>> groupByQuestion() {
        List<Occurrence> occurrences = new ArrayList<>();
        chatMessageRepository.findUnresolvedWithQuestion().forEach(row ->
                occurrences.add(new Occurrence(row.getId(), row.getQuestion(), row.getAnswer(),
                        row.getReason(), row.getCreatedAt())));
        reportRepository.findAllByStatus(ReportStatus.OPEN).forEach(report ->
                occurrences.add(new Occurrence(report.getMessage().getId(), report.getQuestionText(),
                        report.getMessage().getContent(), UnresolvedReason.DISLIKED.name(), report.getCreatedAt())));

        Map<String, List<Occurrence>> groups = new LinkedHashMap<>();
        occurrences.stream()
                .filter(o -> o.question() != null && !o.question().isBlank())
                .forEach(o -> groups.computeIfAbsent(normalize(o.question()), k -> new ArrayList<>()).add(o));
        return groups.values();
    }

    /** 띄어쓰기, 문장부호, 대소문자 차이를 없앤 비교용 문자열 */
    static String normalize(String question) {
        return question.toLowerCase().replaceAll("[\\s\\p{Punct}·…~？！。、]", "");
    }
}