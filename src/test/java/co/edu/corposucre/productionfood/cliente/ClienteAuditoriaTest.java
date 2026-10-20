package co.edu.corposucre.productionfood.cliente;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;

import co.edu.corposucre.productionfood.cliente.dto.ActualizarClienteRequest;
import co.edu.corposucre.productionfood.cliente.dto.ClienteResponse;
import co.edu.corposucre.productionfood.cliente.dto.CrearClienteRequest;
import co.edu.corposucre.productionfood.common.audit.Auditable;
import co.edu.corposucre.productionfood.common.dto.CambiarEstadoRequest;

/**
 * Guarda de las anotaciones de auditoría de {@link ClienteService} (HU-05).
 *
 * <p>No prueba el aspecto ni el registro en la bitácora: eso ya lo cubren sus propias pruebas.
 * Lo que fija aquí es el CONTRATO de las anotaciones escritas a mano, que hasta ahora solo
 * protegía la revisión humana: que la acción y la tabla sigan las de R-06 y que las
 * expresiones de detalle sigan siendo SpEL evaluable y no mencionen credenciales (R-05).
 */
class ClienteAuditoriaTest {

    /**
     * Métodos de escritura de {@link ClienteService} con su acción, tabla, datos de ejemplo y
     * detalle esperado. Es un único origen de verdad para las tres guardas: si mañana se anota
     * otro método de escritura, se añade aquí una sola entrada.
     */
    private static Stream<CasoAuditado> metodosDeEscritura() throws NoSuchMethodException {
        var crear = ClienteService.class.getMethod("crear", CrearClienteRequest.class);
        var actualizar = ClienteService.class.getMethod(
                "actualizar", Integer.class, ActualizarClienteRequest.class);
        var cambiarEstado = ClienteService.class.getMethod(
                "cambiarEstado", Integer.class, CambiarEstadoRequest.class);

        var respuesta = new ClienteResponse(10, "Tienda La Esquina", "Pedro Ruiz", "300 555 1234", true);

        return Stream.of(
                new CasoAuditado("crear", crear, "CREAR", "clientes",
                        new Object[]{new CrearClienteRequest("Tienda La Esquina", "Pedro Ruiz", "300 555 1234")},
                        respuesta,
                        "Cliente creado: Tienda La Esquina"),
                new CasoAuditado("actualizar", actualizar, "EDITAR", "clientes",
                        new Object[]{10, new ActualizarClienteRequest("Tienda La Esquina", "Pedro Ruiz", "300 555 1234")},
                        respuesta,
                        "Cliente editado: Tienda La Esquina"),
                new CasoAuditado("cambiarEstado", cambiarEstado, "CAMBIAR_ESTADO", "clientes",
                        new Object[]{10, new CambiarEstadoRequest(false)},
                        respuesta,
                        "Cliente 10 estado false"));
    }

    @ParameterizedTest
    @MethodSource("metodosDeEscritura")
    @DisplayName("Los métodos de escritura declaran @Auditable con la acción y la tabla esperadas")
    void declaracionDeAccionYTabla(CasoAuditado caso) {
        var anotacion = caso.metodo().getAnnotation(Auditable.class);

        assertThat(anotacion)
                .as("@Auditable en %s", caso.nombre())
                .isNotNull();
        assertThat(anotacion.accion()).isEqualTo(caso.accion());
        assertThat(anotacion.tabla()).isEqualTo(caso.tabla());
    }

    @ParameterizedTest
    @MethodSource("metodosDeEscritura")
    @DisplayName("Guarda de R-05: la expresión de detalle no menciona credenciales")
    void elDetalleNoMencionaCredenciales(CasoAuditado caso) {
        // Esta guarda existe porque una expresión que incluyera la credencial sería un fallo
        // SILENCIOSO: la anotación compila igual, el aspecto no la delata y el dato sensible
        // quedaría escrito en la bitácora, que se conserva y se consulta. Ninguna prueba de
        // comportamiento la detectaría mientras nadie lea la fila concreta.
        var detalle = caso.metodo().getAnnotation(Auditable.class)
                .detalle().toLowerCase(Locale.ROOT);

        assertThat(detalle)
                .as("el detalle de %s no debe mencionar credenciales (R-05)", caso.nombre())
                .doesNotContain("password")
                .doesNotContain("contraseña")
                .doesNotContain("hash")
                .doesNotContain("token");
    }

    @ParameterizedTest
    @MethodSource("metodosDeEscritura")
    @DisplayName("La expresión de detalle es SpEL válida y se evalúa con los argumentos y el resultado")
    void elDetalleSeEvaluaConDatosDeEjemplo(CasoAuditado caso) {
        assertThat(evaluar(caso)).isEqualTo(caso.detalleEsperado());
    }

    /**
     * Evalúa el detalle con un {@link SimpleEvaluationContext} de solo lectura, replicando cómo
     * lo hace el aspecto: argumentos por su nombre real y por alias posicional, y el retorno
     * como {@code resultado}. Se monta aquí y no se reutiliza el del aspecto para no exponer
     * como público un detalle de implementación que hoy no lo es.
     */
    private static String evaluar(CasoAuditado caso) {
        var contexto = SimpleEvaluationContext.forReadOnlyDataBinding().build();
        var parametros = caso.metodo().getParameters();
        for (int i = 0; i < caso.argumentos().length; i++) {
            contexto.setVariable(parametros[i].getName(), caso.argumentos()[i]);
            contexto.setVariable("p" + i, caso.argumentos()[i]);
        }
        contexto.setVariable("resultado", caso.resultado());

        var detalle = caso.metodo().getAnnotation(Auditable.class).detalle();
        return new SpelExpressionParser().parseExpression(detalle).getValue(contexto, String.class);
    }

    /** Caso de auditoría de un método de escritura, con los datos para evaluar su SpEL. */
    private record CasoAuditado(String nombre, Method metodo, String accion, String tabla,
                                Object[] argumentos, Object resultado, String detalleEsperado) {

        @Override
        public String toString() {
            return nombre;
        }
    }
}
