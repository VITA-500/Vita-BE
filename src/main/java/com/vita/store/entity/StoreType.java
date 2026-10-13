package com.vita.store.entity;

import com.vita.common.exception.BusinessException;
import com.vita.common.exception.ErrorCode;

/** 매장 유형 */
public enum StoreType {
    PHONE,      // 통신 매장
    PARTNER;    // 제휴 매장 (혜택 브랜드 매장)

    /** 요청 값 변환. 비어 있으면 null, 대소문자 무시, 잘못된 값은 400 */
    public static StoreType fromNullable(String value){
        if(value == null || value.isBlank()){
            return null;
        }
        try{
            return StoreType.valueOf(value.trim().toUpperCase());
        }catch(IllegalArgumentException exception){
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "storeType은 PHONE 또는 PARTNER여야 합니다.");
        }
    }
}
