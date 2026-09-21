package com.vita.store.exception;

import com.vita.common.exception.BusinessException;
import com.vita.common.exception.ErrorCode;

public class RouteNotFoundException extends BusinessException {
    public RouteNotFoundException(String mode) {
        super(ErrorCode.NOT_FOUND, "%s 경로를 찾을 수 없습니다.".formatted(mode));
    }
}
