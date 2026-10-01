package com.vita.store.entity;

import com.vita.common.exception.BusinessException;
import com.vita.common.exception.ErrorCode;

public enum StoreType {
    PHONE,
    PARTNER;

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
