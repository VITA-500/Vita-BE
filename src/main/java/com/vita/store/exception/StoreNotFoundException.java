package com.vita.store.exception;

import com.vita.common.exception.BusinessException;
import com.vita.common.exception.ErrorCode;

public class StoreNotFoundException extends BusinessException {
    public StoreNotFoundException(Long storeId) {
        super(ErrorCode.NOT_FOUND, "매장을 찾을 수 없습니다. id=" + storeId);
    }
}
