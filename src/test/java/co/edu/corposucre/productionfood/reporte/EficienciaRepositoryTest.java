package co.edu.corposucre.productionfood.reporte;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.GeneratedKeyHolder;

/**
 * Pruebas de integración de {@link EficienciaRepository} contra MySQL (HU-26).
 *
 * <p>Como las del dashboard, usan la base del {@code docker-compose}, se omiten con
 * {@code assumeTrue} si no responde y eliminan únicamente lo que siembran (sufijo único). Todos
 * los planes se siembran en marzo de 2099 para que los rangos de fechas aíslen los datos de la
 * prueba de los que ya haya en la base compartida.
 */
class EficienciaRepositoryTest {

    private static final String URL = "jdbc:mysql://"
            + entorno("DB_HOST", "127.0.0.1") + ":" + entorno("DB_PORT", "3306")
            + "/" + entorno("DB_NAME", "productionfood")
            + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=America/Bogota";
    private static final String USUARIO = entorno("DB_USER", "root");
    private static final String CLAVE = entorno("DB_PASSWORD", "local");

    private static final LocalDate D9 = LocalDate.of(2099, 3, 9);
    private static final LocalDate D10 = LocalDate.of(2099, 3, 10);
    private static final LocalDate D11 = LocalDate.of(2099, 3, 11);
    private static final LocalDate DESDE = LocalDate.of(2099, 3, 1);
    private static final LocalDate HASTA = LocalDate.of(2099, 3, 31);

    private NamedParameterJdbcTemplate jdbc;
    private EficienciaRepository repository;

    private String sufijo;
    private Integer idUnidad;
    private Integer idUsuario;
    private String nombreUsuario;
    private Integer idProducto;
    private Integer idMateriaPrima;
    private final List<Integer> idsPlan = new ArrayList<>();
    private final List<Integer> idsDetalle = new ArrayList<>();
    private final List<Integer> idsProducto = new ArrayList<>();
    private final List<Integer> idsReceta = new ArrayList<>();
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
            sentencia.executeQuery("SELECT cantidad_real FROM consumo_materia_prima LIMIT 1").close();
            sentencia.executeQuery("SELECT 1 FROM recetas LIMIT 1").close();
            disponible = true;
        } catch (SQLException e) {
            disponible = false;
        }
        assumeTrue(disponible, "MySQL no responde en " + URL + " con el esquema; se omite.");
    }

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource(URL, USUARIO, CLAVE);
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        jdbc = new NamedParameterJdbcTemplate(dataSource);
        repository = new EficienciaRepository(jdbc);

        sufijo = "HU26-" + UUID.randomUUID().toString().substring(0, 8);
        var usuario = jdbc.getJdbcTemplate()
                .queryForList("SELECT id_usuario, nombre FROM usuarios LIMIT 1");
        assumeTrue(!usuario.isEmpty(), "No hay usuarios para sembrar planes; se omite.");
        idUsuario = ((Number) usuario.get(0).get("id_usuario")).intValue();
        nombreUsuario = (String) usuario.get(0).get("nombre");
        idUnidad = insertar("INSERT INTO unidades_medida (nombre, abreviatura) VALUES (:n, :a)",
                "n", "Unidad " + sufijo, "a", sufijo.substring(5));
        idProducto = sembrarProducto("Producto");
        idMateriaPrima = sembrarMateriaPrima("MP");
    }

    @AfterEach
    void tearDown() {
        borrarIn("consumo_materia_prima", "id_detalle_plan", idsDetalle);
        borrarIn("detalle_plan_produccion", "id_plan", idsPlan);
        borrarIn("planes_produccion", "id_plan", idsPlan);
        borrarIn("recetas", "id_receta", idsReceta);
        borrarIn("productos", "id_producto", idsProducto);
        borrarIn("materias_primas", "id_materia_prima", idsMateriaPrima);
        if (idUnidad != null) {
            jdbc.update("DELETE FROM unidades_medida WHERE id_unidad = :id",
                    new MapSqlParameterSource("id", idUnidad));
        }
    }

    @Test
    @DisplayName("El filtro de fechas es inclusivo en ambos extremos y opcional en cada uno")
    void filtroDeFechas() {
        var p9 = sembrarPlanConConsumo(D9, "COMPLETADO", "10.00", "10.00", "5.000", "5.000");
        var p10 = sembrarPlanConConsumo(D10, "COMPLETADO", "10.00", "10.00", "5.000", "5.000");
        var p11 = sembrarPlanConConsumo(D11, "COMPLETADO", "10.00", "10.00", "5.000", "5.000");

        assertThat(ids(repository.planesConConsumo(D10, D10))).containsExactly(p10);
        assertThat(ids(repository.planesConConsumo(D10, D11))).containsExactly(p11, p10);
        assertThat(ids(repository.planesConConsumo(D9, D10))).containsExactly(p10, p9);
        assertThat(ids(repository.planesConConsumo(D10, null))).contains(p10, p11)
                .doesNotContain(p9);
        assertThat(ids(repository.planesConConsumo(null, D10))).contains(p9, p10)
                .doesNotContain(p11);
        assertThat(ids(repository.planesConConsumo(null, null))).contains(p9, p10, p11);
        assertThat(ids(repository.planesConConsumo(D11.plusDays(1), HASTA))).isEmpty();
    }

    @Test
    @DisplayName("La fila del plan trae fecha, estado y el nombre del responsable")
    void filaDelPlan() {
        var id = sembrarPlanConConsumo(D10, "EN_PROCESO", "10.00", "4.00", "5.000", "2.000");

        var fila = repository.planesConConsumo(D10, D10).get(0);

        assertThat(fila).isEqualTo(new PlanEficienciaFila(id, D10, "EN_PROCESO", nombreUsuario));
    }

    @Test
    @DisplayName("R-05: un plan PLANIFICADO sin consumos no se devuelve")
    void planPlanificadoSinConsumosQuedaFuera() {
        var sinConsumos = sembrarPlan(D10, "PLANIFICADO");
        sembrarLinea(sinConsumos, idProducto, "10.00", "0.00");
        var conConsumo = sembrarPlanConConsumo(D10, "EN_PROCESO", "10.00", "5.00", "5.000", "2.000");

        assertThat(ids(repository.planesConConsumo(D10, D10)))
                .containsExactly(conConsumo).doesNotContain(sinConsumos);
    }

    @Test
    @DisplayName("R-05: un consumo con real NULL no cuenta como registrado")
    void consumoConRealNuloNoCuenta() {
        var nullable = jdbc.getJdbcTemplate().queryForObject("""
                SELECT is_nullable FROM information_schema.columns
                WHERE table_schema = database() AND table_name = 'consumo_materia_prima'
                  AND column_name = 'cantidad_real'
                """, String.class);
        assumeTrue("YES".equals(nullable),
                "cantidad_real es NOT NULL desde V2 (C-08): no se puede sembrar un real nulo.");

        var soloNulo = sembrarPlan(D10, "EN_PROCESO");
        var detalleNulo = sembrarLinea(soloNulo, idProducto, "10.00", "5.00");
        sembrarConsumo(detalleNulo, idMateriaPrima, "5.000", null);
        var mixto = sembrarPlan(D10, "EN_PROCESO");
        var detalleMixto = sembrarLinea(mixto, idProducto, "10.00", "5.00");
        sembrarConsumo(detalleMixto, idMateriaPrima, "5.000", null);
        var otraMp = sembrarMateriaPrima("MP2");
        sembrarConsumo(detalleMixto, otraMp, "1.000", "1.500");

        assertThat(ids(repository.planesConConsumo(D10, D10))).containsExactly(mixto);
        var consumos = repository.consumosDePlanes(List.of(soloNulo, mixto));
        assertThat(consumos).hasSize(1);
        assertThat(consumos.get(0).materiaPrima()).isEqualTo("MP2 " + sufijo);
        assertThat(repository.materiasPrimasAcumuladas(D10, D10))
                .extracting(MateriaPrimaAcumuladaFila::materiaPrima)
                .containsExactly("MP2 " + sufijo);
    }

    @Test
    @DisplayName("Un plan cancelado con consumo se devuelve con su estado")
    void planCanceladoSeDevuelveConSuEstado() {
        var id = sembrarPlanConConsumo(D10, "CANCELADO", "10.00", "0.00", "5.000", "0.000");

        var filas = repository.planesConConsumo(D10, D10);

        assertThat(filas).extracting(PlanEficienciaFila::idPlan).containsExactly(id);
        assertThat(filas.get(0).estado()).isEqualTo("CANCELADO");
    }

    @Test
    @DisplayName("Productos y consumos de un plan traen planificada y producida de su línea")
    void productosYConsumosDePlanes() {
        var plan = sembrarPlan(D10, "COMPLETADO");
        var otroProducto = sembrarProducto("Otro");
        var detalle1 = sembrarLinea(plan, idProducto, "90.00", "60.00");
        var detalle2 = sembrarLinea(plan, otroProducto, "20.00", "25.00");
        sembrarConsumo(detalle1, idMateriaPrima, "15.000", "16.200");
        sembrarConsumo(detalle2, idMateriaPrima, "0.300", "0.000");

        var productos = repository.productosDePlanes(List.of(plan));
        var consumos = repository.consumosDePlanes(List.of(plan));

        assertThat(productos).containsExactly(
                new ProductoPlanFila(plan, "Producto " + sufijo, new BigDecimal("90.00"),
                        new BigDecimal("60.00")),
                new ProductoPlanFila(plan, "Otro " + sufijo, new BigDecimal("20.00"),
                        new BigDecimal("25.00")));
        assertThat(consumos).hasSize(2);
        var primero = consumos.get(0);
        assertThat(primero.idPlan()).isEqualTo(plan);
        assertThat(primero.materiaPrima()).isEqualTo("MP " + sufijo);
        assertThat(primero.unidad()).isEqualTo(sufijo.substring(5));
        assertThat(primero.teorica()).isEqualByComparingTo("15.000");
        assertThat(primero.real()).isEqualByComparingTo("16.200");
        assertThat(primero.planificada()).isEqualByComparingTo("90.00");
        assertThat(primero.producida()).isEqualByComparingTo("60.00");
        var segundo = consumos.get(1);
        assertThat(segundo.real()).isEqualByComparingTo("0.000");
        assertThat(segundo.planificada()).isEqualByComparingTo("20.00");
        assertThat(segundo.producida()).isEqualByComparingTo("25.00");
        assertThat(repository.productosDePlanes(List.of())).isEmpty();
        assertThat(repository.consumosDePlanes(List.of())).isEmpty();
    }

    @Test
    @DisplayName("R-06: acumulado por materia prima con SUM, COUNT DISTINCT, solo COMPLETADO")
    void acumuladoPorMateriaPrima() {
        var otroProducto = sembrarProducto("Otro");
        var plan1 = sembrarPlan(D9, "COMPLETADO");
        sembrarConsumo(sembrarLinea(plan1, idProducto, "10.00", "10.00"), idMateriaPrima,
                "10.000", "11.000");
        sembrarConsumo(sembrarLinea(plan1, otroProducto, "10.00", "10.00"), idMateriaPrima,
                "2.500", "2.000");
        var plan2 = sembrarPlan(D10, "EN_PROCESO");
        sembrarConsumo(sembrarLinea(plan2, idProducto, "10.00", "5.00"), idMateriaPrima,
                "7.000", "0.000");
        var cancelado = sembrarPlan(D11, "CANCELADO");
        sembrarConsumo(sembrarLinea(cancelado, idProducto, "10.00", "0.00"), idMateriaPrima,
                "100.000", "100.000");

        var filas = repository.materiasPrimasAcumuladas(DESDE, HASTA).stream()
                .filter(f -> f.materiaPrima().equals("MP " + sufijo)).toList();

        assertThat(filas).hasSize(1);
        var fila = filas.get(0);
        assertThat(fila.unidad()).isEqualTo(sufijo.substring(5));
        assertThat(fila.teorica()).isEqualByComparingTo("12.500");
        assertThat(fila.real()).isEqualByComparingTo("13.000");
        assertThat(fila.planes()).isEqualTo(1);
        assertThat(repository.materiasPrimasAcumuladas(D10, D11)).isEmpty();
    }

    @Test
    @DisplayName("R-07: producto sin receta activa se lista; con receta activa no; cancelados no cuentan")
    void productosSinReceta() {
        var conReceta = sembrarProducto("Con receta");
        sembrarReceta(conReceta, 1);
        var recetaInactiva = sembrarProducto("Receta inactiva");
        sembrarReceta(recetaInactiva, 0);
        var soloCancelado = sembrarProducto("Solo cancelado");
        var plan1 = sembrarPlan(D9, "COMPLETADO");
        var plan2 = sembrarPlan(D10, "PLANIFICADO");
        var plan3 = sembrarPlan(D10, "CANCELADO");
        for (var producto : List.of(idProducto, conReceta, recetaInactiva)) {
            sembrarLinea(plan1, producto, "10.00", "10.00");
        }
        sembrarLinea(plan2, idProducto, "10.00", "0.00");
        sembrarLinea(plan3, idProducto, "10.00", "0.00");
        sembrarLinea(plan3, soloCancelado, "10.00", "0.00");

        var filas = repository.productosSinReceta(DESDE, HASTA);

        assertThat(filas).containsExactly(
                new SinRecetaFila("Producto " + sufijo, 2),
                new SinRecetaFila("Receta inactiva " + sufijo, 1));
        assertThat(repository.productosSinReceta(D10, D10)).containsExactly(
                new SinRecetaFila("Producto " + sufijo, 1));
    }

    @Test
    @DisplayName("Plan inexistente y plan sin consumos se distinguen")
    void planInexistenteVsPlanSinConsumos() {
        var sinConsumos = sembrarPlan(D10, "PLANIFICADO");
        sembrarLinea(sinConsumos, idProducto, "10.00", "0.00");
        var conConsumo = sembrarPlanConConsumo(D10, "EN_PROCESO", "10.00", "5.00", "5.000", "2.000");

        assertThat(repository.plan(Integer.MAX_VALUE)).isEmpty();
        assertThat(repository.plan(sinConsumos)).isPresent();
        assertThat(repository.consumosDePlanes(List.of(sinConsumos))).isEmpty();
        assertThat(repository.plan(conConsumo)).isPresent();
        assertThat(repository.consumosDePlanes(List.of(conConsumo))).hasSize(1);
    }

    @Test
    @DisplayName("La consulta de planes con rango de fechas usa idx_planes_fecha")
    void laConsultaDePlanesUsaElIndiceDeFechas() {
        sembrarPlanConConsumo(D10, "COMPLETADO", "10.00", "10.00", "5.000", "5.000");

        var plan = jdbc.getJdbcTemplate().queryForList("""
                EXPLAIN SELECT p.id_plan, p.fecha_produccion, p.estado, u.nombre
                FROM planes_produccion p
                JOIN usuarios u ON u.id_usuario = p.id_usuario
                WHERE EXISTS (SELECT 1 FROM detalle_plan_produccion d
                              JOIN consumo_materia_prima c ON c.id_detalle_plan = d.id_detalle_plan
                              WHERE d.id_plan = p.id_plan AND c.cantidad_real IS NOT NULL)
                  AND p.fecha_produccion >= '2099-03-01' AND p.fecha_produccion <= '2099-03-31'
                ORDER BY p.fecha_produccion DESC, p.id_plan DESC
                """);

        assertThat(plan).anySatisfy(fila -> {
            assertThat(fila.get("table")).isEqualTo("p");
            assertThat(String.valueOf(fila.get("possible_keys"))).contains("idx_planes_fecha");
            assertThat(String.valueOf(fila.get("key"))).isEqualTo("idx_planes_fecha");
        });
    }

    private List<Integer> ids(List<PlanEficienciaFila> filas) {
        return filas.stream().map(PlanEficienciaFila::idPlan).toList();
    }

    private Integer sembrarPlan(LocalDate fecha, String estado) {
        var id = insertar("INSERT INTO planes_produccion (fecha_planificacion, fecha_produccion, "
                + "estado, id_usuario) VALUES (:f, :f, :e, :u)",
                "f", fecha, "e", estado, "u", idUsuario);
        idsPlan.add(id);
        return id;
    }

    private Integer sembrarLinea(Integer plan, Integer producto, String planificada,
                                 String producida) {
        var id = insertar("INSERT INTO detalle_plan_produccion (id_plan, id_producto, "
                + "cantidad_planificada, cantidad_producida) VALUES (:p, :pr, :pl, :pd)",
                "p", plan, "pr", producto, "pl", new BigDecimal(planificada),
                "pd", new BigDecimal(producida));
        idsDetalle.add(id);
        return id;
    }

    private void sembrarConsumo(Integer detalle, Integer materiaPrima, String teorica, String real) {
        jdbc.update("INSERT INTO consumo_materia_prima (id_detalle_plan, id_materia_prima, "
                + "cantidad_teorica, cantidad_real) VALUES (:d, :m, :t, :r)",
                new MapSqlParameterSource().addValue("d", detalle).addValue("m", materiaPrima)
                        .addValue("t", new BigDecimal(teorica))
                        .addValue("r", real == null ? null : new BigDecimal(real)));
    }

    private Integer sembrarPlanConConsumo(LocalDate fecha, String estado, String planificada,
                                          String producida, String teorica, String real) {
        var plan = sembrarPlan(fecha, estado);
        var detalle = sembrarLinea(plan, idProducto, planificada, producida);
        sembrarConsumo(detalle, idMateriaPrima, teorica, real);
        return plan;
    }

    private Integer sembrarProducto(String nombre) {
        var id = insertar("INSERT INTO productos (nombre, precio, id_unidad, estado) "
                + "VALUES (:n, 10, :u, 1)", "n", nombre + " " + sufijo, "u", idUnidad);
        idsProducto.add(id);
        return id;
    }

    private Integer sembrarMateriaPrima(String nombre) {
        var id = insertar("INSERT INTO materias_primas (nombre, id_unidad, stock_minimo, "
                + "costo_unitario, estado) VALUES (:n, :u, 0, 1, 1)",
                "n", nombre + " " + sufijo, "u", idUnidad);
        idsMateriaPrima.add(id);
        return id;
    }

    private void sembrarReceta(Integer producto, int estado) {
        idsReceta.add(insertar("INSERT INTO recetas (id_producto, nombre, cantidad_producir, "
                + "id_unidad, estado) VALUES (:p, :n, 1, :u, :e)",
                "p", producto, "n", "Receta " + sufijo, "u", idUnidad, "e", estado));
    }

    private Integer insertar(String sql, Object... parametros) {
        var params = new MapSqlParameterSource();
        for (int i = 0; i < parametros.length; i += 2) {
            params.addValue((String) parametros[i], parametros[i + 1]);
        }
        var generadas = new GeneratedKeyHolder();
        jdbc.update(sql, params, generadas);
        Number clave = generadas.getKey();
        if (clave == null) {
            throw new IllegalStateException("La base no devolvió el id del fixture insertado.");
        }
        return clave.intValue();
    }

    private void borrarIn(String tabla, String columna, List<Integer> ids) {
        if (!ids.isEmpty()) {
            jdbc.update("DELETE FROM " + tabla + " WHERE " + columna + " IN (:ids)",
                    new MapSqlParameterSource().addValue("ids", ids));
        }
    }
}
