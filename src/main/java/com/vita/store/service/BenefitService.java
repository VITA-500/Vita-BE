package com.vita.store.service;

import com.vita.common.exception.BusinessException;
import com.vita.common.exception.ErrorCode;
import com.vita.store.dto.response.BenefitListResponse;
import com.vita.store.dto.response.BenefitResponse;
import com.vita.store.dto.response.BenefitStoreListResponse;
import com.vita.store.repository.BenefitRepository;
import com.vita.store.repository.StoreRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BenefitService {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private final BenefitRepository benefitRepository;
    private final StoreRepository storeRepository;

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
}
