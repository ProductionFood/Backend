package co.edu.corposucre.productionfood.common.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import jakarta.servlet.http.HttpServletRequest;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

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
        var resp = handler.apiException(
                new ConflictoNegocioException("CORREO_DUPLICADO", "Correo ya registrado."), req);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().code()).isEqualTo("CORREO_DUPLICADO");
        assertThat(resp.getBody().message()).isEqualTo("Correo ya registrado.");
        assertThat(resp.getBody().path()).isEqualTo("/api/v1/usuarios");
        assertThat(resp.getBody().fieldErrors()).isEmpty();
    }

    @Test
    @DisplayName("Parámetro inválido responde 400 con el código de la excepción")
    void parametroInvalidoResponde400() {
        var resp = handler.apiException(
                new ParametroInvalidoException("CAMPO_ORDEN_INVALIDO",
                        "El campo de ordenamiento 'password' no es válido."), req);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().code()).isEqualTo("CAMPO_ORDEN_INVALIDO");
        assertThat(resp.getBody().message())
                .isEqualTo("El campo de ordenamiento 'password' no es válido.");
        assertThat(resp.getBody().fieldErrors()).isEmpty();
    }

    @Test
    @DisplayName("Recurso inexistente responde 404 con mensaje del recurso y el id")
    void recursoNoEncontradoResponde404() {
        var resp = handler.apiException(
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
    @DisplayName("Parámetro de tipo incorrecto responde 400 PARAMETRO_INVALIDO")
    void parametroDeTipoIncorrectoResponde400() {
        var ex = new MethodArgumentTypeMismatchException(
                "abc", Integer.class, "page", null, null);

        var resp = handler.parametroMalFormado(ex, req);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().code()).isEqualTo("PARAMETRO_INVALIDO");
        assertThat(resp.getBody().message())
                .isEqualTo("El parámetro 'page' no es válido.");
    }

    @Test
    @DisplayName("Parámetro obligatorio ausente responde 400 PARAMETRO_INVALIDO")
    void parametroAusenteResponde400() {
        var ex = new MissingServletRequestParameterException("sort", "String");

        var resp = handler.parametroMalFormado(ex, req);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().code()).isEqualTo("PARAMETRO_INVALIDO");
        assertThat(resp.getBody().message())
                .isEqualTo("El parámetro 'sort' no es válido.");
    }

    @Test
    @DisplayName("Cuerpo JSON ilegible responde 400 VALIDACION_FALLIDA")
    void cuerpoIlegibleResponde400() {
        var resp = handler.cuerpoIlegible(
                new HttpMessageNotReadableException("{\"nombre\":", null), req);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().code()).isEqualTo("VALIDACION_FALLIDA");
        assertThat(resp.getBody().message())
                .isEqualTo("Los datos enviados no son válidos.");
        assertThat(resp.getBody().fieldErrors()).isEmpty();
    }

    @Test
    @DisplayName("Un tipo inválido en un campo responde 400 señalando ese campo")
    void cuerpoConTipoInvalidoResponde400ConElCampo() {
        var resp = handler.cuerpoIlegible(cuerpoIlegible("{\"cantidad\":\"abc\"}"), req);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().code()).isEqualTo("VALIDACION_FALLIDA");
        assertThat(resp.getBody().fieldErrors())
                .containsExactly(new ErrorResponse.FieldError(
                        "cantidad", "El valor del campo no es válido."));
    }

    @Test
    @DisplayName("Una fecha inválida responde 400 señalando el campo de fecha")
    void cuerpoConFechaInvalidaResponde400ConElCampo() {
        var resp = handler.cuerpoIlegible(
                cuerpoIlegible("{\"fechaProduccion\":\"2026-13-40\"}", CuerpoConFecha.class), req);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().code()).isEqualTo("VALIDACION_FALLIDA");
        assertThat(resp.getBody().fieldErrors())
                .containsExactly(new ErrorResponse.FieldError(
                        "fechaProduccion", "El valor del campo no es válido."));
    }

    @Test
    @DisplayName("Un JSON truncado responde 400 sin campo identificable")
    void cuerpoTruncadoResponde400SinCampo() {
        var resp = handler.cuerpoIlegible(cuerpoIlegible("{\"cantidad\":"), req);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().code()).isEqualTo("VALIDACION_FALLIDA");
        assertThat(resp.getBody().fieldErrors()).isEmpty();
    }

    @Test
    @DisplayName("Método no soportado responde 405 METODO_NO_PERMITIDO")
    void metodoNoSoportadoResponde405() {
        var resp = handler.metodoNoPermitido(
                new HttpRequestMethodNotSupportedException(
                        "DELETE", List.of("GET", "POST")), req);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().code()).isEqualTo("METODO_NO_PERMITIDO");
        assertThat(resp.getBody().message())
                .isEqualTo("El método 'DELETE' no está permitido para este recurso.");
        assertThat(resp.getBody().fieldErrors()).isEmpty();
    }

    @Test
    @DisplayName("Content-Type no soportado responde 415 con el tipo recibido en el mensaje")
    void tipoContenidoNoSoportadoResponde415() {
        var resp = handler.tipoContenidoNoSoportado(
                new HttpMediaTypeNotSupportedException(
                        MediaType.TEXT_PLAIN, List.of(MediaType.APPLICATION_JSON)), req);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().code()).isEqualTo("TIPO_CONTENIDO_NO_SOPORTADO");
        assertThat(resp.getBody().message())
                .isEqualTo("El tipo de contenido 'text/plain' no está soportado "
                        + "para este recurso.");
        assertThat(resp.getBody().path()).isEqualTo("/api/v1/usuarios");
        assertThat(resp.getBody().fieldErrors()).isEmpty();
    }

    @Test
    @DisplayName("Content-Type ausente responde 415 diciendo que no vino especificado")
    void tipoContenidoNoSoportadoSinTipoResponde415() {
        var resp = handler.tipoContenidoNoSoportado(
                new HttpMediaTypeNotSupportedException("Sin cabecera Content-Type"), req);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().code()).isEqualTo("TIPO_CONTENIDO_NO_SOPORTADO");
        assertThat(resp.getBody().message())
                .isEqualTo("El tipo de contenido 'no especificado' no está soportado "
                        + "para este recurso.");
        assertThat(resp.getBody().fieldErrors()).isEmpty();
    }

    @Test
    @DisplayName("Ruta sin controlador responde 404 RECURSO_NO_ENCONTRADO")
    void rutaNoEncontradaResponde404() {
        var resp = handler.rutaNoEncontrada(
                new NoResourceFoundException(
                        HttpMethod.GET,
                        "api/v1/loquesea",
                        "No static resource api/v1/loquesea."),
                req);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().code()).isEqualTo("RECURSO_NO_ENCONTRADO");
        assertThat(resp.getBody().message())
                .isEqualTo("No existe el recurso solicitado.");
        assertThat(resp.getBody().fieldErrors()).isEmpty();
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

    /**
     * Reproduce el fallo real de Jackson al deserializar el cuerpo contra un tipo concreto, para
     * que el manejador vea la misma causa y la misma ruta que en producción.
     */
    private static HttpMessageNotReadableException cuerpoIlegible(String json) {
        return cuerpoIlegible(json, CuerpoDePrueba.class);
    }

    private static HttpMessageNotReadableException cuerpoIlegible(String json, Class<?> tipo) {
        try {
            new ObjectMapper().readValue(json, tipo);
        } catch (JacksonException e) {
            return new HttpMessageNotReadableException("JSON ilegible", e, null);
        }
        throw new IllegalStateException("El JSON de prueba debería ser ilegible: " + json);
    }

    /**
     * Cuerpo mínimo con un campo numérico: {@code "abc"} dispara el error de tipo y un JSON
     * truncado, el de estructura.
     */
    private record CuerpoDePrueba(BigDecimal cantidad) {
    }

    /**
     * Cuerpo con una fecha: un valor fuera de rango dispara un error de formato cuyo campo también
     * debe señalarse.
     */
    private record CuerpoConFecha(LocalDate fechaProduccion) {
    }
}
