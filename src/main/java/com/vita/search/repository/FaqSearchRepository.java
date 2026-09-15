package com.vita.search.repository;

import com.vita.search.entity.Faq;
import com.vita.search.entity.FaqStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FaqSearchRepository extends JpaRepository<Faq, Long> {

	/** 임베딩 적재 전까지 쓰는 fallback 검색 — Phase 2에서 pgvector 유사도 검색으로 대체 예정. */
	@Query("""
			SELECT f FROM Faq f
			WHERE f.status = :status
			  AND (LOWER(f.question) LIKE LOWER(CONCAT('%', :keyword, '%'))
			       OR LOWER(f.answer) LIKE LOWER(CONCAT('%', :keyword, '%')))
			""")
	Page<Faq> searchByKeyword(@Param("keyword") String keyword, @Param("status") FaqStatus status, Pageable pageable);
}
