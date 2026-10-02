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

public final class StoreSpecs {

    private StoreSpecs(){ }

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

    public static Specification<Store> orderBy(Sort sort){
        return (root, query, cb) -> {
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
