package co.edu.corposucre.productionfood.common.audit;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Pruebas del servicio de auditoría aisladas de Spring: la transacción REQUIRES_NEW y el
 * comportamiento con la base de datos real se validan en los PRs de integración, aquí importa
 * el contrato que la aplicación ve de {@code registrar()}.
 */
class BitacoraServiceTest {

    private BitacoraRepository bitacoraRepository;
    private BitacoraService bitacoraService;

    @BeforeEach
    void setUp() {
        bitacoraRepository = mock(BitacoraRepository.class);
        bitacoraService = new BitacoraService(bitacoraRepository);
    }

    private String detalleDe(int longitud) {
        return "a".repeat(longitud);
    }

    @Test
    @DisplayName("Un detalle de 300 caracteres llega recortado a los 255 de la columna")
    void registraDetalleSobredimensionadoRecortado() {
        var detalle = detalleDe(300);

        bitacoraService.registrar(7, "CREAR_USUARIO", "usuarios", detalle);

        // El recorte tiene que ocurrir ANTES del INSERT: con sql_mode estricto un detalle
        // de 300 caracteres hace fallar el INSERT y la acción quedaría sin auditar.
        verify(bitacoraRepository).insertar(7, "CREAR_USUARIO", "usuarios", detalle.substring(0, 255));
    }

    @Test
    @DisplayName("Un detalle de exactamente 255 caracteres llega intacto (frontera)")
    void registraDetalleEnElLimiteIntacto() {
        var detalle = detalleDe(255);

        bitacoraService.registrar(7, "CREAR_USUARIO", "usuarios", detalle);

        verify(bitacoraRepository).insertar(7, "CREAR_USUARIO", "usuarios", detalle);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t\n"})
    @DisplayName("Un detalle nulo, vacío o en blanco se persiste como null y no como cadena vacía")
    void registraDetalleVacioComoNull(String detalle) {
        bitacoraService.registrar(7, "CREAR_USUARIO", "usuarios", detalle);

        verify(bitacoraRepository).insertar(7, "CREAR_USUARIO", "usuarios", null);
    }

    @Test
    @DisplayName("Un id de usuario nulo se propaga tal cual (acción ejecutada por el sistema)")
    void registraAccionDelSistemaConIdUsuarioNulo() {
        bitacoraService.registrar(null, "LOGIN_FALLIDO", "usuarios", "correo desconocido");

        verify(bitacoraRepository).insertar(null, "LOGIN_FALLIDO", "usuarios", "correo desconocido");
    }

    @Test
    @DisplayName("Si el repositorio falla, registrar no propaga la excepción (R-03)")
    void noPropagaElFalloDelRepositorio() {
        doThrow(new DataIntegrityViolationException("detalle too long"))
                .when(bitacoraRepository).insertar(7, "CREAR_USUARIO", "usuarios", "detalle");

        assertThatNoException()
                .isThrownBy(() -> bitacoraService.registrar(7, "CREAR_USUARIO", "usuarios", "detalle"));
    }

    @Test
    @DisplayName("Un fallo ajeno a la capa de datos tampoco tumba la operación de negocio")
    void noPropagaUnFalloNoRelacionadoConSQLOConTransactional() {
        doThrow(new IllegalStateException("sin conexión"))
                .when(bitacoraRepository).insertar(7, "CREAR_USUARIO", "usuarios", null);

        assertThatNoException()
                .isThrownBy(() -> bitacoraService.registrar(7, "CREAR_USUARIO", "usuarios", null));
    }

    @Test
    @DisplayName("Una acción que supera los 50 caracteres lanza IllegalArgumentException sin insertar")
    void rechazaAccionMasLargaDeCincuenta() {
        var accion = "A".repeat(51);

        assertThatThrownBy(() -> bitacoraService.registrar(7, accion, "usuarios", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("accion");

        // Insertar igual dejaría una fila con un código que no se corresponde con ninguna
        // regla de negocio, que es justo lo que la auditoría no debe permitir.
        verify(bitacoraRepository, never()).insertar(7, accion, "usuarios", null);
    }

    @Test
    @DisplayName("Una tabla que supera los 50 caracteres lanza IllegalArgumentException sin insertar")
    void rechazaTablaMasLargaDeCincuenta() {
        var tabla = "t".repeat(51);

        assertThatThrownBy(() -> bitacoraService.registrar(7, "CREAR_USUARIO", tabla, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tabla");

        verify(bitacoraRepository, never()).insertar(7, "CREAR_USUARIO", tabla, null);
    }

    @Test
    @DisplayName("Una acción vacía o nula se rechaza: no identifica ninguna regla de negocio")
    void rechazaAccionVacia() {
        assertThatThrownBy(() -> bitacoraService.registrar(7, "  ", "usuarios", null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> bitacoraService.registrar(7, null, "usuarios", null))
                .isInstanceOf(IllegalArgumentException.class);

        verify(bitacoraRepository, never()).insertar(7, "  ", "usuarios", null);
    }

    @Test
    @DisplayName("Una acción de exactamente 50 caracteres se acepta sin recorte")
    void aceptaAccionEnElLimiteDeCincuenta() {
        var accion = "A".repeat(50);

        bitacoraService.registrar(7, accion, "usuarios", null);

        verify(bitacoraRepository).insertar(7, accion, "usuarios", null);
    }

    }
