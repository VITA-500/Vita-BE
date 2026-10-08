package com.vita.chat.service;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.vita.chat.dto.PlanIntent;
import com.vita.search.dto.FaqReference;
import com.vita.search.dto.PlanReference;
import com.vita.search.service.PlanLookupService;
import com.vita.chat.dto.PriceRange;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.vita.chat.dto.DataRange;

@Component
@RequiredArgsConstructor
@Slf4j
public class ChatContextBuilder {

	private final PlanLookupService planLookupService;
	
	/** 가격 범위로 거른 뒤 limit만큼 쓰기 위해 넉넉히 조회한다 (요금제 수가 적어 부담 없음) */
	private static final int EXTREME_FETCH_LIMIT = 50;

	public record ChatContext(String text, List<FaqReference> faqs) {
		public static ChatContext empty() { return new ChatContext("", List.of()); }
	}

	/**
	 * 검색된 FAQ/요금제를 LLM에게 줄 하나의 context 문자열로 조립한다.
	 * 순서: 극값 비교 결과 → 일반 요금제 → FAQ
	 *
	 * @param question 사용자 원문 질문 (극값 판단과 극값 조회에 사용)
	 */
	public ChatContext build(String question, List<FaqReference> faqs, List<PlanReference> plans,
	        PlanIntent intent, PriceRange priceRange) {
	    return build(question, faqs, plans, intent, priceRange, DataRange.none());
	}

	public ChatContext build(String question, List<FaqReference> faqs, List<PlanReference> plans,
	        PlanIntent intent, PriceRange priceRange, DataRange dataRange) {

		List<PlanReference> extremePlans = List.of();
		if (intent.extreme()) {
		    boolean hasRange = !priceRange.isEmpty() || !dataRange.isEmpty();
		    int fetch = hasRange ? EXTREME_FETCH_LIMIT : intent.limit();
		    extremePlans = planLookupService.findExtremeForQuery(intent.sortKey(), fetch, question).stream()
		            .filter(p -> priceRange.contains(p.monthlyFee()))
		            .filter(p -> dataRange.contains(isUnlimited(p), p.baseDataMb()))
		            .limit(intent.limit())
		            .toList();
		}

	 // 범위 조건이 있는 극값 질문인데 조건에 맞는 요금제가 없는 경우
		boolean noMatch = intent.extreme() && (!priceRange.isEmpty() || !dataRange.isEmpty())
		        && extremePlans.isEmpty();
	    List<PlanReference> generalPlans = (extremePlans.isEmpty() && !noMatch) ? plans : List.of();

	    if (faqs.isEmpty() && plans.isEmpty() && extremePlans.isEmpty() && !noMatch) {
			log.info("관련 FAQ/요금제 없음. question={}", question);
			return ChatContext.empty();
		}

		StringBuilder sb = new StringBuilder();

		if (!extremePlans.isEmpty()) {
			sb.append("<comparison_result>\n")
			  .append(extremePlans.stream().map(this::toPlanXml).collect(Collectors.joining("\n")))
			  .append("\n</comparison_result>\n");
		} else if (noMatch) {
		    sb.append("<comparison_result>\n확인 결과: 사용자가 요청한 가격 조건에 해당하는 요금제가 없음. 이 사실을 그대로 안내할 것.\n</comparison_result>\n");
		}

		generalPlans.stream().map(this::toPlanXml).forEach(xml -> sb.append(xml).append("\n"));
		faqs.stream().map(this::toFaqXml).forEach(xml -> sb.append(xml).append("\n"));

		log.info("context plan count: extreme={}, general={}, extremeNames={}, generalNames={}",
				extremePlans.size(), generalPlans.size(),
				extremePlans.stream().map(PlanReference::name).toList(),
				generalPlans.stream().map(PlanReference::name).toList());

		return new ChatContext(sb.toString(), faqs);
	}
	
	private static boolean isUnlimited(PlanReference p) {
	    return "UNLIMITED".equals(String.valueOf(p.dataPolicy()));
	}

	private String toFaqXml(FaqReference faq) {
		return """
				<document>
				<category>%s / %s</category>
				<question>%s</question>
				<answer>%s</answer>
				</document>
				""".formatted(PromptEscaper.escape(faq.category()),
				PromptEscaper.escape(faq.subcategory()),
				PromptEscaper.escape(faq.question()),
				PromptEscaper.escape(faq.answer()));
	}

	private String toPlanXml(PlanReference p) {
		return """
				<plan>
				<name>%s</name>
				<monthly_fee>월 %,d원</monthly_fee>
				<summary>%s</summary>
				<description>%s</description>
				</plan>
				""".formatted(PromptEscaper.escape(p.name()),
				p.monthlyFee(),
				PromptEscaper.escape(p.summary()),
				PromptEscaper.escape(p.description()));
	}
}