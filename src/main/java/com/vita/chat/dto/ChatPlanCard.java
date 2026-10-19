package com.vita.chat.dto;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.vita.search.dto.PlanReference;

/**
 * 채팅 답변의 요금제 카드. FE는 이 값으로 카드를 그리고, 없으면(과거 메시지) 답변 글을 해석한다.
 * 답변 시점 값을 메시지에 그대로 저장해서, 요금제가 나중에 바뀌어도 과거 대화는 당시 카드로 보인다.
 * 필드는 BE3 PlanReference와 같다 (검색용 similarity, updatedAt 제외)
 */
public record ChatPlanCard(
		Long planId,
		String planCode,
		String name,
		String summary,
		int monthlyFee,
		String description,
		String networkType,
		String targetGroup,
		Integer minAge,
		Integer maxAge,
		String dataPolicy,
		Long baseDataMb,
		Integer exhaustedSpeedKbps,
		String voicePolicy,
		Integer voiceMinutes,
		String smsPolicy,
		Integer smsCount) {

	public static ChatPlanCard from(PlanReference p) {
		return new ChatPlanCard(p.planId(), p.planCode(), p.name(), p.summary(), p.monthlyFee(), p.description(),
				p.networkType(), p.targetGroup(), p.minAge(), p.maxAge(), p.dataPolicy(), p.baseDataMb(),
				p.exhaustedSpeedKbps(), p.voicePolicy(), p.voiceMinutes(), p.smsPolicy(), p.smsCount());
	}

	/**
	 * LLM에 넘긴 요금제 중 답변에 이름이 나온 것만, 답변에 나온 순서대로.
	 * 띄어쓰기는 무시하고 비교한다 ("비타5G슬림"도 찾음)
	 * 긴 이름부터 찾고 찾은 자리는 지워서, "비타 맥스 쉐어" 안의 "비타 맥스"를 따로 잡지 않는다.
	 * 이름 바로 뒤에 숫자가 이어지면 다른 요금제로 본다 ("라이트 5"가 "라이트 50" 안에서 잡히지 않게)
	 */
	public static List<ChatPlanCard> mentionedIn(String answer, List<PlanReference> plans) {
		if (answer == null || answer.isBlank() || plans == null || plans.isEmpty()) {
			return List.of();
		}
		StringBuilder text = new StringBuilder(compact(answer));
		List<PlanReference> longestFirst = plans.stream()
				.filter(p -> p.name() != null && !compact(p.name()).isEmpty())
				.sorted(Comparator.comparingInt((PlanReference p) -> compact(p.name()).length()).reversed())
				.toList();

		record Found(int index, PlanReference plan) {}
		List<Found> found = new ArrayList<>();
		List<Long> seen = new ArrayList<>();
		for (PlanReference plan : longestFirst) {
			if (seen.contains(plan.planId())) {
				continue;
			}
			String name = compact(plan.name());
			int first = -1;
			for (int at = text.indexOf(name); at >= 0; at = text.indexOf(name, at + 1)) {
				int end = at + name.length();
				if (end < text.length() && Character.isDigit(text.charAt(end))) {
					continue;
				}
				if (first < 0) {
					first = at;
				}
				text.replace(at, end, "\0".repeat(name.length()));
			}
			if (first >= 0) {
				found.add(new Found(first, plan));
				seen.add(plan.planId());
			}
		}
		return found.stream()
				.sorted(Comparator.comparingInt(Found::index))
				.map(f -> from(f.plan()))
				.toList();
	}

	private static String compact(String s) {
		return s.replaceAll("\\s+", "");
	}
}
