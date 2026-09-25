package co.edu.corposucre.productionfood.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;

class AccesoDenegadoHandlerTest {

    @Test
    @DisplayName("Rol sin permiso responde 403 con code SIN_PERMISO del contrato")
    void rolSinPermisoResponde403() throws Exception {
        var req = new MockHttpServletRequest("GET", "/api/v1/roles");
        var res = new MockHttpServletResponse();

        new AccesoDenegadoHandler().handle(
                req, res, new AccessDeniedException("Rol insuficiente."));

        assertThat(res.getStatus()).isEqualTo(403);
        var body = res.getContentAsString();
        assertThat(body).contains("\"code\":\"SIN_PERMISO\"");
        assertThat(body).contains("No tiene permisos para realizar esta acción.");
        assertThat(body).contains("\"path\":\"/api/v1/roles\"");
    }
}
