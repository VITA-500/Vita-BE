package com.vita.chat.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.vita.chat.FallbackType;
import com.vita.chat.service.QuestionTranslator.TranslationOutcome;
import com.vita.search.eval.CachedQueryTransformer;
import com.vita.search.pipeline.QueryTransformer;
import com.vita.search.pipeline.TransformInfo;
import com.vita.search.pipeline.TransformedQuery;

/**
 * 실험 D: 질문 변환(Query Transformation, 실험 C)의 결과를 영어로 번역해 영어 임베딩 검색(실험 B)에 넘기는 변환기.
 * 순서는 "변환 후 번역"이다: 사용자 질문 → 변환(FAQ용·요금제용 질문) → 각각 영어로 번역 → 영어 검색기.
 *
 * <p>변환기와 번역기는 C와 B가 쓴 것을 그대로 쓴다(변환은 {@code bedrockQueryTransformer}, 번역은 {@link QuestionTranslator}).
 * 그래서 D와 C의 차이는 "변환 결과를 영어로 번역해 영어 임베딩에서 찾는다" 하나이고, D와 B의 차이는 "번역 전에 질문 변환을 거친다"
 * 하나다. 번역은 변환 결과 문자열 전체({@code 조건: ... 질문: ...}처럼 라벨이 붙은 형태)를 그대로 넘기고, 번역기가 그 문자열을
 * 한 줄 영어로 바꾼다. 번역기의 검증(숫자·요금제명 보존)도 그대로 적용된다.
 *
 * <h4>폴백과 평가 기록</h4>
 * <ul>
 *   <li>번역이 실패하면(입력 이상·검증 실패·LLM 오류) 번역기가 입력(변환된 한국어 질문)을 돌려주므로 그 한국어 질문으로 영어
 *       임베딩을 검색한다. 이 질문은 {@code FALLBACK_ORIGINAL}로 기록하고 사유는 {@code TRANSLATION_*}다
 *       (LLM 호출 실패는 평가의 변환 결과 저장 파일이 다음 실행에서 다시 시도하도록 {@code CALL_FAILED}). FAQ용·요금제용 중
 *       한쪽만 실패해도 기록한다.</li>
 *   <li>번역은 성공했지만 질문 변환이 폴백이었다면(질문 원문이 그대로 번역된다) 변환의 폴백 기록을 그대로 둔다. 이때 D는 그 질문에서
 *       B와 같게 동작한다.</li>
 *   <li>변환기가 비워 둔 쪽(null)은 번역하지 않고 비워 둔다. 평가용 변환 어댑터는 비운 쪽을 원문으로 채워 주므로 보통은 둘 다 있다.</li>
 * </ul>
 *
 * <h4>평가에서 쓰는 법</h4>
 * <p>변환기 빈 이름은 {@value #BEAN_NAME}이고, 영어 검색기({@code englishFaqRetriever}, {@code englishPlanRetriever})와 함께 쓴다.
 * 질문 변환 단계는 C에서 저장한 변환 결과 파일을 다시 쓰는 것을 권한다(C와 같은 변환 결과 위에서 번역만 더해 비교하고, 변환 호출도 아낀다).
 * {@code search.experiment.d.qt-cache}에 그 파일 경로(쉼표로 여러 개)를 적으면 변환 단계는 저장된 결과만 쓰고, 파일에 없는 질문이
 * 나오면 다른 프롬프트로 변환해 비교가 어긋나지 않도록 예외로 멈춘다. 비워 두면 변환도 실제로 호출한다(응답 시간 측정용).
 * 번역까지 끝난 최종 결과는 평가 러너의 {@code transform-cache} 파일에 저장한다.
 */
@Component(TranslatedQueryTransformer.BEAN_NAME)
public class TranslatedQueryTransformer implements QueryTransformer {

    /** 설정({@code search.eval.query-transformer} 등)에서 이 변환기를 가리킬 때 쓰는 빈 이름. */
    public static final String BEAN_NAME = "translatedQueryTransformer";

    /** 폴백 사유의 접두어. 질문 변환 폴백 사유와 평가 집계에서 구분된다(B의 번역 어댑터와 같은 값). */
    static final String REASON_PREFIX = "TRANSLATION_";

    private final QueryTransformer queryTransformer;
    private final QuestionTranslator translator;

    /**
     * @param bedrockQueryTransformer 질문 변환(C)에 쓰는 변환기
     * @param translator              변환 결과를 영어로 바꾸는 번역기(B)
     * @param storedResultFiles       질문 변환 단계에서 다시 쓸 C의 변환 결과 파일(쉼표로 구분). 비우면 변환기를 실제로 부른다
     */
    @Autowired
    public TranslatedQueryTransformer(
            @Qualifier("bedrockQueryTransformer") QueryTransformer bedrockQueryTransformer,
            QuestionTranslator translator,
            @Value("${search.experiment.d.qt-cache:}") String storedResultFiles) {
        this(withStoredResults(bedrockQueryTransformer, storedResultFiles), translator);
    }

    TranslatedQueryTransformer(QueryTransformer queryTransformer, QuestionTranslator translator) {
        this.queryTransformer = queryTransformer;
        this.translator = translator;
    }

    @Override
    public TransformedQuery transform(String query) {
        TransformedQuery transformed = queryTransformer.transform(query);
        String faqText = transformed.faqQuery();
        String planText = transformed.planQuery();

        TranslationOutcome faq = translate(faqText);
        // FAQ용과 요금제용 질문이 같으면(둘 다 원문으로 채워진 경우) 한 번만 번역한다.
        TranslationOutcome plan = planText != null && planText.equals(faqText) ? faq : translate(planText);

        return new TransformedQuery(query, translated(faq), translated(plan), infoOf(transformed.info(), faq, plan));
    }

    /** 비어 있는 질문은 번역하지 않는다(null을 돌려준다). */
    private TranslationOutcome translate(String text) {
        if (text == null) {
            return null;
        }
        return translator.translateWithStatus(text);
    }

    private static String translated(TranslationOutcome outcome) {
        return outcome == null ? null : outcome.translated();
    }

    /** 번역이 한 번이라도 폴백이면 그 사유를, 아니면 질문 변환의 기록을 그대로 쓴다. */
    private static TransformInfo infoOf(TransformInfo queryTransformInfo, TranslationOutcome faq, TranslationOutcome plan) {
        TranslationOutcome failed = faq != null && faq.fallback() ? faq : (plan != null && plan.fallback() ? plan : null);
        if (failed != null) {
            return TransformInfo.fallback(reason(failed.fallbackType()));
        }
        return queryTransformInfo;
    }

    /**
     * LLM 호출 실패는 일시적일 수 있어, 평가의 변환 결과 저장 파일이 저장하지 않고 다음 실행에서 다시 시도하는 사유(CALL_FAILED)로
     * 넘긴다. 입력 이상·출력 검증 실패는 다시 불러도 같은 결과일 가능성이 높아 번역 전용 사유로 남긴다.
     */
    private static String reason(FallbackType type) {
        if (type == FallbackType.LLM_ERROR) {
            return TransformInfo.REASON_CALL_FAILED;
        }
        return REASON_PREFIX + (type == null ? "UNKNOWN" : type.name());
    }

    /**
     * 질문 변환 단계가 저장된 결과만 쓰도록 변환기를 감싼다. 파일이 여러 개면 앞의 파일부터 찾는다. 어느 파일에도 없는 질문은
     * 예외를 던진다(실제 변환을 부르면 C와 다른 프롬프트의 결과가 섞여 D와 C를 비교할 수 없다).
     *
     * @param live  저장된 결과가 없을 때 쓰는 실제 변환기. 파일을 지정하지 않으면 이 변환기를 그대로 돌려준다
     * @param files 변환 결과 파일 경로(쉼표로 구분). 지정한 파일이 없으면 예외를 던진다
     */
    static QueryTransformer withStoredResults(QueryTransformer live, String files) {
        if (files == null || files.isBlank()) {
            return live;
        }
        List<Path> paths = new ArrayList<>();
        for (String file : Arrays.asList(files.split(","))) {
            if (!file.isBlank()) {
                Path path = Path.of(file.strip());
                if (!Files.isRegularFile(path)) {
                    throw new IllegalStateException("search.experiment.d.qt-cache의 변환 결과 파일이 없습니다: " + path);
                }
                paths.add(path);
            }
        }
        String fileList = paths.toString();
        QueryTransformer chain = query -> {
            throw new IllegalStateException("저장된 질문 변환 결과에 없는 질문입니다(D는 C와 같은 변환 결과를 써야 합니다): "
                    + query + " / 파일 " + fileList);
        };
        List<Path> backwards = new ArrayList<>(paths);
        Collections.reverse(backwards);
        for (Path path : backwards) {
            chain = new CachedQueryTransformer(chain, path);
        }
        return chain;
    }
}
