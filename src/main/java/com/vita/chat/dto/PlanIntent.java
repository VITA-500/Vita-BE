package com.vita.chat.dto;

import com.vita.search.service.PlanSortKey;

public record PlanIntent(boolean extreme, PlanSortKey sortKey, int limit) {

	private static final PlanIntent NONE = new PlanIntent(false, null, 0);

	public static PlanIntent none() {
		return NONE;
	}
}
