package com.vita.store.service;

import com.vita.common.exception.BusinessException;
import com.vita.common.exception.ErrorCode;
import com.vita.common.page.PageRequest;
import com.vita.common.page.PageResponse;
import com.vita.store.dto.request.StoreCreateRequest;
import com.vita.store.dto.request.StoreUpdateRequest;
import com.vita.store.dto.response.*;
import com.vita.store.entity.Store;
import com.vita.store.exception.StoreNotFoundException;
import com.vita.store.repository.StoreDistanceProjection;
import com.vita.store.repository.StoreRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StoreService {

    private static final Set<String> ADMIN_LIST_SORT_FIELDS = Set.of("createdAt", "name");
    private static final String ADMIN_LIST_DEFAULT_SORT = "createdAt, desc";

    private final StoreRepository storeRepository;

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

    public PageResponse<StoreListItemResponse> search(PageRequest pageRequest){
        var page = storeRepository.search(
                pageRequest.keyword() != null ? pageRequest.keyword() : "",
                pageRequest.toSpringPageRequest(ADMIN_LIST_SORT_FIELDS, ADMIN_LIST_DEFAULT_SORT));
        return PageResponse.from(page, store -> new StoreListItemResponse(store.getId(), store.getName(), store.getAddress()));
    }

    public StoreDetailResponse findById(Long storeId){
        Store store = findStoreOrThrow(storeId);
        return new StoreDetailResponse(store.getId(), store.getName(), store.getAddress(),
                store.getLat(), store.getLng(), store.getBusinessHours(), store.getPhone(), store.getConsultServices(), store.getProvidedServices());
    }

    @Transactional
    public StoreCreateResponse create(StoreCreateRequest request){
        Store store = Store.builder()
                .name(request.name())
                .address(request.address())
                .lat(request.lat())
                .lng(request.lng())
                .businessHours(request.businessHours())
                .phone(request.phone())
                .consultServices(request.consultServices())
                .providedServices(request.providedServices())
                .build();
        storeRepository.save(store);
        return new StoreCreateResponse(store.getId(), store.getName(), store.getCreatedAt());
    }

    @Transactional
    public StoreUpdateResponse update(Long storeId, StoreUpdateRequest request){
        Store store = findStoreOrThrow(storeId);
        store.update(request.name(), request.address(), request.lat(), request.lng(),
                request.businessHours(), request.phone(), request.consultServices(), request.providedServices());
        storeRepository.flush();
        return new StoreUpdateResponse(store.getId(), store.getUpdatedAt());
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
