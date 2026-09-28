-- FAQ 임베딩은 최초 구현부터 question과 answer를 결합해 생성했다.
-- 기존 벡터를 다시 계산하지 않고 잘못 기록된 정책 버전만 v2로 바로잡는다.
UPDATE faqs
SET embedding_version = 'v2'
WHERE embedding IS NOT NULL
  AND embedding_version = 'v1';
