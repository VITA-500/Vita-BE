package com.vita.sample.dto;

import jakarta.validation.constraints.NotBlank;

/** 요청 DTO는 Bean Validation 필수 (08_개발표준 2절). */
public record SampleItemCreateRequest(

		@NotBlank
		String title,

		String content
) {
}
