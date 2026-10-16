package com.photospace.shared.web;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.TypeMismatchException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns exceptions into RFC 9457 problem responses. Every body carries the correlation ID; none carries
 * exception messages or stack traces.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<Object> notFound(NotFoundException e, WebRequest request) {
        return problem(e, HttpStatus.NOT_FOUND, request);
    }

    @ExceptionHandler(ForbiddenException.class)
    ResponseEntity<Object> forbidden(ForbiddenException e, WebRequest request) {
        return problem(e, HttpStatus.FORBIDDEN, request);
    }

    @ExceptionHandler(ConflictException.class)
    ResponseEntity<Object> conflict(ConflictException e, WebRequest request) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        return handleExceptionInternal(e, body, new HttpHeaders(), HttpStatus.CONFLICT, request);
    }

    @ExceptionHandler(InvalidFieldsException.class)
    ResponseEntity<Object> invalidFields(InvalidFieldsException e, WebRequest request) {
        return validationProblem(e, e.errors(), new HttpHeaders(), HttpStatus.BAD_REQUEST, request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> unexpected(Exception e, WebRequest request) {
        log.error("Unhandled exception", e);
        return problem(e, HttpStatus.INTERNAL_SERVER_ERROR, request);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException e, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError error : e.getBindingResult().getFieldErrors()) {
            errors.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        return validationProblem(e, errors, headers, status, request);
    }

    /** Constraint violations on query parameters, such as a page size over the maximum. */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException e, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        e.getParameterValidationResults().forEach(result -> result.getResolvableErrors().forEach(error ->
                errors.putIfAbsent(result.getMethodParameter().getParameterName(), error.getDefaultMessage())));
        return validationProblem(e, errors, headers, HttpStatus.BAD_REQUEST, request);
    }

    /**
     * A malformed ID in the path names no resource, so it gets the same 404 as an unknown or foreign one. A
     * malformed query parameter is a 400 validation error.
     */
    @Override
    protected ResponseEntity<Object> handleTypeMismatch(
            TypeMismatchException e, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (e instanceof MethodArgumentTypeMismatchException mismatch) {
            if (mismatch.getParameter().hasParameterAnnotation(PathVariable.class)) {
                return problem(e, HttpStatus.NOT_FOUND, request);
            }
            return validationProblem(e, Map.of(mismatch.getName(), "has an invalid value"), headers, status, request);
        }
        return super.handleTypeMismatch(e, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> createResponseEntity(
            Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (body instanceof ProblemDetail problem) {
            problem.setProperty("correlationId", CorrelationIdFilter.current());
        }
        return super.createResponseEntity(body, headers, status, request);
    }

    private ResponseEntity<Object> validationProblem(
            Exception e, Map<String, String> errors, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, "Request validation failed");
        body.setProperty("errors", errors);
        return handleExceptionInternal(e, body, headers, status, request);
    }

    private ResponseEntity<Object> problem(Exception e, HttpStatus status, WebRequest request) {
        return handleExceptionInternal(e, ProblemDetail.forStatus(status), new HttpHeaders(), status, request);
    }
}
