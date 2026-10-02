package com.vita.store.service;

import com.vita.common.exception.BusinessException;
import com.vita.common.exception.ErrorCode;
import com.vita.common.page.PageRequest;
import com.vita.common.page.PageResponse;
import com.vita.store.dto.request.StoreCreateRequest;
import com.vita.store.dto.request.StoreUpdateRequest;
import com.vita.store.dto.response.*;
import com.vita.store.entity.Benefit;
import com.vita.store.entity.Store;
import com.vita.store.entity.StoreBenefit;
import com.vita.store.entity.StoreType;
import com.vita.store.exception.BenefitNotFoundException;
import com.vita.store.exception.StoreNotFoundException;
import com.vita.store.repository.BenefitRepository;
import com.vita.store.repository.StoreBenefitRepository;
import com.vita.store.repository.StoreDistanceProjection;
import com.vita.store.repository.StoreRepository;
import com.vita.store.repository.StoreSpecs;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StoreService {

    private static final Set<String> ADMIN_LIST_SORT_FIELDS = Set.of("createdAt", "updatedAt", "name");
    private static final String ADMIN_LIST_DEFAULT_SORT = "createdAt, desc";

    private final StoreRepository storeRepository;
    private final StoreBenefitRepository storeBenefitRepository;
    private final BenefitRepository benefitRepository;

    public StoreNearestResponse findNearest(BigDecimal lat, BigDecimal lng) {
        StoreDistanceProjection nearest = storeRepository.findNearest(lat, lng)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "등록된 매장이 없습니다."));
        return new StoreNearestResponse(nearest.getId(), nearest.getName(), nearest.getAddress(),
                nearest.getLat(), nearest.getLng(), round2(nearest.getDistanceKm()), nearest.getBusinessHours(),
                nearest.getPhone(), splitServices(nearest.getConsultServices()), splitServices(nearest.getProvidedServices()));
    }

    public StoreNearbyListResponse findNearby(BigDecimal lat, BigDecimal lng, double radiusKm){
        List<StoreNearbyItemResponse> stores = storeRepository.findNearBy(lat, lng, radiusKm).stream()
                .map(p -> new StoreNearbyItemResponse(p.getId(), p.getName(), p.getLat(),
                        p.getLng(), round2(p.getDistanceKm()), splitServices(p.getConsultServices()),
                        splitServices(p.getProvidedServices()))).toList();
        return new StoreNearbyListResponse(stores);
    }

    private static double round2(double value) {
        return Math.round(value * 100) / 100.0;
    }

    private static List<String> splitServices(String joined){
        return (joined == null || joined.isBlank()) ? List.of() :
                Arrays.asList(joined.split("\\|\\|"));
    }

    public PageResponse<StoreListItemResponse> search(PageRequest pageRequest, String storeType,
                                                      String category, Long benefitId){
        var pageable = pageRequest.toSpringPageRequest(ADMIN_LIST_SORT_FIELDS, ADMIN_LIST_DEFAULT_SORT);
        Specification<Store> spec = Specification.allOf(Stream.of(
                StoreSpecs.keyword(pageRequest.keyword()),
                StoreSpecs.storeType(StoreType.fromNullable(storeType)),
                StoreSpecs.category(category),
                StoreSpecs.benefitId(benefitId),
                StoreSpecs.orderBy(pageable.getSort())).filter(Objects::nonNull).toList());
        Page<Store> page = storeRepository.findAll(spec,
                org.springframework.data.domain.PageRequest.of(pageable.getPageNumber(), pageable.getPageSize()));

        Map<Long, Benefit> benefits = benefitsOf(page.getContent());
        return PageResponse.from(page, store -> {
            Benefit benefit = benefits.get(store.getId());
            return new StoreListItemResponse(store.getId(), store.getName(), store.getAddress(),
                    store.getStoreType().name(),
                    benefit != null ? benefit.getId() : null,
                    benefit != null ? benefit.getBrand() : null,
                    benefit != null ? benefit.getCategory() : null,
                    store.getCreatedAt(), store.getUpdatedAt());
        });
    }

    public StoreDetailResponse findById(Long storeId){
        Store store = findStoreOrThrow(storeId);
        Benefit benefit = benefitsOf(List.of(store)).get(store.getId());
        return new StoreDetailResponse(store.getId(), store.getName(), store.getAddress(),
                store.getLat(), store.getLng(), store.getBusinessHours(), store.getPhone(),
                store.getConsultServices(), store.getProvidedServices(), store.getStoreType().name(),
                benefit != null ? benefit.getId() : null,
                benefit != null ? benefit.getBrand() : null,
                benefit != null ? benefit.getCategory() : null,
                benefit != null ? benefit.getName() : null,
                store.getCreatedAt(), store.getUpdatedAt());
    }

    // 매장 id → 연결된 혜택. 페이지 단위로 한 번에 조회한다 (매장마다 조회하면 N+1)
    private Map<Long, Benefit> benefitsOf(List<Store> stores){
        if(stores.isEmpty()){
            return Map.of();
        }
        List<Long> storeIds = stores.stream().map(Store::getId).toList();
        Map<Long, Benefit> result = new HashMap<>();
        storeBenefitRepository.findWithBenefitByStoreIds(storeIds)
                .forEach(link -> result.putIfAbsent(link.getStore().getId(), link.getBenefit()));
        return result;
    }

    @Transactional
    public StoreCreateResponse create(StoreCreateRequest request){
        StoreType type = StoreType.fromNullable(request.storeType());
        Benefit benefit = resolveBenefit(type == null ? StoreType.PHONE : type, request.benefitId(), true);

        Store store = Store.builder()
                .name(request.name())
                .address(request.address())
                .lat(request.lat())
                .lng(request.lng())
                .businessHours(request.businessHours())
                .phone(request.phone())
                .consultServices(request.consultServices())
                .providedServices(request.providedServices())
                .storeType(type)
                .build();
        storeRepository.save(store);
        if(benefit != null){
            storeBenefitRepository.save(new StoreBenefit(store, benefit));
        }
        return new StoreCreateResponse(store.getId(), store.getName(), store.getCreatedAt());
    }

    @Transactional
    public StoreUpdateResponse update(Long storeId, StoreUpdateRequest request){
        Store store = findStoreOrThrow(storeId);
        Benefit benefit = resolveBenefit(store.getStoreType(), request.benefitId(), false);
        store.update(request.name(), request.address(), request.lat(), request.lng(),
                request.businessHours(), request.phone(), request.consultServices(), request.providedServices());
        storeRepository.flush();
        LocalDateTime updatedAt = store.getUpdatedAt();

        Benefit current = benefitsOf(List.of(store)).get(store.getId());
        if(benefit != null && (current == null || !current.getId().equals(benefit.getId()))){
            // 매장 1개 = 브랜드 1개라 기존 연결을 지우고 새로 연결한다
            storeBenefitRepository.deleteByStoreId(store.getId());
            storeBenefitRepository.save(new StoreBenefit(store, benefit));
            // 브랜드 변경도 매장 수정이므로 수정일을 직접 갱신한다 (DB 정밀도에 맞춰 마이크로초까지)
            updatedAt = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
            storeRepository.touchUpdatedAt(store.getId(), updatedAt);
        }
        return new StoreUpdateResponse(store.getId(), updatedAt);
    }

    /**
     * 제휴 매장은 어느 브랜드(혜택) 매장인지 있어야 업종 필터·혜택 API·챗봇 혜택 안내에 나온다.
     * 등록 시 제휴 매장은 benefitId 필수, 수정 시에는 보냈을 때만 바꾼다. 통신 매장은 혜택을 가질 수 없다.
     */
    private Benefit resolveBenefit(StoreType type, Long benefitId, boolean requiredForPartner){
        if(type == StoreType.PHONE){
            if(benefitId != null){
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "통신 매장에는 benefitId를 지정할 수 없습니다.");
            }
            return null;
        }
        if(benefitId == null){
            if(requiredForPartner){
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "제휴 매장은 benefitId(브랜드)가 필요합니다.");
            }
            return null;
        }
        return benefitRepository.findById(benefitId).orElseThrow(() -> new BenefitNotFoundException(benefitId));
    }

    @Transactional
    public StoreDeleteResponse delete(Long storeId){
        Store store = findStoreOrThrow(storeId);
        storeRepository.delete(store);
        return new StoreDeleteResponse(store.getId(), true);
    }

    private Store findStoreOrThrow(Long storeId) {
        return storeRepository.findById(storeId).orElseThrow(() -> new StoreNotFoundException(storeId));
    }
}