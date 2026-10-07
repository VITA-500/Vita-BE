package com.vita.chat.service;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.vita.chat.dto.PlanIntent;
import com.vita.search.dto.FaqReference;
import com.vita.search.dto.PlanReference;
import com.vita.search.service.PlanLookupService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@RequiredArgsConstructor
@Slf4j
public class ChatContextBuilder {

	private final PlanLookupService planLookupService;

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
	        PlanIntent intent) {

	    List<PlanReference> extremePlans = List.of();
	    if (intent.extreme()) {
	        extremePlans = planLookupService.findExtremeForQuery(intent.sortKey(), intent.limit(), question);
	    }

		List<PlanReference> generalPlans = extremePlans.isEmpty() ? plans : List.of();

		if (faqs.isEmpty() && plans.isEmpty() && extremePlans.isEmpty()) {
			log.info("관련 FAQ/요금제 없음. question={}", question);
			return ChatContext.empty();
		}

		StringBuilder sb = new StringBuilder();

		if (!extremePlans.isEmpty()) {
			sb.append("<comparison_result>\n")
			  .append(extremePlans.stream().map(this::toPlanXml).collect(Collectors.joining("\n")))
			  .append("\n</comparison_result>\n");
		}

		generalPlans.stream().map(this::toPlanXml).forEach(xml -> sb.append(xml).append("\n"));
		faqs.stream().map(this::toFaqXml).forEach(xml -> sb.append(xml).append("\n"));

		log.info("context plan count: extreme={}, general={}, extremeNames={}, generalNames={}",
				extremePlans.size(), generalPlans.size(),
				extremePlans.stream().map(PlanReference::name).toList(),
				generalPlans.stream().map(PlanReference::name).toList());

		return new ChatContext(sb.toString(), faqs);
	}

	private String toFaqXml(FaqReference faq) {
		return """
				<document>
				<category>%s / %s</category>
				<question>%s</question>
				<answer>%s</answer>
				</document>
				""".formatted(faq.category(), faq.subcategory(), faq.question(), faq.answer());
	}

	private String toPlanXml(PlanReference p) {
		return """
				<plan>
				<name>%s</name>
				<monthly_fee>월 %,d원</monthly_fee>
				<summary>%s</summary>
				<description>%s</description>
				</plan>
				""".formatted(p.name(), p.monthlyFee(), p.summary(), p.description());
	}
}