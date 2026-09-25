package co.edu.corposucre.productionfood.security;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Peticiones sin token, con token malformado o expirado responden
 * 401 NO_AUTENTICADO (04-CONTRATO-API §6, SEC-01..SEC-03).
 */
@Component
public class EntryPointNoAutenticado implements AuthenticationEntryPoint {

    @Override
    public void commence(HttpServletRequest req, HttpServletResponse res,
                         AuthenticationException ex) throws IOException {
        RespuestaSeguridad.escribir(res, req, HttpStatus.UNAUTHORIZED,
                "NO_AUTENTICADO", "Su sesión ha expirado. Inicie sesión nuevamente.");
    }
}
