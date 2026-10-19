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
import java.util.Set;
import java.util.stream.Collectors;

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
    private static final int COMMENT_LIMIT = 5;

    private final ChatMessageRepository chatMessageRepository;
    private final UnresolvedQuestionReportRepository reportRepository;

    private record Occurrence(Long messageId, String question, String answer, String reason, LocalDateTime occurredAt,
                              String comment) {
    }

    /** 목록 한 항목과, 그 묶음에 들어 있는 사유들 (reason 필터용) */
    private record Item(ChatStatsResponse.Candidate candidate, Set<String> reasons) {
    }

    /**
     * @param limit  목록 개수 (1~200, 기본 50)
     * @param reason 이 사유가 묶음 안에 하나라도 있는 항목만 (null이면 전체). summary는 필터와 관계없이 전체 기준
     */
    public ChatStatsResponse getUnresolvedStats(Integer limit, String reason) {
        int size = limit == null ? DEFAULT_LIMIT : limit;
        if (size < 1 || size > MAX_LIMIT) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "limit은 1~" + MAX_LIMIT + " 사이여야 합니다.");
        }
        String reasonFilter = parseReason(reason);

        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        Collection<List<Occurrence>> groups = groupByQuestion();
        Map<String, FeedbackCount> feedbackCounts = countFeedbackByQuestion();
        List<Item> items = groups.stream()
                .map(list -> {
                    Occurrence latest = list.stream().max(Comparator.comparing(Occurrence::occurredAt)).orElseThrow();
                    // 자동 감지된 답변에 (아쉬워요)까지 받아도 같은 답변이면 1회로 센다
                    long count = list.stream().map(Occurrence::messageId).distinct().count();
                    FeedbackCount feedback = feedbackCounts.getOrDefault(normalize(latest.question()), FeedbackCount.NONE);
                    ChatStatsResponse.Candidate candidate = new ChatStatsResponse.Candidate(latest.messageId(),
                            latest.question().trim(), latest.answer(), SOURCE_CHAT, latest.reason(), count,
                            latest.occurredAt(), feedback.likeCount(), feedback.dislikeCount(), recentComments(list));
                    return new Item(candidate, list.stream().map(Occurrence::reason).collect(Collectors.toSet()));
                })
                .sorted(Comparator.comparingLong((Item i) -> i.candidate().count()).reversed()
                        .thenComparing(i -> i.candidate().lastOccurredAt(), Comparator.reverseOrder()))
                .toList();

        long repeatCount = items.stream().mapToLong(i -> i.candidate().count()).sum();
        long todayCount = items.stream().filter(i -> i.candidate().lastOccurredAt().toLocalDate().equals(today)).count();
        List<Occurrence> occurrences = groups.stream().flatMap(List::stream).toList();
        Map<String, Long> todayFeedback = countFeedbackOn(today);

        return new ChatStatsResponse(
                new ChatStatsResponse.Summary(items.size(), repeatCount, todayCount,
                        todayFeedback.getOrDefault(ChatMessageFeedback.LIKE.name(), 0L),
                        todayFeedback.getOrDefault(ChatMessageFeedback.DISLIKE.name(), 0L),
                        countByReason(occurrences),
                        countByReason(occurrences.stream()
                                .filter(o -> o.occurredAt().toLocalDate().equals(today)).toList())),
                items.stream()
                        .filter(i -> reasonFilter == null || i.reasons().contains(reasonFilter))
                        .map(Item::candidate)
                        .limit(size)
                        .toList());
    }

    private static String parseReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return null;
        }
        try {
            return UnresolvedReason.valueOf(reason.trim().toUpperCase()).name();
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "reason은 NO_MATCH, LOW_SIMILARITY, ANSWER_FAILED, DISLIKED 중 하나여야 합니다.");
        }
    }

    // 사유별 발생 수. 자동 감지된 답변에 (아쉬워요)까지 받으면 두 사유에 하나씩 센다
    private static Map<String, Long> countByReason(List<Occurrence> occurrences) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (UnresolvedReason r : UnresolvedReason.values()) {
            counts.put(r.name(), 0L);
        }
        occurrences.forEach(o -> counts.merge(o.reason(), 1L, Long::sum));
        return counts;
    }

    // 그날(KST) 누른 (좋아요)/(아쉬워요) 수. 취소한 피드백은 빠진다
    private Map<String, Long> countFeedbackOn(LocalDate day) {
        return chatMessageRepository.countFeedbackBetween(day.atStartOfDay(), day.plusDays(1).atStartOfDay()).stream()
                .collect(Collectors.toMap(ChatMessageRepository.FeedbackCountRow::getFeedback,
                        ChatMessageRepository.FeedbackCountRow::getCount));
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

    // (아쉬워요) 이유 최근 순 COMMENT_LIMIT개
    private static List<String> recentComments(List<Occurrence> list) {
        return list.stream()
                .filter(o -> o.comment() != null)
                .sorted(Comparator.comparing(Occurrence::occurredAt).reversed())
                .map(Occurrence::comment)
                .limit(COMMENT_LIMIT)
                .toList();
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
                        row.getReason(), row.getCreatedAt(), null)));
        reportRepository.findAllByStatus(ReportStatus.OPEN).forEach(report ->
                occurrences.add(new Occurrence(report.getMessage().getId(), report.getQuestionText(),
                        report.getMessage().getContent(), UnresolvedReason.DISLIKED.name(), report.getCreatedAt(),
                        report.getComment())));

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