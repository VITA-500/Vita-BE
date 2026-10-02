package com.vita.store.exception;

import com.vita.common.exception.BusinessException;
import com.vita.common.exception.ErrorCode;

public class BenefitNotFoundException extends BusinessException {
    public BenefitNotFoundException(Long benefitId) {
        super(ErrorCode.NOT_FOUND, "혜택을 찾을 수 없습니다. id=" + benefitId);
    }
}
