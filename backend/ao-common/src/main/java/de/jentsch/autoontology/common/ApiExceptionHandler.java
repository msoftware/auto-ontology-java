// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.common;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** FR-API-02 error formats, limited to the Auto-Ontology controllers. */
@RestControllerAdvice(basePackages = "de.jentsch.autoontology")
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Object> api(ApiException e) {
        return switch (e.style()) {
            case DETAIL -> json(e.status(), body("detail", e.detail()));
            case ERROR -> json(e.status(), body("error", e.detail()));
            case PLAIN -> ResponseEntity.status(e.status())
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(String.valueOf(e.detail()));
        };
    }

    /** FastAPI-like 422 with a list of validation errors. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Object> invalid(MethodArgumentNotValidException e) {
        List<Map<String, Object>> errors = new ArrayList<>();
        e.getBindingResult().getFieldErrors().forEach(fe -> errors.add(Map.of(
                "loc", List.of("body", fe.getField()),
                "msg", String.valueOf(fe.getDefaultMessage()),
                "type", "value_error")));
        return json(HttpStatus.UNPROCESSABLE_CONTENT, Map.of("detail", errors));
    }

    @ExceptionHandler({
        HttpMessageNotReadableException.class,
        MissingServletRequestParameterException.class,
        MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<Object> unreadable(Exception e) {
        return json(HttpStatus.UNPROCESSABLE_CONTENT, Map.of("detail", List.of(Map.of(
                "loc", List.of("body"), "msg", String.valueOf(e.getMessage()), "type", "value_error"))));
    }

    /** A detail of {@code null} (exception without message) must not turn the error into a 500. */
    private static Map<String, Object> body(String key, Object detail) {
        return java.util.Collections.singletonMap(key, detail == null ? "" : detail);
    }

    private static ResponseEntity<Object> json(HttpStatus status, Object body) {
        if (status.is5xxServerError()) {
            log.warn("API error {}: {}", status.value(), body);
        }
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
    }
}
