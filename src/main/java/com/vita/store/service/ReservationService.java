package com.vita.store.service;

import com.vita.common.exception.BusinessException;
import com.vita.common.exception.ErrorCode;
import com.vita.store.dto.request.ReservationRequest;
import com.vita.store.dto.response.ReservationResponse;
import com.vita.store.entity.Store;
import com.vita.store.entity.StoreReservation;
import com.vita.store.entity.StoreType;
import com.vita.store.exception.StoreNotFoundException;
import com.vita.store.repository.StoreRepository;
import com.vita.store.repository.StoreReservationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;

@Service
@RequiredArgsConstructor
public class ReservationService {

    private final StoreRepository storeRepository;
    private final StoreReservationRepository reservationRepository;

    @Transactional
    public ReservationResponse reserve(Long storeId, Long userId, ReservationRequest request){
        /** 비회원도 로그인 필요 규칙 통과
         * 회원인지 한 번 더 확인
         */
        if(userId == null){
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "예약은 로그인 후 이용할 수 있습니다.");
        }
        Store store = storeRepository.findById(storeId).orElseThrow(() ->
                new StoreNotFoundException(storeId));
        // 예약은 통신 매장만
        if(store.getStoreType() != StoreType.PHONE){
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "제휴 매장은 예약 할 수 없습니다.");
        }
        if(!LocalDateTime.of(request.date(), request.time()).isAfter(LocalDateTime.now(ZoneId.of("Asia/Seoul")))){
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "지난 시간으로는 예약할 수 없습니다.");
        }

        StoreReservation reservation = reservationRepository.save(
                StoreReservation.confirmed(store, userId, request.date(), request.time()));
        return new ReservationResponse(reservation.getId(), store.getId(), store.getName(),
                reservation.getReservationDate(), reservation.getReservationTime(), reservation.getStatus().name());
    }
}
