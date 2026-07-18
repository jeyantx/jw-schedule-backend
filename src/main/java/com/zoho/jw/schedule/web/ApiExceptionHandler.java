package com.zoho.jw.schedule.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Renders {@link ApiException} (and anything unexpected) as a small JSON error body. */
@RestControllerAdvice
public class ApiExceptionHandler
{
    private static final Logger LOGGER = Logger.getLogger(ApiExceptionHandler.class.getName());

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handle(ApiException ex)
    {
        return ResponseEntity.status(ex.status())
                .body(Map.of("error", ex.getMessage(), "status", ex.status().value()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handle(Exception ex)
    {
        LOGGER.log(Level.SEVERE, "Unhandled error", ex);
        return ResponseEntity.status(500)
                .body(Map.of("error", ex.getClass().getSimpleName() + ": " + ex.getMessage(), "status", 500));
    }
}
