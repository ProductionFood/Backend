package co.edu.corposucre.productionfood.common.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import jakarta.servlet.http.HttpServletRequest;

class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;
    private HttpServletRequest req;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
        req = mock(HttpServletRequest.class);
        when(req.getRequestURI()).thenReturn("/api/v1/usuarios");
    }

    @Test
    @DisplayName("Conflicto de negocio responde 409 con el código original")
    void conflictoDeNegocioResponde409() {
        var resp = handler.conflicto(
                new ConflictoNegocioException("CORREO_DUPLICADO", "Correo ya registrado."), req);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().code()).isEqualTo("CORREO_DUPLICADO");
        assertThat(resp.getBody().message()).isEqualTo("Correo ya registrado.");
        assertThat(resp.getBody().path()).isEqualTo("/api/v1/usuarios");
        assertThat(resp.getBody().fieldErrors()).isEmpty();
    }

    @Test
    @DisplayName("Recurso inexistente responde 404 con mensaje del recurso y el id")
    void recursoNoEncontradoResponde404() {
        var resp = handler.noEncontrado(
                new RecursoNoEncontradoException("usuario", 99), req);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().code()).isEqualTo("RECURSO_NO_ENCONTRADO");
        assertThat(resp.getBody().message())
                .isEqualTo("No se encontró usuario con id 99.");
    }

    @Test
    @DisplayName("Acceso denegado responde 403 con code SIN_PERMISO del contrato")
    void accesoDenegadoResponde403() {
        var resp = handler.accesoDenegado(
                new AccessDeniedException("Rol insuficiente."), req);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().code()).isEqualTo("SIN_PERMISO");
        assertThat(resp.getBody().message())
                .isEqualTo("No tiene permisos para realizar esta acción.");
    }

    @Test
    @DisplayName("Violación de integridad responde 409 CONFLICTO_INTEGRIDAD")
    void violacionDeIntegridadResponde409() {
        var resp = handler.integridad(
                new DataIntegrityViolationException("fk_rota"), req);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().code()).isEqualTo("CONFLICTO_INTEGRIDAD");
        assertThat(resp.getBody().message())
                .isEqualTo("La operación entra en conflicto con datos existentes.");
    }

    @Test
    @DisplayName("Validación de bean responde 400 con el detalle por campo")
    void validacionDeBeanResponde400ConCampo() {
        var binding = new BeanPropertyBindingResult(new Object(), "crearUsuarioRequest");
        binding.addError(new FieldError(
                "crearUsuarioRequest", "correo", "El correo es obligatorio"));
        var ex = new MethodArgumentNotValidException(null, binding);

        var resp = handler.validacion(ex, req);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().code()).isEqualTo("VALIDACION_FALLIDA");
        assertThat(resp.getBody().fieldErrors())
                .containsExactly(new ErrorResponse.FieldError(
                        "correo", "El correo es obligatorio"));
    }

    @Test
    @DisplayName("Excepción no controlada responde 500 sin filtrar el detalle interno")
    void excepcionGeneralResponde500SinFiltrarDetalle() {
        var resp = handler.general(
                new IllegalStateException("detalle interno sensible"), req);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().code()).isEqualTo("ERROR_INTERNO");
        assertThat(resp.getBody().message())
                .isEqualTo("Ocurrió un error inesperado. Intente nuevamente.")
                .doesNotContain("detalle interno");
        assertThat(resp.getBody().fieldErrors()).isEqualTo(List.of());
    }
}
