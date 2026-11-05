package co.edu.corposucre.productionfood.produccion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import co.edu.corposucre.productionfood.common.error.ConflictoNegocioException;
import co.edu.corposucre.productionfood.inventario.AlertaService;
import co.edu.corposucre.productionfood.produccion.dto.ActualizarConsumoRequest;
import co.edu.corposucre.productionfood.produccion.dto.RegistrarConsumoRequest;

/**
 * Pruebas de integración de punta a punta de HU-23 (inventario automático por producción)
 * contra MySQL.
 *
 * <p>Usan el contexto real de Spring —{@link ConsumoService}, {@code InventarioService}, los
 * repositorios y las transacciones reales—. La clase <strong>no</strong> es transaccional: la
 * garantía que se prueba es el commit y el rollback reales, que un {@code @Transactional} de
 * prueba ocultaría. El proyecto no usa Testcontainers ni H2: la única base soportada es la del
 * {@code docker-compose}, y las pruebas se omiten con {@code assumeTrue} si no responde.
 *
 * <p>Cada prueba siembra su propia unidad, usuario, plan {@code EN_PROCESO}, productos, líneas y
 * materias primas con sufijo único {@code HU23-<uuid>} y limpia solo lo suyo. Cada materia prima
 * nace con una {@code ENTRADA_COMPRA} en el kardex que explica su saldo inicial, igual que en
 * producción; así la consulta de conciliación kardex contra saldo (CP-21) puede exigir cero filas
 * al terminar <em>cada</em> prueba, y también que no haya saldos negativos ni movimientos
 * huérfanos (CP-13, CP-15).
 */
@SpringBootTest
class ConsumoInventarioIntegrationTest {

    private static final String URL = "jdbc:mysql://"
            + entorno("DB_HOST", "127.0.0.1") + ":" + entorno("DB_PORT", "3306")
            + "/" + entorno("DB_NAME", "productionfood")
            + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=America/Bogota";
    private static final String USUARIO = entorno("DB_USER", "root");
    private static final String CLAVE = entorno("DB_PASSWORD", "local");

    private static final String ORIGEN = "consumo_materia_prima";

    @Autowired
    private ConsumoService consumoService;

    @Autowired
    private AlertaService alertaService;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /**
     * Espía sobre el repositorio de consumos, solo para CP-12: permite provocar un fallo
     * posterior al descuento del inventario. Mockito lo reinicia después de cada prueba.
     */
    @MockitoSpyBean
    private ConsumoRepository consumoRepository;

    private String sufijo;
    private Integer idUnidad;
    private Integer idUsuario;
    private Integer idPlan;
    private final List<Integer> idsProducto = new ArrayList<>();
    private final List<Integer> idsDetalle = new ArrayList<>();
    private final List<Integer> idsMateriaPrima = new ArrayList<>();

    private static String entorno(String nombre, String defecto) {
        String valor = System.getenv(nombre);
        return valor == null || valor.isBlank() ? defecto : valor;
    }

    @BeforeAll
    static void laBaseDelProyectoEstaDisponible() {
        boolean disponible;
        try (var conexion = java.sql.DriverManager.getConnection(URL, USUARIO, CLAVE);
             var sentencia = conexion.createStatement()) {
            sentencia.executeQuery("SELECT 1 FROM movimientos_inventario_mp LIMIT 1").close();
            disponible = true;
        } catch (SQLException e) {
            disponible = false;
        }
        assumeTrue(disponible,
                "MySQL no responde en " + URL + " con el kardex de materia prima; se omite.");
    }

    @BeforeEach
    void setUp() {
        sufijo = "-HU23-" + UUID.randomUUID().toString().substring(0, 8);

        idUnidad = insertarConId("""
                INSERT INTO unidades_medida (nombre, abreviatura)
                VALUES (:nombre, :abreviatura)
                """, new MapSqlParameterSource()
                        .addValue("nombre", "Unidad consumo" + sufijo)
                        .addValue("abreviatura", "u23" + sufijo.substring(sufijo.length() - 8)));

        var idRol = jdbc.queryForObject("SELECT id_rol FROM roles ORDER BY id_rol ASC LIMIT 1",
                new MapSqlParameterSource(), Integer.class);
        idUsuario = insertarConId("""
                INSERT INTO usuarios (nombre, correo, password, estado, id_rol)
                VALUES (:nombre, :correo, 'clave-test', 1, :idRol)
                """, new MapSqlParameterSource()
                        .addValue("nombre", "Responsable consumo" + sufijo)
                        .addValue("correo", "h23-" + UUID.randomUUID().toString()
                                .substring(0, 8) + "@test.local")
                        .addValue("idRol", idRol));

        idPlan = insertarConId("""
                INSERT INTO planes_produccion (fecha_planificacion, fecha_produccion, estado,
                                               observaciones, id_usuario)
                VALUES (:planificacion, :produccion, 'EN_PROCESO', NULL, :idUsuario)
                """, new MapSqlParameterSource()
                        .addValue("planificacion", LocalDate.of(1990, 11, 10))
                        .addValue("produccion", LocalDate.of(1990, 11, 12))
                        .addValue("idUsuario", idUsuario));
    }

    @AfterEach
    void tearDown() {
        // Las garantías transversales se comprueban al cierre de cada prueba, antes de limpiar,
        // y limitadas a las materias primas sembradas: los datos ajenos de la base no cuentan.
        List<Object> violaciones = new ArrayList<>();
        if (!idsMateriaPrima.isEmpty()) {
            violaciones.addAll(conciliacion());
            violaciones.addAll(saldosNegativos());
            violaciones.addAll(movimientosHuerfanos());
        }
        try {
            limpiar();
        } finally {
            assertThat(violaciones)
                    .as("CP-13/CP-15/CP-21: conciliación, negativos o huérfanos al cierre")
                    .isEmpty();
        }
    }

    private void limpiar() {
        if (!idsDetalle.isEmpty()) {
            var ids = new MapSqlParameterSource().addValue("ids", idsDetalle);
            // La bitácora no tiene FK; el aspecto de auditoría escribe el id de la línea al
            // final del detalle, y solo esas filas se retiran.
            for (var idDetalle : idsDetalle) {
                jdbc.update("""
                        DELETE FROM bitacora
                        WHERE tabla_afectada = 'consumo_materia_prima'
                          AND detalle LIKE :patron
                        """, new MapSqlParameterSource()
                                .addValue("patron", "% línea " + idDetalle));
            }
            jdbc.update("DELETE FROM consumo_materia_prima WHERE id_detalle_plan IN (:ids)", ids);
            jdbc.update("DELETE FROM detalle_plan_produccion WHERE id_detalle_plan IN (:ids)",
                    ids);
        }
        if (!idsMateriaPrima.isEmpty()) {
            var ids = new MapSqlParameterSource().addValue("ids", idsMateriaPrima);
            jdbc.update("DELETE FROM movimientos_inventario_mp WHERE id_materia_prima IN (:ids)",
                    ids);
            jdbc.update("DELETE FROM inventario WHERE id_materia_prima IN (:ids)", ids);
        }
        if (idPlan != null) {
            jdbc.update("DELETE FROM planes_produccion WHERE id_plan = :id",
                    new MapSqlParameterSource().addValue("id", idPlan));
        }
        if (!idsProducto.isEmpty()) {
            jdbc.update("DELETE FROM productos WHERE id_producto IN (:ids)",
                    new MapSqlParameterSource().addValue("ids", idsProducto));
        }
        if (!idsMateriaPrima.isEmpty()) {
            jdbc.update("DELETE FROM materias_primas WHERE id_materia_prima IN (:ids)",
                    new MapSqlParameterSource().addValue("ids", idsMateriaPrima));
        }
        if (idUsuario != null) {
            jdbc.update("DELETE FROM usuarios WHERE id_usuario = :id",
                    new MapSqlParameterSource().addValue("id", idUsuario));
        }
        if (idUnidad != null) {
            jdbc.update("DELETE FROM unidades_medida WHERE id_unidad = :id",
                    new MapSqlParameterSource().addValue("id", idUnidad));
        }
    }

    // ------------------------------------------------------------------------------------
    // CA-01
    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName("CA-01/CP-01/CP-02: registrar descuenta 16.200 de 112.50 y deja la SALIDA_PRODUCCION")
    void registrarDescuentaElInventarioYDejaElMovimiento() {
        var mp = sembrarMateriaPrima("50.00", "112.50");
        var linea = sembrarLinea();

        var respuesta = consumoService.registrar(linea,
                new RegistrarConsumoRequest(mp, new BigDecimal("16.200")));

        assertThat(saldo(mp)).isEqualByComparingTo("96.30");
        assertThat(respuesta.inventario().anterior()).isEqualByComparingTo("112.50");
        assertThat(respuesta.inventario().posterior()).isEqualByComparingTo("96.30");

        var movimientos = salidas(mp);
        assertThat(movimientos).hasSize(1);
        var mov = movimientos.get(0);
        assertThat(mov.tipo()).isEqualTo("SALIDA_PRODUCCION");
        assertThat(mov.cantidad()).isEqualByComparingTo("16.200");
        assertThat(mov.saldoAnterior()).isEqualByComparingTo("112.50");
        assertThat(mov.saldoPosterior()).isEqualByComparingTo("96.30");
        assertThat(mov.tablaOrigen()).isEqualTo(ORIGEN);
        assertThat(mov.idOrigen()).isEqualTo(respuesta.idConsumo());
    }

    @Test
    @DisplayName("CP-17: 16.205 se redondea HALF_UP y el inventario baja 16.21")
    void redondeoHaciaArribaBajaDieciseisVeintiuno() {
        var mp = sembrarMateriaPrima("50.00", "112.50");
        var linea = sembrarLinea();

        consumoService.registrar(linea, new RegistrarConsumoRequest(mp, new BigDecimal("16.205")));

        assertThat(saldo(mp)).isEqualByComparingTo("96.29");
        assertThat(salidas(mp)).singleElement()
                .satisfies(m -> assertThat(m.cantidad()).isEqualByComparingTo("16.210"));
        // El consumo conserva sus tres decimales; solo el inventario redondea.
        assertThat(cantidadRealDe(linea, mp)).isEqualByComparingTo("16.205");
    }

    @Test
    @DisplayName("CP-18: 16.204 se redondea hacia abajo y el inventario baja 16.20")
    void redondeoHaciaAbajoBajaDieciseisVeinte() {
        var mp = sembrarMateriaPrima("50.00", "112.50");
        var linea = sembrarLinea();

        consumoService.registrar(linea, new RegistrarConsumoRequest(mp, new BigDecimal("16.204")));

        assertThat(saldo(mp)).isEqualByComparingTo("96.30");
        assertThat(salidas(mp)).singleElement()
                .satisfies(m -> assertThat(m.cantidad()).isEqualByComparingTo("16.200"));
    }

    // ------------------------------------------------------------------------------------
    // CA-02
    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName("CA-02/CP-04: fecha_actualizacion cambia sola (ON UPDATE vive en la base)")
    void fechaActualizacionCambiaSola() throws InterruptedException {
        var mp = sembrarMateriaPrima("50.00", "112.50");
        var linea = sembrarLinea();
        var antes = fechaActualizacion(mp);

        // La columna es DATETIME de segundos: hay que cruzar al menos un segundo completo.
        Thread.sleep(1_200);
        consumoService.registrar(linea, new RegistrarConsumoRequest(mp, new BigDecimal("16.200")));

        assertThat(fechaActualizacion(mp)).isAfter(antes);
    }

    @Test
    @DisplayName("CA-02/CP-04: inventario.fecha_actualizacion, kardex.fecha y la JVM comparten zona horaria")
    void zonaHorariaCoherenteEntreBaseYAplicacion() {
        var mp = sembrarMateriaPrima("50.00", "112.50");
        var linea = sembrarLinea();

        consumoService.registrar(linea, new RegistrarConsumoRequest(mp, new BigDecimal("16.200")));
        var ahora = LocalDateTime.now();

        var fechaInventario = fechaActualizacion(mp);
        var fechaKardex = jdbc.queryForObject("""
                SELECT fecha FROM movimientos_inventario_mp
                WHERE id_materia_prima = :id AND tipo_movimiento = 'SALIDA_PRODUCCION'
                """, new MapSqlParameterSource().addValue("id", mp), LocalDateTime.class);

        assertThat(Duration.between(fechaInventario, ahora).abs())
                .as("inventario.fecha_actualizacion=%s vs LocalDateTime.now()=%s",
                        fechaInventario, ahora)
                .isLessThan(Duration.ofMinutes(2));
        assertThat(Duration.between(fechaKardex, ahora).abs())
                .as("movimientos_inventario_mp.fecha=%s vs LocalDateTime.now()=%s",
                        fechaKardex, ahora)
                .isLessThan(Duration.ofMinutes(2));
        assertThat(Duration.between(fechaInventario, fechaKardex).abs())
                .as("inventario.fecha_actualizacion=%s vs kardex.fecha=%s",
                        fechaInventario, fechaKardex)
                .isLessThan(Duration.ofMinutes(2));
    }

    // ------------------------------------------------------------------------------------
    // CA-03
    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName("CA-03/CP-06/CP-09: bajo el mínimo responde alerta BAJO con faltante 3.70 y el consumo queda registrado")
    void alertaBajoConFaltanteYSinBloqueo() {
        var mp = sembrarMateriaPrima("50.00", "62.50");
        var linea = sembrarLinea();

        var respuesta = consumoService.registrar(linea,
                new RegistrarConsumoRequest(mp, new BigDecimal("16.200")));

        assertThat(respuesta.alertas()).hasSize(1);
        var alerta = respuesta.alertas().get(0);
        assertThat(alerta.idMateriaPrima()).isEqualTo(mp);
        assertThat(alerta.nivel()).isEqualTo("BAJO");
        assertThat(alerta.cantidadDisponible()).isEqualByComparingTo("46.30");
        assertThat(alerta.stockMinimo()).isEqualByComparingTo("50.00");
        assertThat(alerta.faltante()).isEqualByComparingTo("3.70");
        // CP-09: la alerta no bloquea; el consumo y su movimiento están persistidos.
        assertThat(contarConsumos(linea)).isEqualTo(1);
        assertThat(salidas(mp)).hasSize(1);
        assertThat(saldo(mp)).isEqualByComparingTo("46.30");
    }

    @Test
    @DisplayName("CA-03/CP-07: el saldo en cero responde alerta AGOTADO")
    void alertaAgotadoEnCero() {
        var mp = sembrarMateriaPrima("50.00", "16.20");
        var linea = sembrarLinea();

        var respuesta = consumoService.registrar(linea,
                new RegistrarConsumoRequest(mp, new BigDecimal("16.200")));

        assertThat(saldo(mp)).isEqualByComparingTo("0.00");
        assertThat(respuesta.alertas()).singleElement().satisfies(a -> {
            assertThat(a.nivel()).isEqualTo("AGOTADO");
            assertThat(a.faltante()).isEqualByComparingTo("50.00");
        });
    }

    @Test
    @DisplayName("CA-03/CP-08: por encima del mínimo, alertas es []")
    void sinAlertaPorEncimaDelMinimo() {
        var mp = sembrarMateriaPrima("50.00", "112.50");
        var linea = sembrarLinea();

        var respuesta = consumoService.registrar(linea,
                new RegistrarConsumoRequest(mp, new BigDecimal("16.200")));

        assertThat(respuesta.alertas()).isEmpty();
    }

    @Test
    @DisplayName("CA-03/CP-10: exactamente en el mínimo (50.00) no hay alerta")
    void sinAlertaEnIgualdadExactaConElMinimo() {
        var mp = sembrarMateriaPrima("50.00", "66.20");
        var linea = sembrarLinea();

        var respuesta = consumoService.registrar(linea,
                new RegistrarConsumoRequest(mp, new BigDecimal("16.200")));

        assertThat(saldo(mp)).isEqualByComparingTo("50.00");
        assertThat(respuesta.alertas()).isEmpty();
    }

    @Test
    @DisplayName("CA-03: tras el consumo AlertaService.listar(false) ya muestra la materia prima")
    void elListadoDeAlertasMuestraLaMateriaPrimaDeInmediato() {
        var mp = sembrarMateriaPrima("50.00", "62.50");
        var linea = sembrarLinea();
        assertThat(alertaService.listar(false).items())
                .noneMatch(a -> a.idMateriaPrima().equals(mp));

        consumoService.registrar(linea, new RegistrarConsumoRequest(mp, new BigDecimal("16.200")));

        assertThat(alertaService.listar(false).items())
                .filteredOn(a -> a.idMateriaPrima().equals(mp))
                .singleElement()
                .satisfies(a -> {
                    assertThat(a.nivel()).isEqualTo("BAJO");
                    assertThat(a.faltante()).isEqualByComparingTo("3.70");
                });
    }

    // ------------------------------------------------------------------------------------
    // CA-04
    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName("CA-04/CP-16: consumir 200 con 96.30 responde STOCK_INSUFICIENTE y revierte todo")
    void stockInsuficienteRevierteTodo() throws InterruptedException {
        var mp = sembrarMateriaPrima("50.00", "96.30");
        var linea = sembrarLinea();
        var fechaAntes = fechaActualizacion(mp);
        Thread.sleep(1_200);

        assertThatThrownBy(() -> consumoService.registrar(linea,
                new RegistrarConsumoRequest(mp, new BigDecimal("200.000"))))
                .isInstanceOfSatisfying(ConflictoNegocioException.class, ex -> {
                    assertThat(ex.getCodigo()).isEqualTo("STOCK_INSUFICIENTE");
                    // R-05: el mensaje trae la cifra disponible y la solicitada.
                    assertThat(ex.getMessage()).contains("96.30").contains("200");
                });

        assertThat(contarConsumos(linea)).isZero();
        assertThat(salidas(mp)).isEmpty();
        assertThat(saldo(mp)).isEqualByComparingTo("96.30");
        assertThat(fechaActualizacion(mp)).isEqualTo(fechaAntes);
    }

    @Test
    @DisplayName("CA-04/CP-11: el tercero de tres consumos sin stock revierte también los dos primeros")
    void elTerceroSinStockRevierteLosTresConsumos() {
        // El servicio registra un ingrediente por llamada; el escenario de "tres consumos en una
        // petición" se ejerce con una transacción del llamador que los agrupa, que es la forma
        // en la que un fallo posterior debe deshacer los descuentos anteriores.
        var mp1 = sembrarMateriaPrima("5.00", "100.00");
        var mp2 = sembrarMateriaPrima("5.00", "100.00");
        var mp3 = sembrarMateriaPrima("5.00", "10.00");
        var linea = sembrarLinea();
        var transaccion = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> transaccion.executeWithoutResult(estado -> {
            consumoService.registrar(linea,
                    new RegistrarConsumoRequest(mp1, new BigDecimal("10.000")));
            consumoService.registrar(linea,
                    new RegistrarConsumoRequest(mp2, new BigDecimal("20.000")));
            consumoService.registrar(linea,
                    new RegistrarConsumoRequest(mp3, new BigDecimal("30.000")));
        })).isInstanceOfSatisfying(ConflictoNegocioException.class,
                ex -> assertThat(ex.getCodigo()).isEqualTo("STOCK_INSUFICIENTE"));

        assertThat(saldo(mp1)).isEqualByComparingTo("100.00");
        assertThat(saldo(mp2)).isEqualByComparingTo("100.00");
        assertThat(saldo(mp3)).isEqualByComparingTo("10.00");
        assertThat(contarConsumos(linea)).isZero();
        assertThat(salidas(mp1)).isEmpty();
        assertThat(salidas(mp2)).isEmpty();
        assertThat(salidas(mp3)).isEmpty();
    }

    @Test
    @DisplayName("CA-04/CP-03: tres ingredientes registrados por separado bajan cada inventario")
    void tresIngredientesBajanCadaInventario() {
        var mp1 = sembrarMateriaPrima("5.00", "100.00");
        var mp2 = sembrarMateriaPrima("5.00", "80.00");
        var mp3 = sembrarMateriaPrima("5.00", "60.00");
        var linea = sembrarLinea();

        consumoService.registrar(linea, new RegistrarConsumoRequest(mp1, new BigDecimal("10.000")));
        consumoService.registrar(linea, new RegistrarConsumoRequest(mp2, new BigDecimal("20.000")));
        consumoService.registrar(linea, new RegistrarConsumoRequest(mp3, new BigDecimal("30.000")));

        assertThat(saldo(mp1)).isEqualByComparingTo("90.00");
        assertThat(saldo(mp2)).isEqualByComparingTo("60.00");
        assertThat(saldo(mp3)).isEqualByComparingTo("30.00");
        assertThat(contarConsumos(linea)).isEqualTo(3);
    }

    @Test
    @DisplayName("CA-04/CP-12/CP-13: un fallo posterior al descuento deja saldo, kardex y consumo intactos")
    void unFalloPosteriorAlDescuentoRevierteTodo() throws InterruptedException {
        var mp = sembrarMateriaPrima("50.00", "112.50");
        var linea = sembrarLinea();
        var fechaAntes = fechaActualizacion(mp);
        Thread.sleep(1_200);
        // buscarPorId se ejecuta justo después de registrarSalida: el descuento y el movimiento
        // ya están escritos en la transacción cuando la excepción sale.
        doThrow(new IllegalStateException("fallo simulado tras el descuento"))
                .when(consumoRepository).buscarPorId(anyInt());

        assertThatThrownBy(() -> consumoService.registrar(linea,
                new RegistrarConsumoRequest(mp, new BigDecimal("16.200"))))
                // El proxy de repositorio traduce la excepción; lo que importa es que sale.
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("fallo simulado");

        assertThat(saldo(mp)).isEqualByComparingTo("112.50");
        assertThat(fechaActualizacion(mp)).isEqualTo(fechaAntes);
        assertThat(salidas(mp)).isEmpty();
        assertThat(contarConsumos(linea)).isZero();
        // CP-13: ningún movimiento de consumo sin su consumo (el cierre lo exige, aquí explícito).
        assertThat(movimientosHuerfanos()).isEmpty();
    }

    // ------------------------------------------------------------------------------------
    // Concurrencia
    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName("CP-14/CP-15: dos consumos simultáneos con stock para uno: uno OK y otro STOCK_INSUFICIENTE (10 iteraciones)")
    void dosConsumosSimultaneosConStockParaUnoSoloUnoPasa() throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        try {
            for (int iteracion = 1; iteracion <= 10; iteracion++) {
                var mp = sembrarMateriaPrima("1.00", "10.00");
                var lineaA = sembrarLinea();
                var lineaB = sembrarLinea();
                var listos = new CountDownLatch(2);
                var salida = new CountDownLatch(1);

                var tareaA = pool.submit(() -> consumirEnParalelo(listos, salida, lineaA, mp));
                var tareaB = pool.submit(() -> consumirEnParalelo(listos, salida, lineaB, mp));
                assertThat(listos.await(10, TimeUnit.SECONDS)).isTrue();
                salida.countDown();

                var resultados = List.of(resultado(tareaA), resultado(tareaB));
                var correctos = resultados.stream().filter("OK"::equals).count();
                var rechazados = resultados.stream()
                        .filter("STOCK_INSUFICIENTE"::equals).count();
                assertThat(correctos).as("iteración %d: %s", iteracion, resultados).isEqualTo(1);
                assertThat(rechazados).as("iteración %d: %s", iteracion, resultados).isEqualTo(1);

                assertThat(saldo(mp)).as("iteración %d", iteracion).isEqualByComparingTo("2.00");
                assertThat(contarConsumos(lineaA) + contarConsumos(lineaB)).isEqualTo(1);
                assertThat(salidas(mp)).hasSize(1);
                assertThat(saldosNegativos()).isEmpty();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private String consumirEnParalelo(CountDownLatch listos, CountDownLatch salida,
                                      Integer linea, Integer mp) throws InterruptedException {
        listos.countDown();
        salida.await();
        try {
            consumoService.registrar(linea, new RegistrarConsumoRequest(mp, new BigDecimal("8.000")));
            return "OK";
        } catch (ConflictoNegocioException ex) {
            return ex.getCodigo();
        }
    }

    private static String resultado(Future<String> tarea) throws Exception {
        return tarea.get(30, TimeUnit.SECONDS);
    }

    // ------------------------------------------------------------------------------------
    // Redondeo, conciliación y corrección
    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName("CP-19: 10 consumos de 0.005 suman 0.10 en el saldo y en el kardex, sin deriva")
    void diezConsumosDeCincoMilesimasNoDerivan() {
        // Criterio: cada consumo redondea por separado HALF_UP a 0.01 (R-08), así que 0.005 baja
        // 0.01 y diez consumos bajan 0.10 (no 0.05). No hay deriva cuando el saldo, la suma de
        // los movimientos del kardex y el último saldo_posterior coinciden con ese total.
        var mp = sembrarMateriaPrima("1.00", "50.00");

        for (int i = 0; i < 10; i++) {
            var linea = sembrarLinea();
            consumoService.registrar(linea,
                    new RegistrarConsumoRequest(mp, new BigDecimal("0.005")));
        }

        assertThat(saldo(mp)).isEqualByComparingTo("49.90");
        var movimientos = salidas(mp);
        assertThat(movimientos).hasSize(10);
        assertThat(movimientos).allSatisfy(
                m -> assertThat(m.cantidad()).isEqualByComparingTo("0.010"));
        var descontado = movimientos.stream().map(Movimiento::cantidad)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(descontado).isEqualByComparingTo("0.100");
        assertThat(movimientos.get(9).saldoPosterior()).isEqualByComparingTo(saldo(mp));
    }

    @Test
    @DisplayName("CP-21: tras registrar, corregir y fallar, la conciliación kardex-saldo devuelve 0 filas")
    void laConciliacionDevuelveCeroFilas() {
        var mp1 = sembrarMateriaPrima("5.00", "100.00");
        var mp2 = sembrarMateriaPrima("5.00", "10.00");
        var linea = sembrarLinea();

        var primero = consumoService.registrar(linea,
                new RegistrarConsumoRequest(mp1, new BigDecimal("15.000")));
        consumoService.actualizar(primero.idConsumo(),
                new ActualizarConsumoRequest(new BigDecimal("12.000")));
        consumoService.actualizar(primero.idConsumo(),
                new ActualizarConsumoRequest(new BigDecimal("18.000")));
        assertThatThrownBy(() -> consumoService.registrar(linea,
                new RegistrarConsumoRequest(mp2, new BigDecimal("99.000"))))
                .isInstanceOf(ConflictoNegocioException.class);

        assertThat(conciliacion()).isEmpty();
        assertThat(saldo(mp1)).isEqualByComparingTo("82.00");
        assertThat(saldo(mp2)).isEqualByComparingTo("10.00");
    }

    @Test
    @DisplayName("CP-22: corregir de 15 a 18 descuenta 3 más con un solo movimiento adicional")
    void corregirAlAlzaDescuentaLaDiferencia() {
        var mp = sembrarMateriaPrima("5.00", "100.00");
        var linea = sembrarLinea();
        var registrado = consumoService.registrar(linea,
                new RegistrarConsumoRequest(mp, new BigDecimal("15.000")));
        assertThat(saldo(mp)).isEqualByComparingTo("85.00");

        var corregido = consumoService.actualizar(registrado.idConsumo(),
                new ActualizarConsumoRequest(new BigDecimal("18.000")));

        assertThat(saldo(mp)).isEqualByComparingTo("82.00");
        assertThat(corregido.inventario().anterior()).isEqualByComparingTo("85.00");
        assertThat(corregido.inventario().posterior()).isEqualByComparingTo("82.00");
        var movimientos = salidas(mp);
        assertThat(movimientos).hasSize(2);
        assertThat(movimientos.get(1).cantidad()).isEqualByComparingTo("3.000");
        assertThat(movimientos.get(1).saldoAnterior()).isEqualByComparingTo("85.00");
        assertThat(movimientos.get(1).saldoPosterior()).isEqualByComparingTo("82.00");
        assertThat(movimientos.get(1).idOrigen()).isEqualTo(registrado.idConsumo());
        assertThat(cantidadRealDe(linea, mp)).isEqualByComparingTo("18.000");
    }

    // ------------------------------------------------------------------------------------
    // Consultas de verificación (tarea-qa §3), limitadas a lo sembrado por la prueba
    // ------------------------------------------------------------------------------------

    private List<Object> conciliacion() {
        return jdbc.query("""
                SELECT i.id_materia_prima, i.cantidad_disponible AS saldo_inventario,
                       m.saldo_posterior AS saldo_kardex
                FROM inventario i
                LEFT JOIN (
                    SELECT id_materia_prima, saldo_posterior,
                           ROW_NUMBER() OVER (PARTITION BY id_materia_prima
                                              ORDER BY id_movimiento DESC) rn
                    FROM movimientos_inventario_mp
                    WHERE id_materia_prima IN (:ids)
                ) m ON m.id_materia_prima = i.id_materia_prima AND m.rn = 1
                WHERE i.id_materia_prima IN (:ids)
                  AND i.cantidad_disponible <> COALESCE(m.saldo_posterior, 0)
                """, new MapSqlParameterSource().addValue("ids", idsMateriaPrima),
                (rs, fila) -> (Object) ("MP " + rs.getInt("id_materia_prima") + " saldo="
                        + rs.getBigDecimal("saldo_inventario") + " kardex="
                        + rs.getBigDecimal("saldo_kardex")));
    }

    private List<Object> saldosNegativos() {
        return jdbc.query("""
                SELECT id_materia_prima, cantidad_disponible FROM inventario
                WHERE id_materia_prima IN (:ids) AND cantidad_disponible < 0
                """, new MapSqlParameterSource().addValue("ids", idsMateriaPrima),
                (rs, fila) -> (Object) ("MP " + rs.getInt(1) + " saldo=" + rs.getBigDecimal(2)));
    }

    private List<Object> movimientosHuerfanos() {
        return jdbc.query("""
                SELECT m.id_movimiento, m.id_origen
                FROM movimientos_inventario_mp m
                LEFT JOIN consumo_materia_prima c ON c.id_consumo = m.id_origen
                WHERE m.tabla_origen = 'consumo_materia_prima' AND c.id_consumo IS NULL
                  AND m.id_materia_prima IN (:ids)
                """, new MapSqlParameterSource().addValue("ids", idsMateriaPrima),
                (rs, fila) -> (Object) ("movimiento " + rs.getLong(1) + " origen "
                        + rs.getInt(2)));
    }

    // ------------------------------------------------------------------------------------
    // Siembra y lecturas
    // ------------------------------------------------------------------------------------

    /**
     * Siembra una materia prima con su fila de inventario y la {@code ENTRADA_COMPRA} que explica
     * el saldo inicial, para que la conciliación parta de un kardex coherente.
     */
    private Integer sembrarMateriaPrima(String stockMinimo, String saldoInicial) {
        var id = insertarConId("""
                INSERT INTO materias_primas (nombre, id_unidad, stock_minimo, costo_unitario, estado)
                VALUES (:nombre, :idUnidad, :minimo, 2.50, 1)
                """, new MapSqlParameterSource()
                        .addValue("nombre", "MP consumo " + UUID.randomUUID() + sufijo)
                        .addValue("idUnidad", idUnidad)
                        .addValue("minimo", new BigDecimal(stockMinimo)));
        idsMateriaPrima.add(id);
        jdbc.update("""
                INSERT INTO inventario (id_materia_prima, cantidad_disponible)
                VALUES (:id, :saldo)
                """, new MapSqlParameterSource().addValue("id", id)
                        .addValue("saldo", new BigDecimal(saldoInicial)));
        jdbc.update("""
                INSERT INTO movimientos_inventario_mp
                    (id_materia_prima, tipo_movimiento, cantidad, saldo_anterior, saldo_posterior,
                     observacion)
                VALUES (:id, 'ENTRADA_COMPRA', :saldo, 0.00, :saldo, 'Saldo inicial de la prueba')
                """, new MapSqlParameterSource().addValue("id", id)
                        .addValue("saldo", new BigDecimal(saldoInicial)));
        return id;
    }

    /** Siembra un producto propio y su línea en el plan {@code EN_PROCESO} de la prueba. */
    private Integer sembrarLinea() {
        var idProducto = insertarConId("""
                INSERT INTO productos (nombre, precio, id_unidad, estado)
                VALUES (:nombre, 100.00, :idUnidad, 1)
                """, new MapSqlParameterSource()
                        .addValue("nombre", "Producto consumo " + UUID.randomUUID() + sufijo)
                        .addValue("idUnidad", idUnidad));
        idsProducto.add(idProducto);
        var idDetalle = insertarConId("""
                INSERT INTO detalle_plan_produccion (id_plan, id_producto, cantidad_planificada)
                VALUES (:idPlan, :idProducto, 10.00)
                """, new MapSqlParameterSource()
                        .addValue("idPlan", idPlan)
                        .addValue("idProducto", idProducto));
        idsDetalle.add(idDetalle);
        return idDetalle;
    }

    private BigDecimal saldo(Integer idMateriaPrima) {
        return jdbc.queryForObject("""
                SELECT cantidad_disponible FROM inventario WHERE id_materia_prima = :id
                """, new MapSqlParameterSource().addValue("id", idMateriaPrima), BigDecimal.class);
    }

    private LocalDateTime fechaActualizacion(Integer idMateriaPrima) {
        return jdbc.queryForObject("""
                SELECT fecha_actualizacion FROM inventario WHERE id_materia_prima = :id
                """, new MapSqlParameterSource().addValue("id", idMateriaPrima),
                LocalDateTime.class);
    }

    private BigDecimal cantidadRealDe(Integer idDetalle, Integer idMateriaPrima) {
        return jdbc.queryForObject("""
                SELECT cantidad_real FROM consumo_materia_prima
                WHERE id_detalle_plan = :idDetalle AND id_materia_prima = :idMp
                """, new MapSqlParameterSource().addValue("idDetalle", idDetalle)
                        .addValue("idMp", idMateriaPrima), BigDecimal.class);
    }

    private int contarConsumos(Integer idDetalle) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM consumo_materia_prima WHERE id_detalle_plan = :id
                """, new MapSqlParameterSource().addValue("id", idDetalle), Integer.class);
    }

    /** Movimientos de producción de la materia prima, del más antiguo al más reciente. */
    private List<Movimiento> salidas(Integer idMateriaPrima) {
        return jdbc.query("""
                SELECT tipo_movimiento, cantidad, saldo_anterior, saldo_posterior, tabla_origen,
                       id_origen
                FROM movimientos_inventario_mp
                WHERE id_materia_prima = :id AND tabla_origen = 'consumo_materia_prima'
                ORDER BY id_movimiento ASC
                """, new MapSqlParameterSource().addValue("id", idMateriaPrima),
                (rs, fila) -> new Movimiento(rs.getString("tipo_movimiento"),
                        rs.getBigDecimal("cantidad"), rs.getBigDecimal("saldo_anterior"),
                        rs.getBigDecimal("saldo_posterior"), rs.getString("tabla_origen"),
                        rs.getInt("id_origen")));
    }

    private Integer insertarConId(String sql, MapSqlParameterSource params) {
        var generadas = new GeneratedKeyHolder();
        jdbc.update(sql, params, generadas);
        Number clave = generadas.getKey();
        if (clave == null) {
            throw new IllegalStateException("La base no devolvió el id del fixture insertado.");
        }
        return clave.intValue();
    }

    private record Movimiento(String tipo, BigDecimal cantidad, BigDecimal saldoAnterior,
                              BigDecimal saldoPosterior, String tablaOrigen, Integer idOrigen) {
    }
}
