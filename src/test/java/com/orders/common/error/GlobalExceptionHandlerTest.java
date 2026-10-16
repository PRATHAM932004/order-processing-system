package com.orders.common.error;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.lang.reflect.Method;
import java.util.List;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler exceptionHandler;
    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        exceptionHandler = new GlobalExceptionHandler();
        request = new MockHttpServletRequest();
        request.setRequestURI("/api/test");
    }

    @Test
    @DisplayName("handleForbidden returns FORBIDDEN code and 403 details")
    void handleForbidden() {
        AccessDeniedException ex = new AccessDeniedException("Access is denied");
        ErrorResponse response = exceptionHandler.handleForbidden(ex, request);

        assertThat(response.code()).isEqualTo("FORBIDDEN");
        assertThat(response.message()).isEqualTo("Access is denied");
        assertThat(response.error()).isEqualTo("Access is denied");
        assertThat(response.path()).isEqualTo("/api/test");
        assertThat(response.timestamp()).isNotNull();
    }

    @Test
    @DisplayName("handleNotFound returns NOT_FOUND code and 404 details")
    void handleNotFound() {
        NoSuchElementException ex = new NoSuchElementException("Entity not found");
        ErrorResponse response = exceptionHandler.handleNotFound(ex, request);

        assertThat(response.code()).isEqualTo("NOT_FOUND");
        assertThat(response.message()).isEqualTo("Entity not found");
        assertThat(response.error()).isEqualTo("Entity not found");
        assertThat(response.path()).isEqualTo("/api/test");
        assertThat(response.timestamp()).isNotNull();
    }

    @Test
    @DisplayName("handleConflict returns CONFLICT code and 409 details")
    void handleConflict() {
        IllegalStateException ex = new IllegalStateException("State conflict occurred");
        ErrorResponse response = exceptionHandler.handleConflict(ex, request);

        assertThat(response.code()).isEqualTo("CONFLICT");
        assertThat(response.message()).isEqualTo("State conflict occurred");
        assertThat(response.error()).isEqualTo("State conflict occurred");
        assertThat(response.path()).isEqualTo("/api/test");
        assertThat(response.timestamp()).isNotNull();
    }

    @Test
    @DisplayName("handleBadRequest returns BAD_REQUEST code and 400 details")
    void handleBadRequest() {
        IllegalArgumentException ex = new IllegalArgumentException("Invalid argument provided");
        ErrorResponse response = exceptionHandler.handleBadRequest(ex, request);

        assertThat(response.code()).isEqualTo("BAD_REQUEST");
        assertThat(response.message()).isEqualTo("Invalid argument provided");
        assertThat(response.error()).isEqualTo("Invalid argument provided");
        assertThat(response.path()).isEqualTo("/api/test");
        assertThat(response.timestamp()).isNotNull();
    }

    @Test
    @DisplayName("handleUnauthorized returns UNAUTHORIZED code and 401 details")
    void handleUnauthorized() {
        BadCredentialsException ex = new BadCredentialsException("Bad credentials");
        ErrorResponse response = exceptionHandler.handleUnauthorized(ex, request);

        assertThat(response.code()).isEqualTo("UNAUTHORIZED");
        assertThat(response.message()).isEqualTo("Bad credentials");
        assertThat(response.error()).isEqualTo("Bad credentials");
        assertThat(response.path()).isEqualTo("/api/test");
        assertThat(response.timestamp()).isNotNull();
    }

    @Test
    @DisplayName("handleValidationException returns VALIDATION_ERROR code and joined field error messages")
    void handleValidationException() throws Exception {
        Method method = this.getClass().getDeclaredMethod("setUp");
        MethodParameter parameter = new MethodParameter(method, -1);
        BindingResult bindingResult = mock(BindingResult.class);

        FieldError fieldError1 = new FieldError("object", "email", "must not be blank");
        FieldError fieldError2 = new FieldError("object", "name", "must not be null");
        when(bindingResult.getFieldErrors()).thenReturn(List.of(fieldError1, fieldError2));

        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(parameter, bindingResult);
        ErrorResponse response = exceptionHandler.handleValidationException(ex, request);

        assertThat(response.code()).isEqualTo("VALIDATION_ERROR");
        assertThat(response.message()).isEqualTo("email: must not be blank, name: must not be null");
        assertThat(response.error()).isEqualTo("email: must not be blank, name: must not be null");
        assertThat(response.path()).isEqualTo("/api/test");
        assertThat(response.timestamp()).isNotNull();
    }

    @Test
    @DisplayName("handleGenericException returns INTERNAL_SERVER_ERROR code and 500 details")
    void handleGenericException() {
        RuntimeException ex = new RuntimeException("Unexpected database failure");
        ErrorResponse response = exceptionHandler.handleGenericException(ex, request);

        assertThat(response.code()).isEqualTo("INTERNAL_SERVER_ERROR");
        assertThat(response.message()).isEqualTo("Unexpected database failure");
        assertThat(response.error()).isEqualTo("Unexpected database failure");
        assertThat(response.path()).isEqualTo("/api/test");
        assertThat(response.timestamp()).isNotNull();
    }

    @Test
    @DisplayName("handleGenericException with null message falls back to default message")
    void handleGenericException_nullMessage() {
        RuntimeException ex = new RuntimeException();
        ErrorResponse response = exceptionHandler.handleGenericException(ex, request);

        assertThat(response.code()).isEqualTo("INTERNAL_SERVER_ERROR");
        assertThat(response.message()).isEqualTo("An unexpected error occurred");
        assertThat(response.error()).isEqualTo("An unexpected error occurred");
        assertThat(response.path()).isEqualTo("/api/test");
        assertThat(response.timestamp()).isNotNull();
    }
}
