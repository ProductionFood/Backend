package co.edu.corposucre.productionfood.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.InsufficientAuthenticationException;

class EntryPointNoAutenticadoTest {

    @Test
    @DisplayName("Sin cabecera Authorization responde 401 NO_AUTENTICADO pidiendo iniciar sesión")
    void sinSesionResponde401NoAutenticado() throws Exception {
        var req = new MockHttpServletRequest("GET", "/api/v1/roles");
        var res = new MockHttpServletResponse();

        new EntryPointNoAutenticado().commence(
                req, res, new InsufficientAuthenticationException("No hay token."));

        assertThat(res.getStatus()).isEqualTo(401);
        var body = res.getContentAsString();
        assertThat(body).contains("\"code\":\"NO_AUTENTICADO\"");
        assertThat(body).contains(MensajesSeguridad.MSG_SIN_TOKEN);
        assertThat(body).contains("\"path\":\"/api/v1/roles\"");
    }

    @Test
    @DisplayName("Token expirado conserva el mensaje de sesión expirada con el mismo code")
    void tokenExpiradoConservaMensajeDeExpiracion() throws Exception {
        var body = commenceConCausa(MensajesSeguridad.MSG_NO_AUTENTICADO);

        assertThat(body).contains("\"code\":\"NO_AUTENTICADO\"");
        assertThat(body).contains("Su sesión ha expirado. Inicie sesión nuevamente.");
    }

    @Test
    @DisplayName("Token inválido responde mensaje de token inválido con el mismo code")
    void tokenInvalidoRespondeMensajeDeTokenInvalido() throws Exception {
        var body = commenceConCausa(MensajesSeguridad.MSG_TOKEN_INVALIDO);

        assertThat(body).contains("\"code\":\"NO_AUTENTICADO\"");
        assertThat(body).contains(MensajesSeguridad.MSG_TOKEN_INVALIDO);
        assertThat(body).doesNotContain("ha expirado");
    }

    private String commenceConCausa(String mensaje) throws Exception {
        var req = new MockHttpServletRequest("GET", "/api/v1/roles");
        req.setAttribute(MensajesSeguridad.ATRIBUTO_MENSAJE_401, mensaje);
        var res = new MockHttpServletResponse();
        new EntryPointNoAutenticado().commence(
                req, res, new InsufficientAuthenticationException("x"));
        assertThat(res.getStatus()).isEqualTo(401);
        return res.getContentAsString();
    }
}
