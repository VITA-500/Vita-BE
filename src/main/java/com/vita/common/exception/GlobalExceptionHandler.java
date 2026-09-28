package com.vita.common.exception;

import com.vita.common.response.ErrorResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** 전역 예외 처리는 이 클래스 하나로 통일 (08_개발표준 2절). */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

	@ExceptionHandler(BusinessException.class)
	public ResponseEntity<ErrorResponse> handleBusinessException(BusinessException e) {
		log.warn("BusinessException: {}", e.getErrorCode(), e);
		return ResponseEntity.status(e.getErrorCode().getHttpStatus()).body(ErrorResponse.of(e.getErrorCode(), e.getMessage()));
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ErrorResponse> handleValidationException(MethodArgumentNotValidException e) {
		var fieldErrors = e.getBindingResult().getFieldErrors().stream()
				.map(fieldError -> new ErrorResponse.FieldError(fieldError.getField(), fieldError.getDefaultMessage()))
				.toList();
		return ResponseEntity
				.status(ErrorCode.VALIDATION_ERROR.getHttpStatus())
				.body(ErrorResponse.ofValidation(fieldErrors));
	}

	/** 매칭되는 라우트가 없을 때 Spring이 던지는 예외 (Spring 6.1+) — 그대로 두면 아래 catch-all에 잡혀 500으로 나감. */
	@ExceptionHandler(NoResourceFoundException.class)
	public ResponseEntity<ErrorResponse> handleNoResourceFoundException(NoResourceFoundException e) {
		return ResponseEntity.status(ErrorCode.NOT_FOUND.getHttpStatus()).body(ErrorResponse.of(ErrorCode.NOT_FOUND));
	}

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ErrorResponse> handleInvalidRequest(Exception e) {
        return ResponseEntity.badRequest().body(ErrorResponse.of(ErrorCode.VALIDATION_ERROR));
    }

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ErrorResponse> handleUnexpectedException(Exception e) {
		log.error("Unhandled exception", e);
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ErrorResponse.of(ErrorCode.INTERNAL_ERROR));
	}
}
