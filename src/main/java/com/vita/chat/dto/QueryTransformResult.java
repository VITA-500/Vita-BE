package com.vita.chat.dto;

public record QueryTransformResult(
        String faqQuery,
        String planQuery,
        PlanIntent planIntent,
        boolean structured,
        PriceRange priceRange,
        DataRange dataRange,
        StoreIntent storeIntent) {

    public QueryTransformResult(String faqQuery, String planQuery, PlanIntent planIntent,
            boolean structured, PriceRange priceRange) {
        this(faqQuery, planQuery, planIntent, structured, priceRange, DataRange.none());
    }

    public QueryTransformResult(String faqQuery, String planQuery, PlanIntent planIntent, boolean structured) {
        this(faqQuery, planQuery, planIntent, structured, PriceRange.none(), DataRange.none());
    }

    public QueryTransformResult(String faqQuery, String planQuery, PlanIntent planIntent,
                                boolean structured, PriceRange priceRange, DataRange dataRange) {
        this(faqQuery, planQuery, planIntent, structured, priceRange, dataRange, StoreIntent.none());
    }

    public static QueryTransformResult original(String question) {
        return new QueryTransformResult(question, question, PlanIntent.none(), false);
    }
}