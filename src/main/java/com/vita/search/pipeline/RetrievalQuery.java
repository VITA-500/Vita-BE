package com.vita.search.pipeline;

/**
 * 후보 검색 단계({@link FaqRetriever})에 넘기는 질문. 임베딩은 파이프라인이 이미 해 두어서, 검색기는 벡터를 그대로 쓰거나
 * (벡터 검색) 텍스트를 함께 쓴다(Hybrid의 BM25 같은 키워드 검색).
 *
 * @param text   FAQ 검색에 쓸 질문 텍스트({@link TransformedQuery#faqQuery()})
 * @param vector 그 텍스트의 임베딩 벡터(질문용 prefix 적용 완료)
 */
public record RetrievalQuery(String text, float[] vector) {
}
