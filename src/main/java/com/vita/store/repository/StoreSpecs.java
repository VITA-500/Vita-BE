package com.vita.store.repository;

import com.vita.store.entity.Store;
import com.vita.store.entity.StoreBenefit;
import com.vita.store.entity.StoreType;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;

/**
 * 관리자 매장 목록 검색 조건
 * 값이 비어 있으면 null을 돌려줘서 조건에서 빠진다
 */
public final class StoreSpecs {

    private StoreSpecs(){ }

    /** 이름 또는 주소에 포함 */
    public static Specification<Store> keyword(String keyword){
        if(keyword == null || keyword.isBlank()){
            return null;
        }
        String pattern = "%" + keyword.trim() + "%";
        return (root, query, cb)
                -> cb.or(cb.like(root.get("name"), pattern),
                cb.like(root.get("address"), pattern));
    }

    public static Specification<Store> storeType(StoreType storeType){
        return storeType == null ? null : (root, query, cb)
                                          -> cb.equal(root.get("storeType"), storeType);
    }

    /** 연결된 혜택의 업종이 같은 매장 */
    public static Specification<Store> category(String category){
        if(category == null || category.isBlank()){
            return null;
        }
        return (root, query, cb) -> {
            Subquery<Long> sub = query.subquery(Long.class);
            var sb = sub.from(StoreBenefit.class);
            sub.select(sb.get("id")).where(cb.equal(sb.get("store"), root),
                    cb.equal(sb.get("benefit").get("category"), category.trim()));
            return cb.exists(sub);
        };
    }

    /** 특정 브랜드에 연결된 매장 */
    public static Specification<Store> benefitId(Long benefitId){
        if(benefitId == null){
            return null;
        }
        return (root, query, cb) -> {
            Subquery<Long> sub = query.subquery(Long.class);
            var sb = sub.from(StoreBenefit.class);
            sub.select(sb.get("id")).where(cb.equal(sb.get("store"), root),
                    cb.equal(sb.get("benefit").get("id"), benefitId));
            return cb.exists(sub);
        };
    }

    /**
     * 정렬. updatedAt은 null(수정 안 한 매장)을 맨 뒤로 보내고, 마지막에 id 내림차순으로 순서를 고정한다
     */
    public static Specification<Store> orderBy(Sort sort){
        return (root, query, cb) -> {
            // 페이지 전체 개수를 세는 count 쿼리에는 정렬을 붙이지 않는다
            if (Long.class.equals(query.getResultType()) ||
                    long.class.equals(query.getResultType())) {
                return null;
            }
            List<Order> orders = new ArrayList<>();
            for (Sort.Order order : sort) {
                Path<?> path = root.get(order.getProperty());
                if ("updatedAt".equals(order.getProperty())) {
                    orders.add(cb.asc(cb.selectCase().when(cb.isNull(path), 1).otherwise(0)));
                }
                orders.add(order.isAscending() ? cb.asc(path) : cb.desc(path));
            }
            orders.add(cb.desc(root.get("id")));
            query.orderBy(orders);
            return null;
        };
    }
}
