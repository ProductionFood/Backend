package co.edu.corposucre.productionfood.produccion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
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

import co.edu.corposucre.productionfood.common.pagination.PageRequest;

/**
 * Pruebas de integración de {@link DetallePlanRepository} contra MySQL (HU-21).
 *
 * <p>El proyecto no usa Testcontainers ni H2: la única base soportada es la del
 * {@code docker-compose}. La prueba se conecta con los mismos parámetros que
 * {@code application.yml} (variables de entorno con los mismos valores por defecto) y se omite
 * con {@code assumeTrue} si la base o la tabla no están disponibles, para no fallar en un
 * entorno sin el contenedor levantado.
 *
 * <p>Cada prueba crea su propia unidad, sus productos, su receta, su materia prima, su plan y sus
 * líneas, y los elimina al terminar en orden inverso al de las llaves foráneas: los consumos y el
 * detalle antes que la cabecera, las recetas antes que los productos, y los productos, la materia
 * prima y el plan antes que la unidad y el usuario.
 *
 * <p>La lista blanca {@link #ORDEN} es propia de la prueba, con los mismos valores que usa el
 * repositorio, para que el orden se ejerza por el camino público de {@link PageRequest#of}.
 */
class DetallePlanRepositoryTest {

    private static final String URL = "jdbc:mysql://"
            + entorno("DB_HOST", "127.0.0.1") + ":" + entorno("DB_PORT", "3306")
            + "/" + entorno("DB_NAME", "productionfood")
            + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=America/Bogota";
    private static final String USUARIO = entorno("DB_USER", "root");
    private static final String CLAVE = entorno("DB_PASSWORD", "local");

    private static final Map<String, String> ORDEN = Map.of(
            "producto", "p.nombre",
            "cantidadPlanificada", "d.cantidad_planificada",
            "avance", "(d.cantidad_producida / d.cantidad_planificada)");
    private static final String ORDEN_POR_DEFECTO = "producto,asc";

    private NamedParameterJdbcTemplate jdbc;
    private DetallePlanRepository repository;

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
            sentencia.executeQuery("SELECT 1 FROM detalle_plan_produccion LIMIT 1").close();
            disponible = true;
        } catch (SQLException e) {
            disponible = false;
        }
        assumeTrue(disponible,
                "MySQL no responde en " + URL + " con la tabla detalle_plan_produccion; se omite.");
    }

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource(URL, USUARIO, CLAVE);
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        jdbc = new NamedParameterJdbcTemplate(dataSource);
        repository = new DetallePlanRepository(jdbc);

        // Sufijo único por prueba: los nombres y abreviaturas tienen índice en la base y una
        // corrida anterior que deje datos no debe confundir las lecturas del JOIN.
        sufijo = "-" + UUID.randomUUID().toString().substring(0, 8);
        idUnidad = insertarConId("""
                INSERT INTO unidades_medida (nombre, abreviatura)
                VALUES (:nombre, :abreviatura)
                """, new MapSqlParameterSource()
                        .addValue("nombre", "Unidad HU-21 test" + sufijo)
                        .addValue("abreviatura", "u21" + sufijo));
        // Tres productos para poder comprobar orden ascendente y descendente con holgura; el
        // primero tiene receta y los otros dos no.
        idsProducto.add(insertarProducto("Producto A HU-21 test" + sufijo));
        idsProducto.add(insertarProducto("Producto B HU-21 test" + sufijo));
        idsProducto.add(insertarProducto("Producto C HU-21 test" + sufijo));
        idUsuario = insertarUsuario("Responsable HU-21 test" + sufijo);
        idsReceta.add(insertarReceta(idsProducto.get(0)));
    }

    @AfterEach
    void tearDown() {
        if (!idsDetalle.isEmpty()) {
            jdbc.update("DELETE FROM consumo_materia_prima WHERE id_detalle_plan IN (:ids)",
                    new MapSqlParameterSource().addValue("ids", idsDetalle));
        }
        // El detalle primero que la cabecera: detalle_plan_produccion → planes es FK.
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
    @DisplayName("insertar devuelve el id, deja la producida en 0.00 y resuelve tieneReceta")
    void insertarDevuelveIdYDejaLaProducidaEnCero() {
        var plan = insertarPlan();
        var conReceta = insertarDetalle(plan, idsProducto.get(0), "10.00");
        var sinReceta = insertarDetalle(plan, idsProducto.get(1), "5.50");

        var primero = repository.buscarPorId(conReceta).orElseThrow();
        var segundo = repository.buscarPorId(sinReceta).orElseThrow();

        assertThat(conReceta).isNotNull();
        assertThat(sinReceta).isNotNull();
        assertThat(primero.getIdDetallePlan()).isEqualTo(conReceta);
        assertThat(primero.getIdPlan()).isEqualTo(plan);
        assertThat(primero.getIdProducto()).isEqualTo(idsProducto.get(0));
        assertThat(primero.getCantidadPlanificada()).isEqualByComparingTo("10.00");
        // La columna no viaja en el INSERT: la base aplica su DEFAULT 0.00.
        assertThat(primero.getCantidadProducida()).isEqualByComparingTo("0.00");
        assertThat(primero.isTieneReceta()).isTrue();
        assertThat(segundo.isTieneReceta()).isFalse();
        assertThat(segundo.getCantidadProducida()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("listarPorPlan trae el nombre del producto y la abreviatura de la unidad del JOIN")
    void listarPorPlanTraeNombreYUnidadDelJoin() {
        var plan = insertarPlan();
        var otroPlan = insertarPlan();
        var primera = insertarDetalle(plan, idsProducto.get(0), "10.00");
        var segunda = insertarDetalle(plan, idsProducto.get(1), "5.00");
        insertarDetalle(otroPlan, idsProducto.get(2), "7.00");

        var filas = repository.listarPorPlan(plan, pagina(0, 10, "producto,asc"));

        assertThat(filas).extracting(DetallePlanProduccion::getIdDetallePlan)
                .containsExactly(primera, segunda);
        assertThat(filas).extracting(DetallePlanProduccion::getNombreProducto)
                .containsExactly("Producto A HU-21 test" + sufijo,
                        "Producto B HU-21 test" + sufijo);
        assertThat(filas).extracting(DetallePlanProduccion::getUnidad)
                .containsOnly("u21" + sufijo);
        assertThat(repository.contarPorPlan(otroPlan)).isEqualTo(1L);
    }

    @Test
    @DisplayName("listarPorPlan ordena por producto en ambos sentidos y pagina sin repetir filas")
    void listarPorPlanOrdenaPorProductoYReparteLaPaginacion() {
        var plan = insertarPlan();
        var a = insertarDetalle(plan, idsProducto.get(0), "3.00");
        var b = insertarDetalle(plan, idsProducto.get(1), "2.00");
        var c = insertarDetalle(plan, idsProducto.get(2), "1.00");

        var ascendente = repository.listarPorPlan(plan, pagina(0, 10, "producto,asc"));
        assertThat(ascendente).extracting(DetallePlanProduccion::getIdDetallePlan)
                .containsExactly(a, b, c);

        var descendente = repository.listarPorPlan(plan, pagina(0, 10, "producto,desc"));
        assertThat(descendente).extracting(DetallePlanProduccion::getIdDetallePlan)
                .containsExactly(c, b, a);

        var pagina0 = repository.listarPorPlan(plan, pagina(0, 1, "producto,asc"));
        var pagina1 = repository.listarPorPlan(plan, pagina(1, 1, "producto,asc"));
        var pagina2 = repository.listarPorPlan(plan, pagina(2, 1, "producto,asc"));
        var pagina3 = repository.listarPorPlan(plan, pagina(3, 1, "producto,asc"));

        assertThat(pagina0).extracting(DetallePlanProduccion::getIdDetallePlan)
                .containsExactly(a);
        assertThat(pagina1).extracting(DetallePlanProduccion::getIdDetallePlan)
                .containsExactly(b);
        assertThat(pagina2).extracting(DetallePlanProduccion::getIdDetallePlan)
                .containsExactly(c);
        assertThat(pagina3).isEmpty();
    }

    @Test
    @DisplayName("listarPorPlan ordena por cantidad planificada ascendente y descendente")
    void listarPorPlanOrdenaPorCantidadPlanificada() {
        var plan = insertarPlan();
        var mayor = insertarDetalle(plan, idsProducto.get(0), "30.00");
        var menor = insertarDetalle(plan, idsProducto.get(1), "10.00");
        var media = insertarDetalle(plan, idsProducto.get(2), "20.00");

        var ascendente = repository.listarPorPlan(plan,
                pagina(0, 10, "cantidadPlanificada,asc"));
        assertThat(ascendente).extracting(DetallePlanProduccion::getIdDetallePlan)
                .containsExactly(menor, media, mayor);

        var descendente = repository.listarPorPlan(plan,
                pagina(0, 10, "cantidadPlanificada,desc"));
        assertThat(descendente).extracting(DetallePlanProduccion::getIdDetallePlan)
                .containsExactly(mayor, media, menor);
    }

    @Test
    @DisplayName("listarPorPlan ordena por avance, la relación entre producida y planificada")
    void listarPorPlanOrdenaPorAvance() {
        var plan = insertarPlan();
        var medio = insertarDetalle(plan, idsProducto.get(0), "10.00");
        var bajo = insertarDetalle(plan, idsProducto.get(1), "10.00");
        var completo = insertarDetalle(plan, idsProducto.get(2), "10.00");
        repository.actualizarProducida(medio, new BigDecimal("5.00"));
        repository.actualizarProducida(bajo, new BigDecimal("2.00"));
        repository.actualizarProducida(completo, new BigDecimal("10.00"));

        var ascendente = repository.listarPorPlan(plan, pagina(0, 10, "avance,asc"));
        assertThat(ascendente).extracting(DetallePlanProduccion::getIdDetallePlan)
                .containsExactly(bajo, medio, completo);

        var descendente = repository.listarPorPlan(plan, pagina(0, 10, "avance,desc"));
        assertThat(descendente).extracting(DetallePlanProduccion::getIdDetallePlan)
                .containsExactly(completo, medio, bajo);
    }

    @Test
    @DisplayName("contarPorPlan cuenta las líneas de un plan y responde 0 si no tiene")
    void contarPorPlanCuentaLasLineasDelPlan() {
        var conLineas = insertarPlan();
        var conUnaLinea = insertarPlan();
        insertarDetalle(conLineas, idsProducto.get(0), "1.00");
        insertarDetalle(conLineas, idsProducto.get(1), "2.00");
        insertarDetalle(conLineas, idsProducto.get(2), "3.00");
        insertarDetalle(conUnaLinea, idsProducto.get(0), "4.00");
        var sinLineas = insertarPlan();

        assertThat(repository.contarPorPlan(conLineas)).isEqualTo(3L);
        assertThat(repository.contarPorPlan(conUnaLinea)).isEqualTo(1L);
        assertThat(repository.contarPorPlan(sinLineas)).isZero();
        assertThat(repository.contarPorPlan(2147483647)).isZero();
    }

    @Test
    @DisplayName("buscarPorId y buscarPorIdParaActualizar devuelven la línea y vacío si no existe")
    void buscarPorIdYParaActualizarDevuelvenLaLinea() {
        var plan = insertarPlan();
        var id = insertarDetalle(plan, idsProducto.get(0), "8.00");

        var leida = repository.buscarPorId(id).orElseThrow();
        var bloqueada = repository.buscarPorIdParaActualizar(id).orElseThrow();

        assertThat(leida.getIdDetallePlan()).isEqualTo(id);
        assertThat(leida.getIdPlan()).isEqualTo(plan);
        assertThat(leida.getIdProducto()).isEqualTo(idsProducto.get(0));
        assertThat(leida.getCantidadPlanificada()).isEqualByComparingTo("8.00");
        assertThat(bloqueada).usingRecursiveComparison().isEqualTo(leida);
        assertThat(repository.buscarPorId(2147483647)).isEmpty();
        assertThat(repository.buscarPorIdParaActualizar(2147483647)).isEmpty();
    }

    @Test
    @DisplayName("actualizarPlanificada y actualizarProducida devuelven 1 y persisten el valor")
    void actualizarPlanificadaYProducidaPersistenElValor() {
        var plan = insertarPlan();
        var id = insertarDetalle(plan, idsProducto.get(0), "10.00");

        assertThat(repository.actualizarPlanificada(id, new BigDecimal("12.50"))).isEqualTo(1);
        assertThat(repository.buscarPorId(id).orElseThrow().getCantidadPlanificada())
                .isEqualByComparingTo("12.50");

        assertThat(repository.actualizarProducida(id, new BigDecimal("4.75"))).isEqualTo(1);
        var leida = repository.buscarPorId(id).orElseThrow();
        assertThat(leida.getCantidadProducida()).isEqualByComparingTo("4.75");
        assertThat(leida.getCantidadPlanificada()).isEqualByComparingTo("12.50");
    }

    @Test
    @DisplayName("eliminar devuelve 1, la fila desaparece y el segundo intento devuelve 0")
    void eliminarBorraLaLinea() {
        var plan = insertarPlan();
        var id = insertarDetalle(plan, idsProducto.get(0), "10.00");

        assertThat(repository.eliminar(id)).isEqualTo(1);
        assertThat(repository.buscarPorId(id)).isEmpty();
        assertThat(repository.eliminar(id)).isZero();
    }

    @Test
    @DisplayName("existeProducto distingue el producto presente del ausente y del otro plan")
    void existeProductoDistinguePresenteDeAusente() {
        var plan = insertarPlan();
        var otroPlan = insertarPlan();
        insertarDetalle(plan, idsProducto.get(0), "10.00");

        assertThat(repository.existeProducto(plan, idsProducto.get(0))).isTrue();
        assertThat(repository.existeProducto(plan, idsProducto.get(1))).isFalse();
        assertThat(repository.existeProducto(otroPlan, idsProducto.get(0))).isFalse();
    }

    @Test
    @DisplayName("tieneConsumos responde false sin consumos y true al registrar uno")
    void tieneConsumosRespondeFalseYDespuesTrue() {
        var plan = insertarPlan();
        var id = insertarDetalle(plan, idsProducto.get(0), "10.00");
        var otraLinea = insertarDetalle(plan, idsProducto.get(1), "5.00");

        assertThat(repository.tieneConsumos(id)).isFalse();

        var idMateriaPrima = insertarMateriaPrima();
        insertarConsumo(id, idMateriaPrima);

        assertThat(repository.tieneConsumos(id)).isTrue();
        assertThat(repository.tieneConsumos(otraLinea)).isFalse();
    }

    @Test
    @DisplayName("R-06: el UNIQUE (id_plan, id_producto) impide repetir el producto")
    void elUniqueImpideRepetirElProducto() {
        var plan = insertarPlan();
        insertarDetalle(plan, idsProducto.get(0), "10.00");

        assertThatThrownBy(() -> repository.insertar(
                detalle(plan, idsProducto.get(0), "2.00")))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(repository.contarPorPlan(plan)).isEqualTo(1L);
    }

    /**
     * Construye una línea sin id para persistirla por el repositorio.
     *
     * @param idPlan     plan al que pertenece la línea
     * @param idProducto producto de la línea
     * @param cantidad   cantidad planificada, como texto decimal
     * @return la línea a persistir
     */
    private DetallePlanProduccion detalle(Integer idPlan, Integer idProducto, String cantidad) {
        var detalle = new DetallePlanProduccion();
        detalle.setIdPlan(idPlan);
        detalle.setIdProducto(idProducto);
        detalle.setCantidadPlanificada(new BigDecimal(cantidad));
        return detalle;
    }

    /**
     * Inserta una línea por el repositorio —el camino real de la aplicación— y registra su id para
     * que la limpieza la borre junto con sus consumos.
     *
     * @param idPlan     plan al que pertenece la línea
     * @param idProducto producto de la línea
     * @param cantidad   cantidad planificada, como texto decimal
     * @return identificador asignado por la base
     */
    private Integer insertarDetalle(Integer idPlan, Integer idProducto, String cantidad) {
        var id = repository.insertar(detalle(idPlan, idProducto, cantidad));
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
                        .addValue("correo", "h21-" + UUID.randomUUID().toString()
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
                        .addValue("nombre", "Receta HU-21 test" + sufijo)
                        .addValue("idUnidad", idUnidad));
    }

    private Integer insertarMateriaPrima() {
        var id = insertarConId("""
                INSERT INTO materias_primas (nombre, id_unidad, stock_minimo, costo_unitario, estado)
                VALUES (:nombre, :idUnidad, 0.00, 1.00, 1)
                """, new MapSqlParameterSource()
                        .addValue("nombre", "Materia prima HU-21 test" + sufijo)
                        .addValue("idUnidad", idUnidad));
        idsMateriaPrima.add(id);
        return id;
    }

    private Integer insertarConsumo(Integer idDetalle, Integer idMateriaPrima) {
        return insertarConId("""
                INSERT INTO consumo_materia_prima (id_detalle_plan, id_materia_prima,
                                                   cantidad_teorica, cantidad_real)
                VALUES (:idDetalle, :idMateriaPrima, 1.000, 1.000)
                """, new MapSqlParameterSource()
                        .addValue("idDetalle", idDetalle)
                        .addValue("idMateriaPrima", idMateriaPrima));
    }

    /**
     * Arma la página con la lista blanca y el orden por defecto de la prueba, para que el orden
     * viaje por {@link PageRequest#of} igual que en el servicio.
     */
    private static PageRequest pagina(int page, int size, String sort) {
        return PageRequest.of(page, size, sort, ORDEN, ORDEN_POR_DEFECTO);
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
