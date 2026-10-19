package com.vita.chat.service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.vita.chat.dto.PlanIntent;
import com.vita.search.dto.FaqReference;
import com.vita.search.dto.PlanReference;
import com.vita.search.service.NonMobilePlanQueryDetector;
import com.vita.search.service.PlanLookupService;
import com.vita.search.service.PlanQueryConditionExtractor;
import com.vita.search.service.PlanQueryConditions;
import com.vita.search.service.PlanSortKey;
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

	/**
	 * @param plans         LLM에 넘긴 요금제. 답변에 나온 것만 골라 요금제 카드로 쓴다
	 * @param extremeLookup 극값 조회를 했다면 그 입력과 결과(Trace/Span 기록용). 극값 질문이 아니면 null
	 */
	public record ChatContext(String text, List<FaqReference> faqs, List<PlanReference> plans, ExtremeLookup extremeLookup) {
		public ChatContext(String text, List<FaqReference> faqs, List<PlanReference> plans) {
			this(text, faqs, plans, null);
		}

		public ChatContext(String text, List<FaqReference> faqs) {
			this(text, faqs, List.of());
		}

		public static ChatContext empty() {
			return new ChatContext("", List.of());
		}
	}

	/**
	 * 극값 조회(가장 저렴한 요금제 등)의 입력과 결과. 기록용이라 context 내용에는 영향이 없다.
	 *
	 * @param sortKey        정렬 기준
	 * @param limit          기본으로 안내할 개수
	 * @param scopeGroup     조회한 대상 그룹(질문에 대상이 없으면 GENERAL)
	 * @param conditions     질문에서 읽어 조회 범위를 좁힌 조건(대상 그룹 제외)
	 * @param priceRange     변환기가 읽은 월 요금 범위
	 * @param dataRange      변환기가 읽은 데이터량 범위
	 * @param nonMobileGuard 인터넷·IPTV·TV 요금 질문이라 모바일 요금제를 조회하지 않았는지
	 * @param fetched        조회 서비스가 돌려준 개수(범위로 거르기 전)
	 * @param inRange        가격·데이터 범위로 거른 뒤 개수
	 * @param returned       동률까지 포함해 최종으로 context에 넣은 요금제
	 * @param noMatch        범위 조건이 있는데 맞는 요금제가 없었는지
	 */
	public record ExtremeLookup(PlanSortKey sortKey, int limit, String scopeGroup, PlanQueryConditions conditions,
			PriceRange priceRange, DataRange dataRange, boolean nonMobileGuard, int fetched, int inRange,
			List<PlanReference> returned, boolean noMatch) {
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
	    return build(question, faqs, plans, intent, priceRange, dataRange, null);
	}

	/**
	 * @param planNotice 요금제 전체 목록을 물은 질문에서 검색(BE3)이 붙인 안내 한 줄(전체 목록이라는 점, 기기 전용 요금제가
	 *                   빠졌다는 점). 일반 질문이면 null이다. 일반 요금제 목록 바로 앞에 {@code <plan_notice>}로 넣는다.
	 */
	public ChatContext build(String question, List<FaqReference> faqs, List<PlanReference> plans,
	        PlanIntent intent, PriceRange priceRange, DataRange dataRange, String planNotice) {

		List<PlanReference> extremePlans = List.of();
		int fetched = 0;
		int inRangeCount = 0;
		if (intent.extreme()) {
		    boolean hasRange = !priceRange.isEmpty() || !dataRange.isEmpty();
		    int fetch = hasRange ? EXTREME_FETCH_LIMIT : intent.limit();
		    List<PlanReference> fetchedPlans = planLookupService.findExtremeForQuery(intent.sortKey(), fetch, question);
		    fetched = fetchedPlans.size();
		    List<PlanReference> inRange = fetchedPlans.stream()
		            .filter(p -> priceRange.contains(p.monthlyFee()))
		            .filter(p -> dataRange.contains(isUnlimited(p), p.baseDataMb()))
		            .toList();
		    inRangeCount = inRange.size();
		    // 정렬 기준 값이 같은 요금제(무제한이 둘일 때 등)는 limit을 넘어도 함께 안내한다.
		    extremePlans = PlanLookupService.limitWithTies(inRange, intent.sortKey(), intent.limit());
		}

	 // 범위 조건이 있는 극값 질문인데 조건에 맞는 요금제가 없는 경우
		boolean noMatch = intent.extreme() && (!priceRange.isEmpty() || !dataRange.isEmpty())
		        && extremePlans.isEmpty();
	    List<PlanReference> generalPlans = (extremePlans.isEmpty() && !noMatch) ? plans : List.of();

	    ExtremeLookup lookup = intent.extreme()
	            ? extremeLookupRecord(question, intent, priceRange, dataRange, fetched, inRangeCount, extremePlans, noMatch)
	            : null;

	    if (faqs.isEmpty() && plans.isEmpty() && extremePlans.isEmpty() && !noMatch) {
			log.info("관련 FAQ/요금제 없음. question={}", question);
			return new ChatContext("", List.of(), List.of(), lookup);
		}

		StringBuilder sb = new StringBuilder();

		if (!extremePlans.isEmpty()) {
			sb.append("<comparison_result>\n")
			  .append(extremePlans.stream().map(this::toPlanXml).collect(Collectors.joining("\n")))
			  .append("\n</comparison_result>\n");
		} else if (noMatch) {
			sb.append("<comparison_result>\n확인 결과: 사용자가 요청한 조건에 해당하는 요금제가 없음. 이 사실을 그대로 안내할 것.\n</comparison_result>\n");
		}

		if (planNotice != null && !planNotice.isBlank() && !generalPlans.isEmpty()) {
			sb.append("<plan_notice>\n").append(PromptEscaper.escape(planNotice)).append("\n</plan_notice>\n");
		}
		generalPlans.stream().map(this::toPlanXml).forEach(xml -> sb.append(xml).append("\n"));
		faqs.stream().map(this::toFaqXml).forEach(xml -> sb.append(xml).append("\n"));

		log.info("context plan count: extreme={}, general={}, extremeNames={}, generalNames={}",
				extremePlans.size(), generalPlans.size(),
				extremePlans.stream().map(PlanReference::name).toList(),
				generalPlans.stream().map(PlanReference::name).toList());

		List<PlanReference> contextPlans = new ArrayList<>(extremePlans);
		contextPlans.addAll(generalPlans);
		return new ChatContext(sb.toString(), faqs, List.copyOf(contextPlans), lookup);
	}
	
	/** 극값 조회 기록을 만든다. 대상 그룹과 조건은 조회 서비스가 쓰는 것과 같은 추출기로 다시 읽는다(순수 함수라 결과가 같다). */
	private static ExtremeLookup extremeLookupRecord(String question, PlanIntent intent, PriceRange priceRange,
			DataRange dataRange, int fetched, int inRange, List<PlanReference> returned, boolean noMatch) {
		String group = PlanQueryConditionExtractor.targetGroupOf(question);
		return new ExtremeLookup(intent.sortKey(), intent.limit(), group != null ? group : PlanLookupService.GENERAL_GROUP,
				PlanQueryConditionExtractor.extractAssumingPlanContext(question).withoutTargetGroup(), priceRange, dataRange,
				NonMobilePlanQueryDetector.matches(question), fetched, inRange, List.copyOf(returned), noMatch);
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