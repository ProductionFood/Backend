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
 * El mensaje es la constante compartida {@link MensajesSeguridad#MSG_NO_AUTENTICADO}:
 * este camino y el del filtro tienen que ser indistinguibles para el cliente, y no
 * basta con escribir el mismo texto en dos ficheros para que siga siéndolo.
 */
@Component
public class EntryPointNoAutenticado implements AuthenticationEntryPoint {

    @Override
    public void commence(HttpServletRequest req, HttpServletResponse res,
                         AuthenticationException ex) throws IOException {
        RespuestaSeguridad.escribir(res, req, HttpStatus.UNAUTHORIZED,
                "NO_AUTENTICADO", MensajesSeguridad.MSG_NO_AUTENTICADO);
    }
}
