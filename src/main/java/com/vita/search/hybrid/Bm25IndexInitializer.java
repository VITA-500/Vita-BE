package com.vita.search.hybrid;

import java.util.List;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Hybrid 검색을 켰을 때 시작 단계에서 환경을 확인하고(pg_search 확장), 설정에 따라 BM25 인덱스를 만든다.
 *
 * <p>pg_search는 로컬 ParadeDB에만 있고 AWS RDS에는 없다. 그래서 인덱스 생성을 Flyway에 넣지 않고 {@code create-indexes=true}일 때만
 * 여기서 {@code IF NOT EXISTS}로 만든다. Hybrid를 켰는데 DB에 pg_search가 없으면 질의 때 가서 실패하는 대신 시작 단계에서 바로 멈춘다.
 * Hybrid 검색 저장소는 이 빈이 먼저 만들어지도록 의존한다.
 */
@Slf4j
@Component("bm25IndexInitializer")
@ConditionalOnProperty(prefix = "search.hybrid", name = "enabled", havingValue = "true")
public class Bm25IndexInitializer implements InitializingBean {

	/** 한국어 BM25 인덱스. FAQ는 질문·답변, 요금제는 이름·요약·설명을 색인한다. */
	static final List<String> INDEX_DDL = List.of(
			"CREATE INDEX IF NOT EXISTS faqs_bm25_ko ON faqs USING bm25 "
					+ "(id, (question::pdb.lindera('korean')), (answer::pdb.lindera('korean')))",
			"CREATE INDEX IF NOT EXISTS plans_bm25_ko ON plans USING bm25 "
					+ "(id, (name::pdb.lindera('korean')), (summary::pdb.lindera('korean')), (description::pdb.lindera('korean')))");

	private final JdbcTemplate jdbcTemplate;
	private final HybridProperties properties;

	public Bm25IndexInitializer(DataSource dataSource, HybridProperties properties) {
		this.jdbcTemplate = new JdbcTemplate(dataSource);
		this.properties = properties;
	}

	@Override
	public void afterPropertiesSet() {
		Integer extensions = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM pg_extension WHERE extname = 'pg_search'", Integer.class);
		if (extensions == null || extensions == 0) {
			throw new IllegalStateException("search.hybrid.enabled=true인데 DB에 pg_search 확장이 없습니다. "
					+ "Hybrid 검색은 ParadeDB(pg_search) 로컬 환경에서만 켤 수 있고, AWS RDS(dev/prod)에서는 꺼야 합니다.");
		}
		if (properties.createIndexes()) {
			INDEX_DDL.forEach(jdbcTemplate::execute);
			log.info("Hybrid BM25 인덱스를 확인·생성했습니다: faqs_bm25_ko, plans_bm25_ko");
		}
		log.info("Hybrid 검색 사용: FAQ 벡터 {} : 키워드 {} (k={}, 후보 {}), 요금제 벡터 {} : 키워드 {} (k={}, 후보 {})",
				properties.faq().vectorWeight(), properties.faq().keywordWeight(), properties.faq().rrfK(),
				properties.faq().candidateLimit(), properties.plan().vectorWeight(), properties.plan().keywordWeight(),
				properties.plan().rrfK(), properties.plan().candidateLimit());
	}
}
