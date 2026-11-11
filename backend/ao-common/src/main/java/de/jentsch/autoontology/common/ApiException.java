// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.common;

import org.springframework.http.HttpStatus;

/**
 * Error with an HTTP status. {@link Style#DETAIL} renders {@code {"detail": ...}} (routes the
 * original served from FastAPI), {@link Style#ERROR} renders {@code {"error": ...}} (routes the
 * original served from Next.js), {@link Style#PLAIN} plain text (FR-API-02).
 */
public class ApiException extends RuntimeException {
    /** Rendering of the error body. */
    public enum Style {
        DETAIL,
        ERROR,
        PLAIN
    }

    private final HttpStatus status;
    private final Style style;
    private final transient Object detail;

    public ApiException(HttpStatus status, String message) {
        this(status, message, Style.DETAIL, message);
    }

    public ApiException(HttpStatus status, String message, Style style, Object detail) {
        super(message);
        this.status = status;
        this.style = style;
        this.detail = detail;
    }

    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, message);
    }

    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }

    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, message);
    }

    public static ApiException unprocessable(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, message);
    }

    public static ApiException badGateway(String message) {
        return new ApiException(HttpStatus.BAD_GATEWAY, message);
    }

    /** Next.js-style {@code {"error": message}}. */
    public static ApiException error(HttpStatus status, String message) {
        return new ApiException(status, message, Style.ERROR, message);
    }

    /** Plain-text body such as {@code Conversation not found}. */
    public static ApiException plain(HttpStatus status, String message) {
        return new ApiException(status, message, Style.PLAIN, message);
    }

    public HttpStatus status() {
        return status;
    }

    public Style style() {
        return style;
    }

    public Object detail() {
        return detail;
    }
}
