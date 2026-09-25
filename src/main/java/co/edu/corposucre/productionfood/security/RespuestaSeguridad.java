package co.edu.corposucre.productionfood.security;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import com.fasterxml.jackson.databind.ObjectMapper;

import co.edu.corposucre.productionfood.common.error.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Escribe la respuesta de error de seguridad con el contrato ErrorResponse
 * (04-CONTRATO-API §6): status HTTP, code SCREAMING_SNAKE y message legible.
 */
final class RespuestaSeguridad {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RespuestaSeguridad() {
    }

    static void escribir(HttpServletResponse res, HttpServletRequest req,
                         HttpStatus status, String code, String message) throws IOException {
        res.setStatus(status.value());
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding("UTF-8");
        MAPPER.writeValue(res.getOutputStream(), new ErrorResponse(
                LocalDateTime.now().toString(),
                status.value(),
                status.getReasonPhrase(),
                code,
                message,
                req.getRequestURI(),
                List.of()));
    }
}
