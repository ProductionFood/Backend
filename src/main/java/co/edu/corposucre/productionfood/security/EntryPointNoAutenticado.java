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
 *
 * El code es siempre el mismo; el mensaje distingue la causa. JwtAuthenticationFilter
 * deja el texto en {@link MensajesSeguridad#ATRIBUTO_MENSAJE_401}; sin atributo no
 * hubo token, así que se usa {@link MensajesSeguridad#MSG_SIN_TOKEN}.
 */
@Component
public class EntryPointNoAutenticado implements AuthenticationEntryPoint {

    @Override
    public void commence(HttpServletRequest req, HttpServletResponse res,
                         AuthenticationException ex) throws IOException {
        Object causa = req.getAttribute(MensajesSeguridad.ATRIBUTO_MENSAJE_401);
        String mensaje = causa instanceof String m ? m : MensajesSeguridad.MSG_SIN_TOKEN;
        RespuestaSeguridad.escribir(res, req, HttpStatus.UNAUTHORIZED,
                "NO_AUTENTICADO", mensaje);
    }
}
