package co.edu.corposucre.productionfood.common.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import co.edu.corposucre.productionfood.unidadmedida.UnidadMedidaService;
import co.edu.corposucre.productionfood.unidadmedida.dto.CrearUnidadRequest;

/**
 * Regresión del agotamiento del pool por la auditoría (HU-23).
 *
 * <p>Si el aspecto de auditoría corre DENTRO de la transacción del servicio, cada petición
 * retiene su conexión y pide una segunda para el {@code REQUIRES_NEW} de la bitácora: con el
 * pool lleno de transacciones externas todas esperan una conexión que nadie libera, hasta el
 * timeout. El pool se reduce aquí a 3 conexiones y a 3 s de espera, y se lanzan más
 * escrituras auditables simultáneas que conexiones, de modo que el defecto se manifiesta como
 * un fallo de conexión en pocos segundos y no como una prueba lenta.
 *
 * <p>Se omite con {@code assumeTrue} si MySQL no responde, igual que las demás pruebas de
 * integración del proyecto.
 */
@SpringBootTest(properties = {
        "spring.datasource.hikari.maximum-pool-size=3",
        "spring.datasource.hikari.minimum-idle=3",
        "spring.datasource.hikari.connection-timeout=3000"})
class AuditoriaPoolIntegrationTest {

    private static final String URL = "jdbc:mysql://"
            + entorno("DB_HOST", "127.0.0.1") + ":" + entorno("DB_PORT", "3306")
            + "/" + entorno("DB_NAME", "productionfood")
            + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=America/Bogota";
    private static final String USUARIO = entorno("DB_USER", "root");
    private static final String CLAVE = entorno("DB_PASSWORD", "local");

    private static final int ESCRITURAS = 8;

    @Autowired
    private UnidadMedidaService unidadMedidaService;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    private final String sufijo = UUID.randomUUID().toString().substring(0, 8);

    private static String entorno(String nombre, String defecto) {
        String valor = System.getenv(nombre);
        return valor == null || valor.isBlank() ? defecto : valor;
    }

    @BeforeAll
    static void laBaseDelProyectoEstaDisponible() {
        boolean disponible;
        try (var conexion = java.sql.DriverManager.getConnection(URL, USUARIO, CLAVE);
             var sentencia = conexion.createStatement()) {
            sentencia.executeQuery("SELECT 1 FROM bitacora LIMIT 1").close();
            disponible = true;
        } catch (SQLException e) {
            disponible = false;
        }
        assumeTrue(disponible, "MySQL no responde en " + URL + "; se omite.");
    }

    @AfterEach
    void limpiar() {
        var parametros = new MapSqlParameterSource("patron", "%-" + sufijo + "%");
        jdbc.update("DELETE FROM bitacora WHERE detalle LIKE :patron", parametros);
        jdbc.update("DELETE FROM unidades_medida WHERE nombre LIKE :patron", parametros);
    }

    @Test
    @DisplayName("Más escrituras auditables simultáneas que conexiones completan sin agotar el pool")
    void escriturasAuditablesConcurrentesNoAgotanElPool() throws Exception {
        var pool = Executors.newFixedThreadPool(ESCRITURAS);
        var salida = new CountDownLatch(1);
        List<Future<Object>> resultados = new ArrayList<>();
        try {
            for (int i = 0; i < ESCRITURAS; i++) {
                var n = i;
                Callable<Object> tarea = () -> {
                    salida.await();
                    return unidadMedidaService.crear(
                            new CrearUnidadRequest("Pool" + n + "-" + sufijo, "p" + n + sufijo));
                };
                resultados.add(pool.submit(tarea));
            }

            long inicio = System.nanoTime();
            salida.countDown();
            for (var resultado : resultados) {
                assertThat(resultado.get(20, TimeUnit.SECONDS)).isNotNull();
            }
            var duracion = Duration.ofNanos(System.nanoTime() - inicio);

            // Con el defecto fallan por timeout (3 s) o, con un pool mayor, tardan lo del timeout.
            assertThat(duracion).isLessThan(Duration.ofSeconds(10));
        } finally {
            pool.shutdownNow();
        }

        Integer filas = jdbc.queryForObject(
                "SELECT COUNT(*) FROM bitacora WHERE accion = 'CREAR' "
                        + "AND tabla_afectada = 'unidades_medida' AND detalle LIKE :patron",
                new MapSqlParameterSource("patron", "%-" + sufijo), Integer.class);
        assertThat(filas).isEqualTo(ESCRITURAS);
    }
}
