package co.edu.corposucre.productionfood.security;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Denegaciones de autorización fuera de MVC responden 403 SIN_PERMISO
 * (04-CONTRATO-API §6, SEC-04).
 */
@Component
public class AccesoDenegadoHandler implements AccessDeniedHandler {

    @Override
    public void handle(HttpServletRequest req, HttpServletResponse res,
                       AccessDeniedException ex) throws IOException {
        RespuestaSeguridad.escribir(res, req, HttpStatus.FORBIDDEN,
                "SIN_PERMISO", "No tiene permisos para realizar esta acción.");
    }
}
