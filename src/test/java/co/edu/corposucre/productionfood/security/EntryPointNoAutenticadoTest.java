package co.edu.corposucre.productionfood.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.InsufficientAuthenticationException;

class EntryPointNoAutenticadoTest {

    @Test
    @DisplayName("Sin sesión válida responde 401 con code NO_AUTENTICADO y el mensaje del contrato")
    void sinSesionResponde401NoAutenticado() throws Exception {
        var req = new MockHttpServletRequest("GET", "/api/v1/roles");
        var res = new MockHttpServletResponse();

        new EntryPointNoAutenticado().commence(
                req, res, new InsufficientAuthenticationException("No hay token."));

        assertThat(res.getStatus()).isEqualTo(401);
        var body = res.getContentAsString();
        assertThat(body).contains("\"code\":\"NO_AUTENTICADO\"");
        assertThat(body).contains("Su sesión ha expirado. Inicie sesión nuevamente.");
        assertThat(body).contains("\"path\":\"/api/v1/roles\"");
    }
}
