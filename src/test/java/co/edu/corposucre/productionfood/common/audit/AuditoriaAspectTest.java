package co.edu.corposucre.productionfood.common.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import co.edu.corposucre.productionfood.security.UsuarioAutenticado;

/**
 * Pruebas del aspecto de auditoría levantadas sobre un proxy real de AspectJ, no invocando
 * el método advise a mano: R-02 (no auditar lo que falló) depende de que el advice solo se
 * ejecute en el retorno normal, y eso solo se demuestra si un aspecto intercepta de verdad.
 */
class AuditoriaAspectTest {

    private BitacoraService bitacoraService;
    private ServicioAuditable servicio;

    @BeforeEach
    void setUp() {
        bitacoraService = mock(BitacoraService.class);

        var factory = new AspectJProxyFactory(new ServicioAuditable());
        factory.addAspect(new AuditoriaAspect(bitacoraService));
        servicio = factory.getProxy();
    }

    @Test
    @DisplayName("Al retornar con éxito se registra con el usuario del SecurityContext, la acción y la tabla")
    void registraAccionTablaYUsuarioActual() {
        autenticarComo(7);

        servicio.crear("Ana Pérez");

        verify(bitacoraService).registrar(7, "CREAR_USUARIO", "usuarios", "Ana Pérez");
    }

    @Test
    @DisplayName("Si el método lanza no se registra nada (R-02)")
    void noRegistraSiElMetodoFalla() {
        autenticarComo(7);

        assertThatThrownBy(() -> servicio.fallar())
                .isInstanceOf(IllegalStateException.class);

        // Registrar antes del fallo dejaría en la bitácora un cambio que nunca ocurrió.
        verify(bitacoraService, never()).registrar(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Sin SecurityContext poblado la acción del sistema se registra con idUsuario null")
    void registraConIdUsuarioNuloSinSecurityContext() {
        servicio.crear("Ana Pérez");

        verify(bitacoraService).registrar(null, "CREAR_USUARIO", "usuarios", "Ana Pérez");
    }

    @Test
    @DisplayName("La expresión de detalle se evalúa con los argumentos y el valor retornado")
    void evaluaElDetalleConArgumentosYResultado() {
        autenticarComo(3);

        servicio.crearConDetalleExpreso("compra-88", "ABIERTA");

        verify(bitacoraService).registrar(3, "CREAR_COMPRA", "compras",
                "compra compra-88 estado ABIERTA resultado compra-88|ABIERTA");
    }

    @Test
    @DisplayName("Un detalle en blanco se registra con detalle null")
    void registraDetalleNullSiLaExpresionEstaVacia() {
        autenticarComo(5);

        servicio.crearSinDetalle("Bruno");

        verify(bitacoraService).registrar(5, "CREAR_USUARIO", "usuarios", null);
    }

    @Test
    @DisplayName("Si registrar() lanza un fallo de infraestructura, el advise no propaga (R-03)")
    void noPropagaElFalloDeRegistrar() {
        autenticarComo(7);
        doThrow(new IllegalStateException("la transacción de auditoría quedó rollback-only"))
                .when(bitacoraService).registrar(7, "CREAR_USUARIO", "usuarios", "Ana Pérez");

        // Lo que se verifica aquí es que la operación ya ejecutada no se cae por el registro.
        assertThatNoException().isThrownBy(() -> servicio.crear("Ana Pérez"));
    }

    @Test
    @DisplayName("Si registrar() lanza IllegalArgumentException, el error SÍ sale: la @Auditable está mal escrita")
    void propagaElCodigoDeAuditoriaInvalido() {
        autenticarComo(7);
        doThrow(new IllegalArgumentException(
                "El campo 'accion' supera los 50 caracteres de bitacora; acórtalo en el código."))
                .when(bitacoraService).registrar(7, "CREAR_USUARIO", "usuarios", "Ana Pérez");

        // Un código de acción o tabla solo puede ser inválido por una anotación mal escrita:
        // tragárselo aquí dejaría un hueco silencioso en lugar de un error visible donde se
        // introdujo el fallo.
        assertThatThrownBy(() -> servicio.crear("Ana Pérez"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("supera los 50 caracteres");
    }

    @Test
    @DisplayName("Una expresión SpEL mal escrita registra la acción sin detalle y no tumba la operación")
    void registraSinDetalleSiLaExpresionEsInvalida() {
        autenticarComo(9);

        assertThatNoException().isThrownBy(() -> servicio.crearConDetalleInvalido("Ana"));

        verify(bitacoraService).registrar(9, "CREAR_USUARIO", "usuarios", null);
    }

    @Test
    @DisplayName("El detalle generado no contiene la contraseña que el método recibe (R-05)")
    void elDetalleNoIncluyeLaContrasena() {
        autenticarComo(7);

        servicio.crearConPassword("ana@pf.local", "Clave2026*");

        var captor = ArgumentCaptor.forClass(String.class);
        verify(bitacoraService).registrar(eq(7), eq("CREAR_USUARIO"), eq("usuarios"), captor.capture());
        assertThat(captor.getValue()).doesNotContain("Clave2026*");
    }

    @Test
    @DisplayName("Los argumentos también se alcanzan por su alias posicional")
    void losArgumentosSeAlcanzanPorAliasPosicional() {
        autenticarComo(2);

        servicio.crearPorAlias("Bruno Díaz");

        verify(bitacoraService).registrar(2, "CREAR_USUARIO", "usuarios", "Bruno Díaz");
    }

    @AfterEach
    void limpiarSeguridad() {
        SecurityContextHolder.clearContext();
    }

    private void autenticarComo(Integer idUsuario) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new UsuarioAutenticado(idUsuario, "admin@pf.local", "Admin", "ADMIN"),
                        null, List.of()));
    }

    /**
     * Objetivo de negocio simulado: al ser una clase sin interfaz, el proxy se construye con
     * CGLIB y las anotaciones se leen del método real, que es lo que hace el punto de corte
     * {@code @annotation(auditable)} en producción.
     */
    static class ServicioAuditable {

        @Auditable(accion = "CREAR_USUARIO", tabla = "usuarios", detalle = "#nombre")
        public String crear(String nombre) {
            return nombre;
        }

        @Auditable(accion = "CREAR_USUARIO", tabla = "usuarios")
        public String crearSinDetalle(String nombre) {
            return nombre;
        }

        @Auditable(accion = "CREAR_USUARIO", tabla = "usuarios", detalle = "#correo")
        public String crearConPassword(String correo, String password) {
            return correo;
        }

        @Auditable(accion = "CREAR_USUARIO", tabla = "usuarios", detalle = "#p0")
        public String crearPorAlias(String nombre) {
            return nombre;
        }

        @Auditable(accion = "CREAR_USUARIO", tabla = "usuarios", detalle = "#nombre +")
        public String crearConDetalleInvalido(String nombre) {
            return nombre;
        }

        @Auditable(accion = "CREAR_COMPRA", tabla = "compras",
                detalle = "'compra ' + #codigo + ' estado ' + #estado + ' resultado ' + #resultado")
        public String crearConDetalleExpreso(String codigo, String estado) {
            return codigo + "|" + estado;
        }

        @Auditable(accion = "CREAR_USUARIO", tabla = "usuarios", detalle = "#nombre")
        public String fallar() {
            throw new IllegalStateException("la operación de negocio falló después del cambio");
        }
    }
}