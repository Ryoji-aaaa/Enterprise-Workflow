package jp.co.sdcj.workflow.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> handleApiException(ApiException exception) {
        return ResponseEntity
                .status(exception.getStatus())
                .body(new ApiError(exception.getCode(), exception.getMessage()));
    }
}
