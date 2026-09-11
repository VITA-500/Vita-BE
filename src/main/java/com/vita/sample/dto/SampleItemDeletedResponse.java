package com.vita.sample.dto;

/** 04_API명세서 0절 확정 컨벤션: 삭제도 204가 아니라 200 + body. */
public record SampleItemDeletedResponse(Long id, boolean deleted) {
}
