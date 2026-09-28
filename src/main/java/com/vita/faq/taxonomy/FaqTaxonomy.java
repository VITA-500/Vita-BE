package com.vita.faq.taxonomy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** 배치와 관리자 API가 함께 사용하는 FAQ 분류. 분류 값은 JSON 한 곳에서 관리한다. */
public final class FaqTaxonomy {
    private static final Map<String, Set<String>> CATEGORIES = load();

    private FaqTaxonomy() {
    }

    public static boolean supports(String category) {
        return category != null && CATEGORIES.containsKey(category);
    }

    /** 서브카테고리가 제공된 경우 해당 메인카테고리에 속하는지 검사한다. */
    public static boolean supports(String category, String subcategory) {
        return supports(category) && subcategory != null && CATEGORIES.get(category).contains(subcategory);
    }

    public static Set<String> supportedCategories() {
        return CATEGORIES.keySet();
    }

    public static Set<String> supportedSubcategories(String category) {
        return supports(category) ? CATEGORIES.get(category) : Set.of();
    }

    private static Map<String, Set<String>> load() {
        var resource = new ClassPathResource("data/faq/category/faq_generation_categories.json");
        try (var input = resource.getInputStream()) {
            var root = new ObjectMapper().readTree(input);
            Map<String, Set<String>> categories = new LinkedHashMap<>();
            int pairCount = 0;
            for (var entry : root.path("categories")) {
                String category = entry.path("category").asText();
                Set<String> subcategories = new LinkedHashSet<>();
                for (var value : entry.path("subcategories")) {
                    String subcategory = value.asText();
                    if (subcategory.isBlank() || subcategory.length() > 50 || !subcategories.add(subcategory)) {
                        throw new IllegalStateException("FAQ 서브카테고리 설정이 올바르지 않습니다.");
                    }
                }
                if (category.isBlank() || category.length() > 50 || subcategories.isEmpty()
                    || categories.putIfAbsent(category, Collections.unmodifiableSet(subcategories)) != null) {
                    throw new IllegalStateException("FAQ 카테고리 설정이 올바르지 않습니다.");
                }
                pairCount += subcategories.size();
            }
            if (categories.isEmpty() || categories.size() != root.path("categoryCount").asInt()
                || pairCount != root.path("subcategoryCount").asInt()) {
                throw new IllegalStateException("FAQ 분류 개수 설정이 올바르지 않습니다.");
            }
            return Collections.unmodifiableMap(categories);
        } catch (IOException e) {
            throw new IllegalStateException("FAQ 분류 설정을 읽을 수 없습니다.", e);
        }
    }
}
