package com.vita.search.entity;

import com.vita.common.entity.BaseTimeEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * faq 테이블의 검색 전용(읽기 전용) 뷰. embedding 등 BE2가 관리하는 컬럼은 매핑하지 않는다 —
 * 벡터 검색(Phase 2)은 네이티브 쿼리로 처리할 예정이라 이 엔티티에 필요 없음.
 */
@Entity
@Table(name = "faq")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Faq extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	private String category;

	private String subcategory;

	private String question;

	private String answer;

	@Enumerated(EnumType.STRING)
	private FaqStatus status;
}
