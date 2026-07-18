package com.zoho.jw.schedule.web;

import org.springframework.http.HttpStatus;

/** A controlled error with an HTTP status, rendered as JSON by {@link ApiExceptionHandler}. */
public class ApiException extends RuntimeException
{
    private final HttpStatus status;

    public ApiException(HttpStatus status, String message)
    {
        super(message);
        this.status = status;
    }

    public HttpStatus status() { return status; }

    public static ApiException unauthorized(String m) { return new ApiException(HttpStatus.UNAUTHORIZED, m); }
    public static ApiException forbidden(String m)    { return new ApiException(HttpStatus.FORBIDDEN, m); }
    public static ApiException notFound(String m)     { return new ApiException(HttpStatus.NOT_FOUND, m); }
    public static ApiException badRequest(String m)   { return new ApiException(HttpStatus.BAD_REQUEST, m); }
}
