package co.edu.corposucre.productionfood.common.error;

import java.time.LocalDateTime;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import tools.jackson.core.JacksonException;

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

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ErrorResponse> apiException(
            ApiException ex, HttpServletRequest req) {
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

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ErrorResponse> accesoDenegado(
            AccessDeniedException ex, HttpServletRequest req) {
        return build(HttpStatus.FORBIDDEN, "SIN_PERMISO",
                     "No tiene permisos para realizar esta acción.",
                     req.getRequestURI(), List.of());
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class,
                       MissingServletRequestParameterException.class})
    ResponseEntity<ErrorResponse> parametroMalFormado(
            Exception ex, HttpServletRequest req) {
        var nombre = ex instanceof MethodArgumentTypeMismatchException tipo
                ? tipo.getName()
                : ((MissingServletRequestParameterException) ex).getParameterName();
        return build(HttpStatus.BAD_REQUEST, "PARAMETRO_INVALIDO",
                     "El parámetro '" + nombre + "' no es válido.",
                     req.getRequestURI(), List.of());
    }

    /**
     * Traduce un cuerpo ilegible a {@code VALIDACION_FALLIDA} e intenta señalar el campo culpable.
     *
     * <p>Un JSON truncado o sin estructura válida no permite identificar ningún campo y responde
     * con {@code fieldErrors} vacío. En cambio, un tipo inválido en un campo concreto (por ejemplo
     * {@code "cantidad":"abc"}) llega como {@link JacksonException} con la ruta del campo: sin ese
     * dato el cliente solo sabría que «algo» está mal, no qué corregir.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorResponse> cuerpoIlegible(
            HttpMessageNotReadableException ex, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, "VALIDACION_FALLIDA",
                     "Los datos enviados no son válidos.",
                     req.getRequestURI(), camposDelCuerpoIlegible(ex));
    }

    /**
     * Extrae el primer campo identificable de la ruta de Jackson, si la hay.
     *
     * <p>Se recorre la cadena de causas y no se usa la causa más específica: en un error de tipo
     * de un {@code BigDecimal} la excepción de Jackson es la hoja, pero en una fecha inválida
     * Jackson envuelve un {@code DateTimeParseException} y la hoja deja de ser
     * {@link JacksonException}. La ruta del campo solo vive en la excepción de Jackson, así que
     * hay que encontrarla en cualquier eslabón.
     */
    private static List<ErrorResponse.FieldError> camposDelCuerpoIlegible(
            HttpMessageNotReadableException ex) {
        Throwable causa = ex;
        while (causa != null && !(causa instanceof JacksonException)) {
            causa = causa.getCause();
        }
        if (!(causa instanceof JacksonException jackson)) {
            return List.of();
        }
        for (var referencia : jackson.getPath()) {
            var campo = referencia.getPropertyName();
            if (campo != null) {
                return List.of(new ErrorResponse.FieldError(
                        campo, "El valor del campo no es válido."));
            }
        }
        return List.of();
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ErrorResponse> metodoNoPermitido(
            HttpRequestMethodNotSupportedException ex, HttpServletRequest req) {
        return build(HttpStatus.METHOD_NOT_ALLOWED, "METODO_NO_PERMITIDO",
                     "El método '" + ex.getMethod()
                             + "' no está permitido para este recurso.",
                     req.getRequestURI(), List.of());
    }

    // Spring lanza NoResourceFoundException para toda URL sin controlador. Este handler
    // específico gana sobre el @ExceptionHandler(Exception.class) genérico (Spring elige el
    // más específico): sin él, cualquier ruta mal escrita o endpoint mal enrutado devuelve
    // 500 y ensucia los logs con errores que en realidad son 404.
    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ErrorResponse> rutaNoEncontrada(
            NoResourceFoundException ex, HttpServletRequest req) {
        log.debug("Ruta sin controlador: {}", req.getRequestURI());
        return build(HttpStatus.NOT_FOUND, "RECURSO_NO_ENCONTRADO",
                     "No existe el recurso solicitado.",
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
