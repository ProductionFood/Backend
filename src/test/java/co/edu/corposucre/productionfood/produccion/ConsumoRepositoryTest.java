package co.edu.corposucre.productionfood.produccion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import co.edu.corposucre.productionfood.common.pagination.PageRequest;

/**
 * Pruebas de integración de {@link ConsumoRepository} contra MySQL (HU-22).
 *
 * <p>El proyecto no usa Testcontainers ni H2: la única base soportada es la del
 * {@code docker-compose}. La prueba se conecta con los mismos parámetros que
 * {@code application.yml} (variables de entorno con los mismos valores por defecto) y se omite
 * con {@code assumeTrue} si la base o la tabla no están disponibles, para no fallar en un
 * entorno sin el contenedor levantado.
 *
 * <p>Cada prueba crea su propia unidad, sus productos, su receta, sus materias primas, su
 * usuario, su plan y sus líneas, y los elimina al terminar en orden inverso al de las llaves
 * foráneas: los consumos antes que el detalle, el detalle antes que la cabecera, las recetas
 * antes que los productos, y los productos, las materias primas y el plan antes que la unidad y
 * el usuario.
 *
 * <p>La lista blanca {@link #ORDEN} es propia de la prueba, con los mismos valores que usa el
 * repositorio, para que el orden se ejerza por el camino público de {@link PageRequest#of}.
 */
class ConsumoRepositoryTest {

    private static final String URL = "jdbc:mysql://"
            + entorno("DB_HOST", "127.0.0.1") + ":" + entorno("DB_PORT", "3306")
            + "/" + entorno("DB_NAME", "productionfood")
            + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=America/Bogota";
    private static final String USUARIO = entorno("DB_USER", "root");
    private static final String CLAVE = entorno("DB_PASSWORD", "local");

    private static final Map<String, String> ORDEN = Map.of(
            "producto", "p.nombre",
            "materiaPrima", "mp.nombre",
            "cantidadTeorica", "c.cantidad_teorica",
            "cantidadReal", "c.cantidad_real",
            "fechaRegistro", "c.fecha_registro");
    private static final String ORDEN_POR_DEFECTO = "producto,asc";

    private NamedParameterJdbcTemplate jdbc;
    private ConsumoRepository repository;
    private TransactionTemplate transacciones;

    private String sufijo;
    private Integer idUnidad;
    private final List<Integer> idsProducto = new ArrayList<>();
    private final List<Integer> idsReceta = new ArrayList<>();
    private final List<Integer> idsMateriaPrima = new ArrayList<>();
    private Integer idUsuario;
    private final List<Integer> idsPlan = new ArrayList<>();
    private final List<Integer> idsDetalle = new ArrayList<>();

    private static String entorno(String nombre, String defecto) {
        String valor = System.getenv(nombre);
        return valor == null || valor.isBlank() ? defecto : valor;
    }

    @BeforeAll
    static void laBaseDelProyectoEstaDisponible() {
        boolean disponible;
        try (var conexion = java.sql.DriverManager.getConnection(URL, USUARIO, CLAVE);
             var sentencia = conexion.createStatement()) {
            sentencia.executeQuery("SELECT 1 FROM consumo_materia_prima LIMIT 1").close();
            disponible = true;
        } catch (SQLException e) {
            disponible = false;
        }
        assumeTrue(disponible,
                "MySQL no responde en " + URL + " con la tabla consumo_materia_prima; se omite.");
    }

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource(URL, USUARIO, CLAVE);
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        jdbc = new NamedParameterJdbcTemplate(dataSource);
        repository = new ConsumoRepository(jdbc);
        transacciones = new TransactionTemplate(new JdbcTransactionManager(dataSource));

        // Sufijo único por prueba: los nombres y abreviaturas tienen índice en la base y una
        // corrida anterior que deje datos no debe confundir las lecturas del JOIN.
        sufijo = "-" + UUID.randomUUID().toString().substring(0, 8);
        idUnidad = insertarConId("""
                INSERT INTO unidades_medida (nombre, abreviatura)
                VALUES (:nombre, :abreviatura)
                """, new MapSqlParameterSource()
                        .addValue("nombre", "Unidad HU-22 test" + sufijo)
                        .addValue("abreviatura", "u22" + sufijo));
        // Tres productos y tres materias primas para poder comprobar los tres campos de orden con
        // holgura; la primera materia prima la comparte la receta del primer producto.
        idsProducto.add(insertarProducto("Producto A HU-22 test" + sufijo));
        idsProducto.add(insertarProducto("Producto B HU-22 test" + sufijo));
        idsProducto.add(insertarProducto("Producto C HU-22 test" + sufijo));
        idsMateriaPrima.add(insertarMateriaPrima("Materia prima A HU-22 test" + sufijo));
        idsMateriaPrima.add(insertarMateriaPrima("Materia prima B HU-22 test" + sufijo));
        idsMateriaPrima.add(insertarMateriaPrima("Materia prima C HU-22 test" + sufijo));
        idUsuario = insertarUsuario("Responsable HU-22 test" + sufijo);
        idsReceta.add(insertarReceta(idsProducto.get(0)));
    }

    @AfterEach
    void tearDown() {
        // El consumo primero que el detalle, el detalle primero que la cabecera.
        if (!idsDetalle.isEmpty()) {
            jdbc.update("DELETE FROM consumo_materia_prima WHERE id_detalle_plan IN (:ids)",
                    new MapSqlParameterSource().addValue("ids", idsDetalle));
        }
        if (!idsPlan.isEmpty()) {
            jdbc.update("DELETE FROM detalle_plan_produccion WHERE id_plan IN (:ids)",
                    new MapSqlParameterSource().addValue("ids", idsPlan));
            jdbc.update("DELETE FROM planes_produccion WHERE id_plan IN (:ids)",
                    new MapSqlParameterSource().addValue("ids", idsPlan));
        }
        // La receta antes que el producto y la unidad: recetas → productos, unidades_medida.
        if (!idsReceta.isEmpty()) {
            jdbc.update("DELETE FROM recetas WHERE id_receta IN (:ids)",
                    new MapSqlParameterSource().addValue("ids", idsReceta));
        }
        if (!idsProducto.isEmpty()) {
            jdbc.update("DELETE FROM productos WHERE id_producto IN (:ids)",
                    new MapSqlParameterSource().addValue("ids", idsProducto));
        }
        // La materia prima antes que la unidad y el plan antes que el usuario.
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

    @Test
    @DisplayName("insertar devuelve el id, persiste la escala 3 y deja fecha_registro automática")
    void insertarDevuelveIdPersisteEscalaYFechaAutomatica() {
        var plan = insertarPlan();
        var detalle = insertarDetalle(plan, idsProducto.get(0), "10.00");

        var id = insertarConsumo(detalle, idsMateriaPrima.get(0), "2.345", "1.250");

        assertThat(id).isNotNull().isPositive();
        var leido = repository.buscarPorId(id).orElseThrow();
        // decimal(10,3): la base guarda y devuelve exactamente tres decimales.
        assertThat(leido.getCantidadTeorica().toPlainString()).isEqualTo("2.345");
        assertThat(leido.getCantidadReal().toPlainString()).isEqualTo("1.250");
        assertThat(leido.getFechaRegistro()).isNotNull();

        // La fecha no viaja en el INSERT: la base aplica su DEFAULT CURRENT_TIMESTAMP.
        var segundos = jdbc.queryForObject("""
                SELECT TIMESTAMPDIFF(SECOND, fecha_registro, NOW())
                FROM consumo_materia_prima WHERE id_consumo = :id
                """, new MapSqlParameterSource().addValue("id", id), Long.class);
        assertThat(segundos).isNotNull();
        assertThat(Math.abs(segundos)).isLessThanOrEqualTo(300L);
    }

    @Test
    @DisplayName("cantidad_real NOT NULL: un INSERT directo sin valor es rechazado por la base")
    void cantidadRealNulaEsRechazada() {
        var plan = insertarPlan();
        var detalle = insertarDetalle(plan, idsProducto.get(0), "10.00");

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO consumo_materia_prima
                    (id_detalle_plan, id_materia_prima, cantidad_teorica, cantidad_real)
                VALUES (:idDetalle, :idMateriaPrima, 1.000, NULL)
                """, new MapSqlParameterSource()
                        .addValue("idDetalle", detalle)
                        .addValue("idMateriaPrima", idsMateriaPrima.get(0))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("R-06: el UNIQUE (id_detalle_plan, id_materia_prima) impide duplicar el consumo")
    void elUniqueImpideDuplicarElConsumo() {
        var plan = insertarPlan();
        var detalle = insertarDetalle(plan, idsProducto.get(0), "10.00");
        insertarConsumo(detalle, idsMateriaPrima.get(0), "1.000", "1.000");

        assertThatThrownBy(() -> insertarConsumo(
                detalle, idsMateriaPrima.get(0), "2.000", "2.000"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("listarPorPlan filtra por plan, proyecta los JOIN y pagina sin repetir filas")
    void listarPorPlanFiltraProyectaYPagina() {
        var plan = insertarPlan();
        var otroPlan = insertarPlan();
        var detalleA = insertarDetalle(plan, idsProducto.get(0), "10.00");
        var detalleB = insertarDetalle(plan, idsProducto.get(1), "10.00");
        var detalleC = insertarDetalle(plan, idsProducto.get(2), "10.00");
        var detalleOtro = insertarDetalle(otroPlan, idsProducto.get(0), "10.00");

        var a = insertarConsumo(detalleA, idsMateriaPrima.get(0), "1.000", "1.000");
        var b = insertarConsumo(detalleB, idsMateriaPrima.get(1), "2.000", "2.000");
        var c = insertarConsumo(detalleC, idsMateriaPrima.get(2), "3.000", "3.000");
        insertarConsumo(detalleOtro, idsMateriaPrima.get(0), "4.000", "4.000");

        var filas = repository.listarPorPlan(plan, pagina(0, 10, "producto,asc"));

        // El consumo del otro plan no entra en la página.
        assertThat(filas).extracting(ConsumoMateriaPrima::getIdConsumo)
                .containsExactly(a, b, c);
        assertThat(filas).extracting(ConsumoMateriaPrima::getIdDetallePlan)
                .containsExactly(detalleA, detalleB, detalleC);
        assertThat(filas).extracting(ConsumoMateriaPrima::getIdPlan).containsOnly(plan);
        assertThat(filas).extracting(ConsumoMateriaPrima::getNombreProducto)
                .containsExactly("Producto A HU-22 test" + sufijo,
                        "Producto B HU-22 test" + sufijo,
                        "Producto C HU-22 test" + sufijo);
        assertThat(filas).extracting(ConsumoMateriaPrima::getNombreMateriaPrima)
                .containsExactly("Materia prima A HU-22 test" + sufijo,
                        "Materia prima B HU-22 test" + sufijo,
                        "Materia prima C HU-22 test" + sufijo);
        assertThat(filas).extracting(ConsumoMateriaPrima::getUnidad)
                .containsOnly("u22" + sufijo);
        assertThat(filas).extracting(ConsumoMateriaPrima::getEstadoPlan)
                .containsOnly(EstadoPlan.PLANIFICADO);

        var pagina0 = repository.listarPorPlan(plan, pagina(0, 2, "producto,asc"));
        var pagina1 = repository.listarPorPlan(plan, pagina(1, 2, "producto,asc"));
        var pagina2 = repository.listarPorPlan(plan, pagina(2, 2, "producto,asc"));

        assertThat(pagina0).extracting(ConsumoMateriaPrima::getIdConsumo)
                .containsExactly(a, b);
        assertThat(pagina1).extracting(ConsumoMateriaPrima::getIdConsumo)
                .containsExactly(c);
        assertThat(pagina2).isEmpty();
    }

    @Test
    @DisplayName("listarPorPlan ordena por producto, materia prima, cantidad real y fecha")
    void listarPorPlanOrdenaPorLasColumnasDeLaListaBlanca() {
        var plan = insertarPlan();
        var detalleA = insertarDetalle(plan, idsProducto.get(0), "10.00");
        var detalleB = insertarDetalle(plan, idsProducto.get(1), "10.00");
        var detalleC = insertarDetalle(plan, idsProducto.get(2), "10.00");

        var a = insertarConsumo(detalleA, idsMateriaPrima.get(0), "1.000", "3.000");
        var b = insertarConsumo(detalleB, idsMateriaPrima.get(1), "2.000", "1.000");
        var c = insertarConsumo(detalleC, idsMateriaPrima.get(2), "3.000", "2.000");
        // El DEFAULT de la columna tiene precisión de segundo: se fijan fechas distintas para
        // que el orden por fecha_registro sea determinista.
        fijarFecha(a, LocalDateTime.of(2020, 1, 3, 10, 0));
        fijarFecha(b, LocalDateTime.of(2020, 1, 1, 10, 0));
        fijarFecha(c, LocalDateTime.of(2020, 1, 2, 10, 0));

        assertThat(repository.listarPorPlan(plan, pagina(0, 10, "producto,asc")))
                .extracting(ConsumoMateriaPrima::getIdConsumo)
                .containsExactly(a, b, c);
        assertThat(repository.listarPorPlan(plan, pagina(0, 10, "producto,desc")))
                .extracting(ConsumoMateriaPrima::getIdConsumo)
                .containsExactly(c, b, a);

        assertThat(repository.listarPorPlan(plan, pagina(0, 10, "materiaPrima,asc")))
                .extracting(ConsumoMateriaPrima::getIdConsumo)
                .containsExactly(a, b, c);
        assertThat(repository.listarPorPlan(plan, pagina(0, 10, "materiaPrima,desc")))
                .extracting(ConsumoMateriaPrima::getIdConsumo)
                .containsExactly(c, b, a);

        assertThat(repository.listarPorPlan(plan, pagina(0, 10, "cantidadReal,asc")))
                .extracting(ConsumoMateriaPrima::getIdConsumo)
                .containsExactly(b, c, a);
        assertThat(repository.listarPorPlan(plan, pagina(0, 10, "cantidadReal,desc")))
                .extracting(ConsumoMateriaPrima::getIdConsumo)
                .containsExactly(a, c, b);

        assertThat(repository.listarPorPlan(plan, pagina(0, 10, "fechaRegistro,asc")))
                .extracting(ConsumoMateriaPrima::getIdConsumo)
                .containsExactly(b, c, a);
        assertThat(repository.listarPorPlan(plan, pagina(0, 10, "fechaRegistro,desc")))
                .extracting(ConsumoMateriaPrima::getIdConsumo)
                .containsExactly(a, c, b);
    }

    @Test
    @DisplayName("contarPorPlan cuenta los consumos del plan y responde 0 si no tiene")
    void contarPorPlanCuentaLosConsumosDelPlan() {
        var conConsumos = insertarPlan();
        var conUno = insertarPlan();
        var sinConsumos = insertarPlan();
        var detalleA = insertarDetalle(conConsumos, idsProducto.get(0), "10.00");
        var detalleB = insertarDetalle(conConsumos, idsProducto.get(1), "10.00");
        var detalleC = insertarDetalle(conUno, idsProducto.get(0), "10.00");

        insertarConsumo(detalleA, idsMateriaPrima.get(0), "1.000", "1.000");
        insertarConsumo(detalleA, idsMateriaPrima.get(1), "2.000", "2.000");
        insertarConsumo(detalleB, idsMateriaPrima.get(0), "3.000", "3.000");
        insertarConsumo(detalleC, idsMateriaPrima.get(0), "4.000", "4.000");

        assertThat(repository.contarPorPlan(conConsumos)).isEqualTo(3L);
        assertThat(repository.contarPorPlan(conUno)).isEqualTo(1L);
        assertThat(repository.contarPorPlan(sinConsumos)).isZero();
        assertThat(repository.contarPorPlan(2147483647)).isZero();
    }

    @Test
    @DisplayName("listarPorDetallePlan devuelve solo los consumos de la línea, por id ascendente")
    void listarPorDetallePlanDevuelveLosConsumosDeLaLinea() {
        var plan = insertarPlan();
        var detalle = insertarDetalle(plan, idsProducto.get(0), "10.00");
        var otraLinea = insertarDetalle(plan, idsProducto.get(1), "10.00");

        var primero = insertarConsumo(detalle, idsMateriaPrima.get(0), "1.000", "1.000");
        var segundo = insertarConsumo(detalle, idsMateriaPrima.get(1), "2.000", "2.000");
        insertarConsumo(otraLinea, idsMateriaPrima.get(0), "3.000", "3.000");

        var filas = repository.listarPorDetallePlan(detalle);

        assertThat(filas).extracting(ConsumoMateriaPrima::getIdConsumo)
                .containsExactly(primero, segundo);
        assertThat(filas).extracting(ConsumoMateriaPrima::getIdDetallePlan)
                .containsOnly(detalle);
        assertThat(filas).extracting(ConsumoMateriaPrima::getIdPlan).containsOnly(plan);
        assertThat(filas).extracting(ConsumoMateriaPrima::getNombreMateriaPrima)
                .containsExactly("Materia prima A HU-22 test" + sufijo,
                        "Materia prima B HU-22 test" + sufijo);
        assertThat(filas).extracting(ConsumoMateriaPrima::getCantidadTeorica)
                .extracting(BigDecimal::toPlainString)
                .containsExactly("1.000", "2.000");
    }

    @Test
    @DisplayName("buscarPorId y buscarPorIdParaActualizar devuelven el consumo o vacío")
    void buscarPorIdYParaActualizarDevuelvenElConsumo() {
        var plan = insertarPlan();
        var detalle = insertarDetalle(plan, idsProducto.get(0), "10.00");
        var id = insertarConsumo(detalle, idsMateriaPrima.get(0), "2.345", "1.250");

        var leida = repository.buscarPorId(id).orElseThrow();
        // El FOR UPDATE solo protege dentro de una transacción abierta; se ejerce igual que lo
        // hará el servicio.
        var bloqueada = transacciones.execute(
                estado -> repository.buscarPorIdParaActualizar(id).orElseThrow());

        assertThat(leida.getIdConsumo()).isEqualTo(id);
        assertThat(leida.getIdDetallePlan()).isEqualTo(detalle);
        assertThat(leida.getIdPlan()).isEqualTo(plan);
        assertThat(leida.getIdMateriaPrima()).isEqualTo(idsMateriaPrima.get(0));
        assertThat(leida.getCantidadTeorica()).isEqualByComparingTo("2.345");
        assertThat(leida.getCantidadReal()).isEqualByComparingTo("1.250");
        assertThat(bloqueada).usingRecursiveComparison().isEqualTo(leida);
        assertThat(repository.buscarPorId(2147483647)).isEmpty();
        var ausente = transacciones.execute(
                estado -> repository.buscarPorIdParaActualizar(2147483647));
        assertThat(ausente).isEmpty();
    }

    @Test
    @DisplayName("existeConsumo distingue la materia prima consumida de la ausente")
    void existeConsumoDistingueConsumidaDeAusente() {
        var plan = insertarPlan();
        var detalle = insertarDetalle(plan, idsProducto.get(0), "10.00");
        var otraLinea = insertarDetalle(plan, idsProducto.get(1), "10.00");

        assertThat(repository.existeConsumo(detalle, idsMateriaPrima.get(0))).isFalse();

        insertarConsumo(detalle, idsMateriaPrima.get(0), "1.000", "1.000");

        assertThat(repository.existeConsumo(detalle, idsMateriaPrima.get(0))).isTrue();
        assertThat(repository.existeConsumo(detalle, idsMateriaPrima.get(1))).isFalse();
        assertThat(repository.existeConsumo(otraLinea, idsMateriaPrima.get(0))).isFalse();
    }

    @Test
    @DisplayName("actualizarReal devuelve 1 y cambia solo cantidad_real")
    void actualizarRealCambiaSoloLaCantidadReal() {
        var plan = insertarPlan();
        var detalle = insertarDetalle(plan, idsProducto.get(0), "10.00");
        var id = insertarConsumo(detalle, idsMateriaPrima.get(0), "2.345", "1.000");
        var antes = repository.buscarPorId(id).orElseThrow();

        assertThat(repository.actualizarReal(id, new BigDecimal("4.750"))).isEqualTo(1);

        var despues = repository.buscarPorId(id).orElseThrow();
        assertThat(despues.getCantidadReal().toPlainString()).isEqualTo("4.750");
        assertThat(despues.getCantidadTeorica()).isEqualByComparingTo("2.345");
        assertThat(despues.getFechaRegistro()).isEqualTo(antes.getFechaRegistro());
        assertThat(despues.getIdDetallePlan()).isEqualTo(antes.getIdDetallePlan());
        assertThat(despues.getIdMateriaPrima()).isEqualTo(antes.getIdMateriaPrima());
        assertThat(repository.actualizarReal(2147483647, new BigDecimal("1.000"))).isZero();
    }

    /**
     * Construye un consumo sin id para persistirlo por el repositorio.
     *
     * @param idDetalle       línea del plan consumida
     * @param idMateriaPrima  materia prima consumida
     * @param cantidadTeorica cantidad calculada, como texto decimal
     * @param cantidadReal    cantidad gastada, como texto decimal
     * @return el consumo a persistir
     */
    private ConsumoMateriaPrima consumo(Integer idDetalle, Integer idMateriaPrima,
                                        String cantidadTeorica, String cantidadReal) {
        var consumo = new ConsumoMateriaPrima();
        consumo.setIdDetallePlan(idDetalle);
        consumo.setIdMateriaPrima(idMateriaPrima);
        consumo.setCantidadTeorica(new BigDecimal(cantidadTeorica));
        consumo.setCantidadReal(new BigDecimal(cantidadReal));
        return consumo;
    }

    /**
     * Inserta un consumo por el repositorio —el camino real de la aplicación— y devuelve su id.
     *
     * @param idDetalle       línea del plan consumida
     * @param idMateriaPrima  materia prima consumida
     * @param cantidadTeorica cantidad calculada, como texto decimal
     * @param cantidadReal    cantidad gastada, como texto decimal
     * @return identificador asignado por la base
     */
    private Integer insertarConsumo(Integer idDetalle, Integer idMateriaPrima,
                                    String cantidadTeorica, String cantidadReal) {
        return repository.insertar(
                consumo(idDetalle, idMateriaPrima, cantidadTeorica, cantidadReal));
    }

    /**
     * Fija la fecha de registro de un consumo ya insertado, para que el orden por
     * {@code fecha_registro} sea determinista y no dependa de la precisión de segundos del
     * {@code DEFAULT CURRENT_TIMESTAMP}.
     *
     * @param idConsumo identificador del consumo
     * @param fecha     fecha que se va a persistir
     */
    private void fijarFecha(Integer idConsumo, LocalDateTime fecha) {
        jdbc.update("""
                UPDATE consumo_materia_prima
                SET fecha_registro = :fecha
                WHERE id_consumo = :id
                """, new MapSqlParameterSource()
                        .addValue("fecha", fecha)
                        .addValue("id", idConsumo));
    }

    /**
     * Arma la página con la lista blanca y el orden por defecto de la prueba, para que el orden
     * viaje por {@link PageRequest#of} igual que en el servicio.
     */
    private static PageRequest pagina(int page, int size, String sort) {
        return PageRequest.of(page, size, sort, ORDEN, ORDEN_POR_DEFECTO);
    }

    private Integer insertarDetalle(Integer idPlan, Integer idProducto, String cantidad) {
        var id = insertarConId("""
                INSERT INTO detalle_plan_produccion
                    (id_plan, id_producto, cantidad_planificada)
                VALUES (:idPlan, :idProducto, :cantidadPlanificada)
                """, new MapSqlParameterSource()
                        .addValue("idPlan", idPlan)
                        .addValue("idProducto", idProducto)
                        .addValue("cantidadPlanificada", new BigDecimal(cantidad)));
        idsDetalle.add(id);
        return id;
    }

    /**
     * Siembra un plan con el responsable por defecto de las pruebas y lo registra para la
     * limpieza.
     *
     * @return identificador del plan sembrado
     */
    private Integer insertarPlan() {
        var id = insertarConId("""
                INSERT INTO planes_produccion (fecha_planificacion, fecha_produccion, estado,
                                               observaciones, id_usuario)
                VALUES (:fechaPlanificacion, :fechaProduccion, 'PLANIFICADO', NULL, :idUsuario)
                """, new MapSqlParameterSource()
                        .addValue("fechaPlanificacion", LocalDate.of(1990, 11, 10))
                        .addValue("fechaProduccion", LocalDate.of(1990, 11, 12))
                        .addValue("idUsuario", idUsuario));
        idsPlan.add(id);
        return id;
    }

    private Integer insertarUsuario(String nombre) {
        // La tabla roles la siembra V3 con los roles del sistema; el primero basta como FK.
        var idRol = jdbc.queryForObject(
                "SELECT id_rol FROM roles ORDER BY id_rol ASC LIMIT 1",
                new MapSqlParameterSource(), Integer.class);
        // El correo tiene índice único: un fragmento de UUID evita colisiones entre corridas.
        return insertarConId("""
                INSERT INTO usuarios (nombre, correo, password, estado, id_rol)
                VALUES (:nombre, :correo, :password, 1, :idRol)
                """, new MapSqlParameterSource()
                        .addValue("nombre", nombre)
                        .addValue("correo", "h22-" + UUID.randomUUID().toString()
                                .substring(0, 8) + "@test.local")
                        .addValue("password", "clave-test")
                        .addValue("idRol", idRol));
    }

    private Integer insertarProducto(String nombre) {
        return insertarConId("""
                INSERT INTO productos (nombre, precio, id_unidad, estado)
                VALUES (:nombre, 100.00, :idUnidad, 1)
                """, new MapSqlParameterSource()
                        .addValue("nombre", nombre)
                        .addValue("idUnidad", idUnidad));
    }

    private Integer insertarReceta(Integer idProducto) {
        return insertarConId("""
                INSERT INTO recetas (id_producto, nombre, cantidad_producir, id_unidad, estado)
                VALUES (:idProducto, :nombre, 1.00, :idUnidad, 1)
                """, new MapSqlParameterSource()
                        .addValue("idProducto", idProducto)
                        .addValue("nombre", "Receta HU-22 test" + sufijo)
                        .addValue("idUnidad", idUnidad));
    }

    private Integer insertarMateriaPrima(String nombre) {
        return insertarConId("""
                INSERT INTO materias_primas (nombre, id_unidad, stock_minimo, costo_unitario, estado)
                VALUES (:nombre, :idUnidad, 0.00, 1.00, 1)
                """, new MapSqlParameterSource()
                        .addValue("nombre", nombre)
                        .addValue("idUnidad", idUnidad));
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
}
