package com.vita.store.service;

import com.vita.common.exception.BusinessException;
import com.vita.common.exception.ErrorCode;
import com.vita.common.page.PageRequest;
import com.vita.common.page.PageResponse;
import com.vita.store.dto.request.BenefitRequest;
import com.vita.store.dto.response.*;
import com.vita.store.entity.Benefit;
import com.vita.store.exception.BenefitNotFoundException;
import com.vita.store.repository.BenefitRepository;
import com.vita.store.repository.StoreBenefitRepository;
import com.vita.store.repository.StoreRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BenefitService {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;
    private static final Set<String> ADMIN_SORT_FIELDS = Set.of("createdAt", "updatedAt", "brand");
    private static final String ADMIN_DEFAULT_SORT = "createdAt,desc";

    private final BenefitRepository benefitRepository;
    private final StoreRepository storeRepository;
    private final StoreBenefitRepository storeBenefitRepository;

    public BenefitListResponse findAll(String category){
        List<BenefitResponse> benefits = benefitRepository.findAllWithStoreCount();
        if(category != null && !category.isBlank()){
            String target = category.trim();
            benefits = benefits.stream().filter(benefit -> benefit.category().equals(target)).toList();
        }
        return new BenefitListResponse(benefits);
    }

    /**
     * 업종(카테고리)별 제휴 매장을 가까운 순으로 돌려준다.
     */
    public BenefitStoreListResponse findStores(String category, BigDecimal lat, BigDecimal lng,
                                               Double radiusKm, Integer limit, Long benefitId){
        if(radiusKm != null && radiusKm <= 0){
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "반경(radius)은 0보다 커야합니다.");
        }

        int size = limit == null ? DEFAULT_LIMIT : limit;
        if(size < 1 || size > MAX_LIMIT){
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "limit은 1~" + MAX_LIMIT + " 사이여야 합니다.");
        }

        String target = category.trim();
        List<BenefitStoreListResponse.Item> stores = storeRepository
                .findPartnersByCategory(lat, lng, target, benefitId, radiusKm, size).stream()
                .map(p -> new BenefitStoreListResponse.Item(p.getId(), p.getName(),
                        p.getAddress(), p.getLat(), p.getLng(), Math.round(p.getDistanceKm() * 100) / 100.0,
                        p.getBenefitId(), p.getBrand(), p.getCategory(), p.getBenefitName())).toList();
        return new BenefitStoreListResponse(target, stores);
    }

    // 관리자 혜택 목록
    public PageResponse<AdminBenefitResponse> searchForAdmin(PageRequest pageRequest, String category){
        if(pageRequest.page() < 0 || pageRequest.size() < 1 || pageRequest.size() > MAX_LIMIT){
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "page는 0 이상, size는 1~" + MAX_LIMIT + "이어야 합니다.");
        }
        var pageable = pageRequest.toSpringPageRequest(ADMIN_SORT_FIELDS, ADMIN_DEFAULT_SORT);
        String keyword = pageRequest.keyword() == null ? "" : pageRequest.keyword().trim();
        String targetCategory = category == null ? "" : category.trim();

        List<AdminBenefitResponse> filtered = benefitRepository.findAllForAdmin().stream()
                .filter(b-> keyword.isEmpty() ||
                        b.brand().contains(keyword) || b.name().contains(keyword))
                .filter(b -> targetCategory.isEmpty() || b.category().equals(targetCategory))
                .sorted(comparatorOf(pageable.getSort().iterator().next()))
                .toList();

        int from = Math.min((int) pageable.getOffset(), filtered.size());
        int to = Math.min(from + pageable.getPageSize(), filtered.size());
        int totalPages = (filtered.size() + pageable.getPageSize() - 1) / pageable.getPageSize();
        return new PageResponse<>(filtered.subList(from, to), filtered.size(), totalPages, pageable.getPageNumber());
    }

    private static Comparator<AdminBenefitResponse> comparatorOf(Sort.Order order){
        Comparator<AdminBenefitResponse> primary = switch(order.getProperty()){
            case "brand" -> byKey(AdminBenefitResponse::brand, order.isAscending());
            case "updatedAt" -> byKey(AdminBenefitResponse::updatedAt, order.isAscending());
            default -> byKey(AdminBenefitResponse::createdAt, order.isAscending());
        };
        return primary.thenComparing(AdminBenefitResponse::benefitId, Comparator.reverseOrder());
    }

    // 방향 상관없이 null은 맨 뒤
    private static <T extends Comparable<? super T>> Comparator<AdminBenefitResponse> byKey(
            Function<AdminBenefitResponse, T> key, boolean ascending){
        Comparator<T> direction = ascending ? Comparator.naturalOrder() : Comparator.reverseOrder();

        return Comparator.comparing(key, Comparator.nullsLast(direction));
    }

    @Transactional
    public AdminBenefitResponse create(BenefitRequest request){
        String brand = request.brand().trim();
        String category = validateCategory(request.category());
        if(benefitRepository.existsByBrand(brand)){
            throw new BusinessException(ErrorCode.BENEFIT_BRAND_ALREADY_EXISTS);
        }
        Benefit benefit = benefitRepository.save(Benefit.builder()
                .brand(brand)
                .name(request.name().trim())
                .category(category)
                .description(request.description())
                .build());
        return toAdminResponse(benefit, 0);
    }

    /** 브랜드명을 바꿔도 매장 연결은 id 기준이라 그대로 유지 */
    @Transactional
    public AdminBenefitResponse update(Long benefitId, BenefitRequest request){
        Benefit benefit = findBenefitOrThrow(benefitId);
        String brand = request.brand().trim();
        String category = validateCategory(request.category());
        if(benefitRepository.existsByBrandAndIdNot(brand, benefitId)){
            throw new BusinessException(ErrorCode.BENEFIT_BRAND_ALREADY_EXISTS);
        }
        benefit.update(brand, request.name().trim(), category, request.description());
        benefitRepository.flush();
        return toAdminResponse(benefit, storeBenefitRepository.countByBenefitId(benefitId));
    }

    /** 연결된 제휴 매장이 있으면 삭제하지 않음 */
    @Transactional
    public BenefitDeleteResponse delete(Long benefitId){
        Benefit benefit = findBenefitOrThrow(benefitId);
        long storeCount = storeBenefitRepository.countByBenefitId(benefitId);
        if(storeCount > 0){
            throw new BusinessException(ErrorCode.BENEFIT_IN_USE,
                    "연결된 제휴 매장이 " + storeCount + "개 있어 삭제할 수 없습니다.");
        }
        benefitRepository.delete(benefit);
        return new BenefitDeleteResponse(benefitId, true);
    }

    private Benefit findBenefitOrThrow(Long benefitId) {
        return benefitRepository.findById(benefitId).orElseThrow(
                () -> new BenefitNotFoundException(benefitId));
    }

    private static String validateCategory(String category) {
        String value = category.trim();
        if(!Benefit.CATEGORIES.contains(value)){
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "category는 " + String.join(", ", Benefit.CATEGORIES) + " 중 하나여야 합니다.");
        }
        return value;
    }

    private static AdminBenefitResponse toAdminResponse(Benefit benefit, long storeCount) {
        return new AdminBenefitResponse(benefit.getId(), benefit.getBrand(), benefit.getName(),
                benefit.getCategory(), benefit.getDescription(), storeCount, benefit.getCreatedAt(), benefit.getUpdatedAt());
    }
}
