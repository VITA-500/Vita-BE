package com.vita.faq.batch;

import com.vita.faq.taxonomy.FaqTaxonomy;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FaqTaxonomyTest {
    @Test
    void supportsExactlySharedCategories() {
        assertThat(FaqTaxonomy.supportedCategories()).containsExactlyInAnyOrder(
            "유심(USIM) 업데이트 · 교체", "모바일", "인터넷/IPTV", "전화", "결합 할인",
            "해외로밍", "소상공인", "가입 및 변경", "요금 및 납부", "서비스안내");
        assertThat(FaqTaxonomy.supportedCategories().stream()
            .mapToInt(category -> FaqTaxonomy.supportedSubcategories(category).size()).sum()).isEqualTo(43);
    }

    @Test
    void validatesParentChildRelationshipAndRejectsOldCategories() {
        assertThat(FaqTaxonomy.supports("모바일", "요금제")).isTrue();
        assertThat(FaqTaxonomy.supports("인터넷/IPTV", "IPTV 장애/고장")).isTrue();
        assertThat(FaqTaxonomy.supports("모바일", "IPTV 장애/고장")).isFalse();
        assertThat(FaqTaxonomy.supports("로밍")).isFalse();
        assertThat(FaqTaxonomy.supports("가입/개통")).isFalse();
        assertThat(FaqTaxonomy.supports("유심/eSIM")).isFalse();
        assertThat(FaqTaxonomy.supports(null)).isFalse();
        assertThat(FaqTaxonomy.supports("모바일", null)).isFalse();
        assertThat(FaqTaxonomy.supports("모바일", "")).isFalse();
    }

    @Test
    void callersCannotModifySharedTaxonomy() {
        assertThatThrownBy(() -> FaqTaxonomy.supportedCategories().clear())
            .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> FaqTaxonomy.supportedSubcategories("모바일").clear())
            .isInstanceOf(UnsupportedOperationException.class);
    }
}
