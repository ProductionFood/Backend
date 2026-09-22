package co.edu.corposucre.productionfood.common.error;

import java.time.LocalDateTime;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> validacion(
            MethodArgumentNotValidException ex, HttpServletRequest req) {
        var campos = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> new ErrorResponse.FieldError(f.getField(), f.getDefaultMessage()))
                .toList();
        return build(HttpStatus.BAD_REQUEST, "VALIDACION_FALLIDA",
                     "Los datos enviados no son válidos.", req.getRequestURI(), campos);
    }

    @ExceptionHandler(RecursoNoEncontradoException.class)
    ResponseEntity<ErrorResponse> noEncontrado(
            RecursoNoEncontradoException ex, HttpServletRequest req) {
        return build(ex.getStatus(), ex.getCodigo(), ex.getMessage(),
                     req.getRequestURI(), List.of());
    }

    @ExceptionHandler(ConflictoNegocioException.class)
    ResponseEntity<ErrorResponse> conflicto(
            ConflictoNegocioException ex, HttpServletRequest req) {
        return build(ex.getStatus(), ex.getCodigo(), ex.getMessage(),
                     req.getRequestURI(), List.of());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ErrorResponse> integridad(
            DataIntegrityViolationException ex, HttpServletRequest req) {
        log.warn("Violación de integridad en {}", req.getRequestURI(), ex);
        return build(HttpStatus.CONFLICT, "CONFLICTO_INTEGRIDAD",
                     "La operación entra en conflicto con datos existentes.",
                     req.getRequestURI(), List.of());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> general(Exception ex, HttpServletRequest req) {
        log.error("Error no controlado en {}", req.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "ERROR_INTERNO",
                     "Ocurrió un error inesperado. Intente nuevamente.",
                     req.getRequestURI(), List.of());
    }

    private ResponseEntity<ErrorResponse> build(
            HttpStatus status, String code, String message,
            String path, List<ErrorResponse.FieldError> fields) {
        var body = new ErrorResponse(
            LocalDateTime.now().toString(),
            status.value(),
            status.getReasonPhrase(),
            code,
            message,
            path,
            fields);
        return ResponseEntity.status(status).body(body);
    }
}
