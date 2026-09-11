package com.vita.sample.dto;

import com.vita.sample.entity.SampleItem;
import java.time.LocalDateTime;

/** 요청/응답 DTO는 record로, 둘 다 해당 도메인 dto/에 같이 둔다 (08_개발표준 1절). */
public record SampleItemResponse(Long id, String title, String content, LocalDateTime createdAt) {

	public static SampleItemResponse from(SampleItem item) {
		return new SampleItemResponse(item.getId(), item.getTitle(), item.getContent(), item.getCreatedAt());
	}
}
