package com.orbitstorage.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ProblemDetail;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;

@RestControllerAdvice
public class Errors {
    private static final Logger LOG = LoggerFactory.getLogger(Errors.class);
    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> api(ApiException ex) {
        var builder = ResponseEntity.status(ex.status);
        if (ex.retryAfter > 0) builder.header("Retry-After", Long.toString(ex.retryAfter));
        return builder.body(ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(ex.status), ex.getMessage()));
    }
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
        IllegalArgumentException.class, org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    ResponseEntity<ProblemDetail> invalid(Exception ex) {
        return ResponseEntity.badRequest().body(ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(400), "Invalid request parameters"));
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unknown(Exception ex) {
        if (ex instanceof org.springframework.web.ErrorResponse error) {
            return ResponseEntity.status(error.getStatusCode()).headers(error.getHeaders())
                .body(ProblemDetail.forStatusAndDetail(error.getStatusCode(), "Request could not be processed"));
        }
        // Exception text from provider SDKs can contain signed URLs or credentials.
        LOG.error("Request failed: {}", ex.getClass().getSimpleName());
        return ResponseEntity.internalServerError().body(ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(500), "Request could not be completed"));
    }
}
