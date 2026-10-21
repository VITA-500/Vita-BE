-- 쓰지 않는 DB 객체 정리.
-- faqs_en / plans_en: 영어로 번역해 검색하는 실험(V18)의 테이블. 영어 번역 검색은 도입하지 않기로 했고(한국어와 같거나 낮은 점수),
--   번역·영어 검색 코드는 이미 제거돼 엔티티·리포지토리·SQL이 없다. 서비스 검색에 쓰는 faqs / plans는 건드리지 않는다.
-- idx_store_location: stores(lat, lng) 인덱스. 거리 계산은 모든 매장 행에 Haversine을 적용한 뒤 정렬·필터하고
--   lat/lng를 직접 비교하는 조건이 없어 이 인덱스를 타는 쿼리가 없다(V4에서 store가 stores로 바뀐 뒤에도 인덱스 이름은 그대로).
DROP TABLE IF EXISTS public.plans_en;
DROP TABLE IF EXISTS public.faqs_en;
DROP INDEX IF EXISTS public.idx_store_location;
