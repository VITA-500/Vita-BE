package com.vita.chat.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vita.chat.ChatMessageFeedback;
import com.vita.chat.ReportStatus;
import com.vita.chat.UnresolvedReason;
import com.vita.chat.dto.ChatDismissResponse;
import com.vita.chat.dto.ChatStatsResponse;
import com.vita.chat.entity.ChatMessage;
import com.vita.chat.entity.UnresolvedQuestionReport;
import com.vita.chat.repository.ChatMessageRepository;
import com.vita.chat.repository.UnresolvedQuestionReportRepository;
import com.vita.common.exception.BusinessException;
import com.vita.common.exception.ErrorCode;

import lombok.RequiredArgsConstructor;

/**
 * 관리자 대시보드 미해결 질문 통계.
 * 자동 감지와 (아쉬워요)를 합쳐 같은 질문끼리 묶는다.
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
        Map<String, FeedbackCount> feedbackCounts = countFeedbackByQuestion();
        List<ChatStatsResponse.Candidate> candidates = groupByQuestion().stream()
                .map(list -> {
                    Occurrence latest = list.stream().max(Comparator.comparing(Occurrence::occurredAt)).orElseThrow();
                    // 자동 감지된 답변에 (아쉬워요)까지 받아도 같은 답변이면 1회로 센다
                    long count = list.stream().map(Occurrence::messageId).distinct().count();
                    FeedbackCount feedback = feedbackCounts.getOrDefault(normalize(latest.question()), FeedbackCount.NONE);
                    return new ChatStatsResponse.Candidate(latest.messageId(), latest.question().trim(), latest.answer(),
                            SOURCE_CHAT, latest.reason(), count, latest.occurredAt(),
                            feedback.likeCount(), feedback.dislikeCount());
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
     * 관리자 "무시": 목록의 한 항목을 목록에서 내린다.
     * (아쉬워요)는 DISMISSED로 바꾸고 자동 감지된 답변은 무시한 시각을 남긴다.
     * 무시한 (아쉬워요)는 같은 사용자가 다시 눌러도 목록에 다시 올라오지 않는다.
     * 같은 질문이 새로 미해결로 나오면 새 항목으로 다시 올라온다.
     */
    @Transactional
    public ChatDismissResponse dismiss(Long id) {
        List<Long> messageIds = groupByQuestion().stream()
                .filter(list -> list.stream().anyMatch(o -> o.messageId().equals(id)))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "미해결 질문을 찾을 수 없습니다."))
                .stream().map(Occurrence::messageId).distinct().toList();

        reportRepository.findAllByMessageIdInAndStatus(messageIds, ReportStatus.OPEN)
                .forEach(UnresolvedQuestionReport::dismiss);
        chatMessageRepository.findAllById(messageIds).forEach(ChatMessage::dismissUnresolved);
        return new ChatDismissResponse(id, messageIds.size());
    }

    private record FeedbackCount(long likeCount, long dislikeCount) {
        static final FeedbackCount NONE = new FeedbackCount(0, 0);
    }

    // 같은 질문(띄어쓰기, 문장부호, 대소문자 무시)의 답변이 받은 (좋아요)/(아쉬워요) 수
    private Map<String, FeedbackCount> countFeedbackByQuestion() {
        Map<String, FeedbackCount> counts = new HashMap<>();
        chatMessageRepository.findFeedbackWithQuestion().stream()
                .filter(row -> row.getQuestion() != null && !row.getQuestion().isBlank())
                .forEach(row -> {
                    boolean like = ChatMessageFeedback.LIKE.name().equals(row.getFeedback());
                    counts.merge(normalize(row.getQuestion()), new FeedbackCount(like ? 1 : 0, like ? 0 : 1),
                            (a, b) -> new FeedbackCount(a.likeCount() + b.likeCount(), a.dislikeCount() + b.dislikeCount()));
                });
        return counts;
    }

    // 자동 감지 + (아쉬워요(OPEN))를 모아 같은 질문끼리 묶는다
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