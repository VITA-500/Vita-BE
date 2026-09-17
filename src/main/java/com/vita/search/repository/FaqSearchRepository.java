package com.vita.search.repository;

import com.vita.search.entity.Faq;
import com.vita.search.entity.FaqStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** FAQ 키워드(LIKE) 검색용 리포지토리. */
public interface FaqSearchRepository extends JpaRepository<Faq, Long> {

	/**
	 * 임베딩 적재 전까지 쓰는 fallback 검색 — Phase 2에서 pgvector 유사도 검색으로 대체 예정.
	 *
	 * @param keyword  question/answer에 대해 대소문자 무시하고 부분 일치(LIKE) 검색
	 * @param status   이 상태인 FAQ만 검색 대상 (보통 ACTIVE)
	 * @param pageable 페이지 번호/크기/정렬
	 */
	@Query("""
			SELECT f FROM Faq f
			WHERE f.status = :status
			  AND (LOWER(f.question) LIKE LOWER(CONCAT('%', :keyword, '%'))
			       OR LOWER(f.answer) LIKE LOWER(CONCAT('%', :keyword, '%')))
			""")
	Page<Faq> searchByKeyword(@Param("keyword") String keyword, @Param("status") FaqStatus status, Pageable pageable);
}
