package com.vita.store.service;

import com.vita.common.exception.BusinessException;
import com.vita.common.exception.ErrorCode;
import com.vita.common.page.PageRequest;
import com.vita.common.page.PageResponse;
import com.vita.store.dto.request.StoreCreateRequest;
import com.vita.store.dto.request.StoreUpdateRequest;
import com.vita.store.dto.response.*;
import com.vita.store.entity.*;
import com.vita.store.exception.BenefitNotFoundException;
import com.vita.store.exception.StoreNotFoundException;
import com.vita.store.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
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
    private static final int PARTNER_NEARBY_LIMIT = 100;
    private static final int CHAT_DEFAULT_LIMIT = 4;
    private static final int CHAT_MAX_LIMIT = 20;

    private final StoreRepository storeRepository;
    private final StoreBenefitRepository storeBenefitRepository;
    private final BenefitRepository benefitRepository;
    private final StoreReservationRepository storeReservationRepository;

    public StoreNearestResponse findNearest(BigDecimal lat, BigDecimal lng) {
        StoreDistanceProjection nearest = storeRepository.findNearest(lat, lng)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "등록된 매장이 없습니다."));
        return new StoreNearestResponse(nearest.getId(), nearest.getName(), nearest.getAddress(),
                nearest.getLat(), nearest.getLng(), round2(nearest.getDistanceKm()), nearest.getBusinessHours(),
                BusinessHours.openAt(nearest.getBusinessHours(), BusinessHours.nowInKorea()),
                nearest.getPhone(), splitServices(nearest.getConsultServices()), splitServices(nearest.getProvidedServices()));
    }

    /**
     * 지도 주변 매장. storeType 생략 시 통신 매장만 돌려줌
     * PARTNER일 경우 제휴 매장을 혜택 정보와 돌려줌
     * 업종 및 브랜드로 필터 가능
     * 제휴 매장 반경이 클 때를 대비해 개수 상한 100개
     * openNow=true면 지금 영업 중인 매장만
     * openAt("HH:mm")이면 그 시각에 영업 중인 매장만
     */
    public StoreNearbyListResponse findNearby(BigDecimal lat, BigDecimal lng, double radiusKm, String storeType,
                                              String category, Long benefitId, Boolean openNow, String openAt) {
        StoreType type = StoreType.fromNullable(storeType);
        LocalTime filterTime = BusinessHours.filterTime(openNow, openAt);
        LocalTime now = BusinessHours.nowInKorea();
        String targetCategory = (category == null || category.isBlank()) ? null : category.trim();

        if (type != StoreType.PARTNER) {
            if (targetCategory != null || benefitId != null) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "업종(category) 및 브랜드(benefitId) 필터는 storeType=PARTNER에서만 사용할 수 있습니다.");
            }
            List<StoreNearbyItemResponse> stores = storeRepository.findNearBy(lat, lng, radiusKm, filterTime).stream()
                .map(p -> new StoreNearbyItemResponse(p.getId(), p.getName(), StoreType.PHONE.name(), p.getAddress(),
                        p.getPhone(), p.getLat(), p.getLng(), round2(p.getDistanceKm()), p.getBusinessHours(), BusinessHours.openAt(p.getBusinessHours(), now),
                        splitServices(p.getConsultServices()), splitServices(p.getProvidedServices()),
                        null, null, null, null, null))
                    .toList();
        return new StoreNearbyListResponse(stores);
        }

        List<StoreNearbyItemResponse> stores = storeRepository.findPartnersNearby(
                lat, lng, targetCategory, benefitId, radiusKm, filterTime, PARTNER_NEARBY_LIMIT).stream()
                .map(p -> new StoreNearbyItemResponse(p.getId(), p.getName(), StoreType.PARTNER.name(),
                        p.getAddress(), p.getPhone(), p.getLat(), p.getLng(), round2(p.getDistanceKm()), p.getBusinessHours(),
                        BusinessHours.openAt(p.getBusinessHours(), now), List.of(), List.of(), p.getBenefitId(), p.getBrand(),
                        p.getCategory(), p.getBenefitName(), p.getBenefitDescription()))
                .toList();
        return new StoreNearbyListResponse(stores);
    }

    /**
     * 채팅용 주변 통신 매장
     * services를 주면 그 서비스를 많이 제공하는 매장 먼저, 같으면 가까운 순
     * limit 생략 시 4개, 최대 20개
     */
    public List<StoreChatItemResponse> findNearbyForChat(BigDecimal lat, BigDecimal lng, double radiusKm, Integer limit, List<String> services){
        return findNearbyForChat(lat, lng, radiusKm, limit, services, null);
    }

    /** openAt을 주면 그 시각에 영업 중인 매장만 (지금 영업 중: BusinessHours.nowInKorea()) */
    public List<StoreChatItemResponse> findNearbyForChat(BigDecimal lat, BigDecimal lng, double radiusKm, Integer limit,
                                                         List<String> services, LocalTime openAt){
        if(lat == null || lng == null){
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "위치(lat, lng)는 필수입니다.");
        }
        if(radiusKm <= 0){
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "반경(radiusKm)은 0보다 커야 합니다.");
        }
        int size = limit == null ? CHAT_DEFAULT_LIMIT : limit;
        if(size < 1 || size > CHAT_MAX_LIMIT){
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "limit은 1~" + CHAT_MAX_LIMIT + " 사이여야 합니다.");
        }
        String joinedServices = services == null ? null : services.stream()
                .filter(Objects::nonNull).map(String::trim).filter(s -> !s.isEmpty())
                .distinct().reduce((a, b) -> a + "||" + b).orElse(null);

        LocalTime now = BusinessHours.nowInKorea();
        return storeRepository.findNearbyForChat(lat, lng, radiusKm, joinedServices, openAt, size).stream()
                .map(p -> new StoreChatItemResponse(p.getId(), p.getName(), p.getAddress(),
                        p.getPhone(), p.getLat(), p.getLng(), round2(p.getDistanceKm()), p.getBusinessHours(),
                        BusinessHours.openAt(p.getBusinessHours(), now),
                        splitServices(p.getConsultServices()), splitServices(p.getProvidedServices()))).toList();
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
                store.getLat(), store.getLng(), store.getBusinessHours(),
                BusinessHours.openAt(store.getBusinessHours(), BusinessHours.nowInKorea()), store.getPhone(),
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
                .businessHours(BusinessHours.parse(request.businessHours()))
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
                BusinessHours.parse(request.businessHours()), request.phone(),
                request.consultServices(), request.providedServices());
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
        // 예약은 목업 데이터이기 때문에 매장과 함께 지움
        storeReservationRepository.deleteByStoreId(storeId);
        storeRepository.delete(store);
        return new StoreDeleteResponse(store.getId(), true);
    }

    private Store findStoreOrThrow(Long storeId) {
        return storeRepository.findById(storeId).orElseThrow(() -> new StoreNotFoundException(storeId));
    }
}