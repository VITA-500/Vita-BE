package com.vita.sample.entity;

import com.vita.common.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 패키지 구조 예시용 엔티티 — 실제 도메인 데이터가 아니다.
 * BaseTimeEntity 상속, protected 기본 생성자 + 의미 있는 생성자, setter 대신 의도가 드러나는
 * 메서드(update)를 쓰는 패턴을 그대로 따라 만들면 된다 (08_개발표준 2절).
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "sample_item")
public class SampleItem extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private String title;

	@Column(columnDefinition = "text")
	private String content;

	public SampleItem(String title, String content) {
		this.title = title;
		this.content = content;
	}

	public void update(String title, String content) {
		this.title = title;
		this.content = content;
	}
}
