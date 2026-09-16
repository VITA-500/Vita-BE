package com.vita.store.service;

import com.vita.common.exception.BusinessException;
import com.vita.common.exception.ErrorCode;
import com.vita.store.dto.request.StoreCreateRequest;
import com.vita.store.dto.request.StoreUpdateRequest;
import com.vita.store.dto.response.StoreCreateResponse;
import com.vita.store.dto.response.StoreDeleteResponse;
import com.vita.store.dto.response.StoreNearestResponse;
import com.vita.store.dto.response.StoreUpdateResponse;
import com.vita.store.entity.Store;
import com.vita.store.exception.StoreNotFoundException;
import com.vita.store.repository.StoreDistanceProjection;
import com.vita.store.repository.StoreRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StoreService {

    private final StoreRepository storeRepository;

    public StoreNearestResponse findNearest(BigDecimal lat, BigDecimal lng) {
        StoreDistanceProjection nearest = storeRepository.findNearest(lat, lng)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "등록된 매장이 없습니다."));
        return new StoreNearestResponse(nearest.getId(), nearest.getName(), nearest.getAddress(),
                nearest.getLat(), nearest.getLng(), round2(nearest.getDistanceKm()), nearest.getBusinessHours(), nearest.getPhone());
    }

    private static double round2(double value) {
        return Math.round(value * 100) / 100.0;
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
                .build();
        storeRepository.save(store);
        return new StoreCreateResponse(store.getId(), store.getName(), store.getCreatedAt());
    }

    @Transactional
    public StoreUpdateResponse update(Long storeId, StoreUpdateRequest request){
        Store store = findStoreOrThrow(storeId);
        store.update(request.name(), request.address(), request.lat(), request.lng(),
                request.businessHours(), request.phone());
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
