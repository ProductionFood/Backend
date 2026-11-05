package co.edu.corposucre.productionfood.produccion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.GeneratedKeyHolder;

import co.edu.corposucre.productionfood.common.pagination.PageRequest;

/**
 * Pruebas de integración de {@link PlanProduccionRepository} contra MySQL (HU-20).
 *
 * <p>El proyecto no usa Testcontainers ni H2: la única base soportada es la del
 * {@code docker-compose}. La prueba se conecta con los mismos parámetros que
 * {@code application.yml} (variables de entorno con los mismos valores por defecto) y se omite
 * con {@code assumeTrue} si la base o la tabla no están disponibles, para no fallar en un
 * entorno sin el contenedor levantado.
 *
 * <p>Cada prueba crea su propio usuario, sus productos y sus planes, y los elimina al terminar
 * en orden inverso al de las llaves foráneas: el detalle antes que la cabecera, la cabecera
 * antes que su responsable, y los productos antes que la unidad.
 *
 * <p>Las fechas de siembra viven en noviembre de 1990 —un rango lejano a cualquier dato
 * realista de la base— para que el filtro por rango sea determinista sin importar lo que haya
 * en otras filas.
 */
class PlanProduccionRepositoryTest {

    private static final String URL = "jdbc:mysql://"
            + entorno("DB_HOST", "127.0.0.1") + ":" + entorno("DB_PORT", "3306")
            + "/" + entorno("DB_NAME", "productionfood")
            + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=America/Bogota";
    private static final String USUARIO = entorno("DB_USER", "root");
    private static final String CLAVE = entorno("DB_PASSWORD", "local");

    private NamedParameterJdbcTemplate jdbc;
    private PlanProduccionRepository repository;

    private String sufijo;
    private Integer idUnidad;
    private final List<Integer> idsProducto = new ArrayList<>();
    private Integer idUsuario;
    private Integer idUsuarioExtra;
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
            sentencia.executeQuery("SELECT 1 FROM planes_produccion LIMIT 1").close();
            disponible = true;
        } catch (SQLException e) {
            disponible = false;
        }
        assumeTrue(disponible,
                "MySQL no responde en " + URL + " con la tabla planes_produccion; se omite.");
    }

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource(URL, USUARIO, CLAVE);
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        jdbc = new NamedParameterJdbcTemplate(dataSource);
        repository = new PlanProduccionRepository(jdbc);

        // Sufijo único por prueba: los nombres y correos tienen índice en la base y una
        // corrida anterior que deje datos no debe confundir las lecturas por nombre.
        sufijo = "-" + UUID.randomUUID().toString().substring(0, 8);
        idUnidad = insertarConId("""
                INSERT INTO unidades_medida (nombre, abreviatura)
                VALUES (:nombre, :abreviatura)
                """, new MapSqlParameterSource()
                        .addValue("nombre", "Unidad HU-20 test" + sufijo)
                        .addValue("abreviatura", "u20" + sufijo));
        // Dos productos: uk_detalle_plan es (id_plan, id_producto), así que un plan con dos
        // líneas necesita dos productos distintos.
        idsProducto.add(insertarProducto("Producto A HU-20 test" + sufijo));
        idsProducto.add(insertarProducto("Producto B HU-20 test" + sufijo));
        idUsuario = insertarUsuario("Responsable HU-20 test" + sufijo);
        idUsuarioExtra = insertarUsuario("Responsable extra HU-20 test" + sufijo);
    }

    @AfterEach
    void tearDown() {
        // El detalle primero que la cabecera: detalle_plan_produccion → planes es FK.
        if (!idsDetalle.isEmpty()) {
            jdbc.update("DELETE FROM detalle_plan_produccion WHERE id_plan IN (:ids)",
                    new MapSqlParameterSource().addValue("ids", idsPlan));
        }
        if (!idsPlan.isEmpty()) {
            jdbc.update("DELETE FROM planes_produccion WHERE id_plan IN (:ids)",
                    new MapSqlParameterSource().addValue("ids", idsPlan));
        }
        if (idUsuarioExtra != null) {
            jdbc.update("DELETE FROM usuarios WHERE id_usuario = :id",
                    new MapSqlParameterSource().addValue("id", idUsuarioExtra));
        }
        if (idUsuario != null) {
            jdbc.update("DELETE FROM usuarios WHERE id_usuario = :id",
                    new MapSqlParameterSource().addValue("id", idUsuario));
        }
        if (!idsProducto.isEmpty()) {
            jdbc.update("DELETE FROM productos WHERE id_producto IN (:ids)",
                    new MapSqlParameterSource().addValue("ids", idsProducto));
        }
        if (idUnidad != null) {
            jdbc.update("DELETE FROM unidades_medida WHERE id_unidad = :id",
                    new MapSqlParameterSource().addValue("id", idUnidad));
        }
    }

    @Test
    @DisplayName("insertar devuelve el id y buscarPorId trae fechas, estado y responsable del JOIN")
    void insertarYBuscarPorIdTraenLaCabeceraConElResponsable() {
        var id = insertarConRepositorio(nueva(LocalDate.of(1990, 11, 10),
                LocalDate.of(1990, 11, 12), EstadoPlan.PLANIFICADO, "Producción de prueba"));

        var leido = repository.buscarPorId(id).orElseThrow();

        assertThat(leido.getIdPlan()).isEqualTo(id);
        assertThat(leido.getFechaPlanificacion()).isEqualTo(LocalDate.of(1990, 11, 10));
        assertThat(leido.getFechaProduccion()).isEqualTo(LocalDate.of(1990, 11, 12));
        assertThat(leido.getEstado()).isEqualTo(EstadoPlan.PLANIFICADO);
        assertThat(leido.getObservaciones()).isEqualTo("Producción de prueba");
        assertThat(leido.getIdUsuario()).isEqualTo(idUsuario);
        // El nombre viene del JOIN con usuarios, no de una consulta extra (HU-20).
        assertThat(leido.getNombreResponsable()).isEqualTo("Responsable HU-20 test" + sufijo);
        // Un plan sin líneas tiene cantidad_productos 0, no NULL (HU-21 las agrega después).
        assertThat(leido.getCantidadProductos()).isZero();
    }

    @Test
    @DisplayName("buscarPorId devuelve vacío para un id que no existe")
    void buscarPorIdDevuelveVacioCuandoNoExiste() {
        // Id lejano a cualquier fila real: la consulta no devuelve nada y el Optional queda
        // vacío, igual que en los demás repositorios.
        assertThat(repository.buscarPorId(2147483647)).isEmpty();
    }

    @Test
    @DisplayName("insertar persiste las observaciones y las deja en NULL cuando se omiten")
    void insertarPersisteONulleaLasObservaciones() {
        var conObservaciones = insertarConRepositorio(nueva(LocalDate.of(1990, 11, 10),
                LocalDate.of(1990, 11, 12), EstadoPlan.PLANIFICADO, "Con detalle"));
        var sinObservaciones = insertarConRepositorio(nueva(LocalDate.of(1990, 11, 10),
                LocalDate.of(1990, 11, 12), EstadoPlan.PLANIFICADO, null));

        assertThat(repository.buscarPorId(conObservaciones).orElseThrow().getObservaciones())
                .isEqualTo("Con detalle");
        assertThat(repository.buscarPorId(sinObservaciones).orElseThrow().getObservaciones())
                .isNull();
    }

    @Test
    @DisplayName("buscar y contar aplican los mismos filtros de estado, responsable y rango")
    void buscarYContarAplicanLosMismosFiltros() {
        var propia = sembrarPlan(LocalDate.of(1990, 11, 5), EstadoPlan.PLANIFICADO, idUsuario);
        sembrarPlan(LocalDate.of(1990, 11, 10), EstadoPlan.PLANIFICADO, idUsuario);
        sembrarPlan(LocalDate.of(1990, 11, 10), EstadoPlan.EN_PROCESO, idUsuario);
        sembrarPlan(LocalDate.of(1990, 11, 10), EstadoPlan.PLANIFICADO, idUsuarioExtra);
        // Fuera del rango propio: no debe entrar en ningún conteo de noviembre.
        sembrarPlan(LocalDate.of(1990, 12, 5), EstadoPlan.PLANIFICADO, idUsuario);

        var inicio = LocalDate.of(1990, 11, 8);
        var fin = LocalDate.of(1990, 11, 12);

        // Rango sobre fecha_produccion: solo el día 10 cae entre el 8 y el 12.
        assertThat(repository.contar(null, null, inicio, fin)).isEqualTo(3);
        // Estado: los dos PLANIFICADO del día 10, uno propio y uno del responsable extra.
        assertThat(repository.contar(EstadoPlan.PLANIFICADO, null, inicio, fin)).isEqualTo(2);
        // Responsable: el EN_PROCESO del día 10 queda fuera por idUsuario.
        assertThat(repository.contar(null, idUsuario, inicio, fin)).isEqualTo(2);
        // Sin rango, el propio acumula sus tres PLANIFICADO de noviembre y diciembre.
        assertThat(repository.contar(EstadoPlan.PLANIFICADO, idUsuario, null, null))
                .isEqualTo(3);

        var filas = repository.buscar(EstadoPlan.PLANIFICADO, idUsuario, inicio, fin,
                pagina(0, 10));
        assertThat(filas).hasSize(1);
        assertThat(filas).extracting(PlanProduccion::getIdPlan).doesNotContain(propia);
        assertThat(repository.contar(EstadoPlan.PLANIFICADO, idUsuario, inicio, fin))
                .isEqualTo(filas.size());
    }

    @Test
    @DisplayName("la paginación reparte las filas en páginas de 'size' sin repetir ni perder ninguna")
    void laPaginacionReparteLasFilasSinRepetirNiPerderNinguna() {
        var primera = sembrarPlan(LocalDate.of(1990, 11, 5), EstadoPlan.PLANIFICADO, idUsuario);
        var segunda = sembrarPlan(LocalDate.of(1990, 11, 10), EstadoPlan.EN_PROCESO, idUsuario);
        var tercera = sembrarPlan(LocalDate.of(1990, 11, 15), EstadoPlan.COMPLETADO, idUsuario);

        var pagina0 = repository.buscar(null, null, null, null, pagina(0, 1));
        var pagina1 = repository.buscar(null, null, null, null, pagina(1, 1));
        var pagina2 = repository.buscar(null, null, null, null, pagina(2, 1));
        var pagina3 = repository.buscar(null, null, null, null, pagina(3, 1));

        assertThat(pagina0).hasSize(1);
        assertThat(pagina1).hasSize(1);
        assertThat(pagina2).hasSize(1);
        assertThat(pagina3).isEmpty();
        // Con la propiedad id, las tres páginas encadenadas recorren las filas en el orden en
        // que se sembraron, sin repetir ni perder ninguna.
        assertThat(Stream.concat(Stream.concat(pagina0.stream(), pagina1.stream()),
                pagina2.stream()))
                .extracting(PlanProduccion::getIdPlan)
                .containsExactly(primera, segunda, tercera);
        assertThat(repository.contar(null, null, null, null)).isEqualTo(3);
    }

    @Test
    @DisplayName("sort por estado resuelve la lista blanca y devuelve las filas en ese orden")
    void sortPorEstadoResuelveLaListaBlanca() {
        sembrarPlan(LocalDate.of(1990, 11, 5), EstadoPlan.PLANIFICADO, idUsuario);
        sembrarPlan(LocalDate.of(1990, 11, 10), EstadoPlan.CANCELADO, idUsuario);
        sembrarPlan(LocalDate.of(1990, 11, 15), EstadoPlan.COMPLETADO, idUsuario);

        var ascendente = repository.buscar(null, null, null, null,
                PageRequest.of(0, 10, "estado", PlanProduccionRepository.ORDEN,
                        PlanProduccionRepository.ORDEN_POR_DEFECTO));

        assertThat(ascendente).extracting(PlanProduccion::getEstado)
                .containsExactly(EstadoPlan.CANCELADO, EstadoPlan.COMPLETADO,
                        EstadoPlan.PLANIFICADO);

        var descendente = repository.buscar(null, null, null, null,
                PageRequest.of(0, 10, "estado,desc", PlanProduccionRepository.ORDEN,
                        PlanProduccionRepository.ORDEN_POR_DEFECTO));

        assertThat(descendente).extracting(PlanProduccion::getEstado)
                .containsExactly(EstadoPlan.PLANIFICADO, EstadoPlan.COMPLETADO,
                        EstadoPlan.CANCELADO);
    }

    @Test
    @DisplayName("una propiedad de orden fuera de la lista blanca cae al orden por defecto sin error")
    void unaPropiedadFueraDeLaListaBlancaCaeAlOrdenPorDefecto() {
        var temprana = sembrarPlan(LocalDate.of(1990, 11, 5), EstadoPlan.PLANIFICADO,
                idUsuario);
        var media = sembrarPlan(LocalDate.of(1990, 11, 10), EstadoPlan.EN_PROCESO, idUsuario);
        var tardia = sembrarPlan(LocalDate.of(1990, 11, 15), EstadoPlan.COMPLETADO, idUsuario);

        // 'u.nombre' no pertenece a la lista blanca: el repositorio no la concatena en el
        // ORDER BY —una columna inexistente responde ERROR 1054, el borde que dolió en HU-15—
        // y aplica el orden por defecto completo, fecha_produccion DESC.
        var filas = repository.buscar(null, null, null, null,
                new PageRequest(0, 10, "u.nombre", true));

        assertThat(filas).extracting(PlanProduccion::getIdPlan)
                .containsExactly(tardia, media, temprana);
    }

    @Test
    @DisplayName("cambiarEstado actualiza solo el estado y deja intactas fechas y observaciones")
    void cambiarEstadoSoloCambiaElEstado() {
        var id = insertarConRepositorio(nueva(LocalDate.of(1990, 11, 10),
                LocalDate.of(1990, 11, 12), EstadoPlan.PLANIFICADO, "Antes del cambio"));

        var afectadas = repository.cambiarEstado(id, EstadoPlan.PLANIFICADO,
                EstadoPlan.EN_PROCESO);

        assertThat(afectadas).isEqualTo(1);
        var leido = repository.buscarPorId(id).orElseThrow();
        assertThat(leido.getEstado()).isEqualTo(EstadoPlan.EN_PROCESO);
        assertThat(leido.getFechaPlanificacion()).isEqualTo(LocalDate.of(1990, 11, 10));
        assertThat(leido.getFechaProduccion()).isEqualTo(LocalDate.of(1990, 11, 12));
        assertThat(leido.getObservaciones()).isEqualTo("Antes del cambio");
    }

    @Test
    @DisplayName("cambiarEstado devuelve 0 si el estado esperado ya no coincide (carrera)")
    void cambiarEstadoDevuelveCeroSiElEstadoEsperadoYaNoCoincide() {
        var id = insertarConRepositorio(nueva(LocalDate.of(1990, 11, 10),
                LocalDate.of(1990, 11, 12), EstadoPlan.PLANIFICADO, null));
        // Otra transacción ya lo movió a EN_PROCESO: el UPDATE condicionado a PLANIFICADO no
        // encuentra la fila y no lo pisa.
        repository.cambiarEstado(id, EstadoPlan.PLANIFICADO, EstadoPlan.EN_PROCESO);

        var afectadas = repository.cambiarEstado(id, EstadoPlan.PLANIFICADO,
                EstadoPlan.CANCELADO);

        assertThat(afectadas).isZero();
        assertThat(repository.buscarPorId(id).orElseThrow().getEstado())
                .isEqualTo(EstadoPlan.EN_PROCESO);
    }

    @Test
    @DisplayName("contarDetalles cuenta las líneas del plan y coincide con la subconsulta del listado")
    void contarDetallesCuentaLasLineasDelPlan() {
        var conLineas = insertarConRepositorio(nueva(LocalDate.of(1990, 11, 10),
                LocalDate.of(1990, 11, 12), EstadoPlan.PLANIFICADO, null));
        var sinLineas = insertarConRepositorio(nueva(LocalDate.of(1990, 11, 10),
                LocalDate.of(1990, 11, 12), EstadoPlan.PLANIFICADO, null));
        insertarLinea(conLineas, idsProducto.get(0), "10.00");
        insertarLinea(conLineas, idsProducto.get(1), "5.00");

        assertThat(repository.contarDetalles(conLineas)).isEqualTo(2);
        assertThat(repository.contarDetalles(sinLineas)).isZero();

        // La subconsulta cantidad_productos del SELECT devuelve la misma cifra que
        // contarDetalles, calculada en la misma consulta y sin una consulta por fila.
        assertThat(repository.buscarPorId(conLineas).orElseThrow().getCantidadProductos())
                .isEqualTo(2);
        assertThat(repository.buscarPorId(sinLineas).orElseThrow().getCantidadProductos())
                .isZero();
    }

    @Test
    @DisplayName("actualizarSiEstado cambia fechas, observaciones y responsable, pero ni id ni estado")
    void actualizarSiEstadoCambiaLaCabeceraSinTocarIdNiEstado() {
        var id = insertarConRepositorio(nueva(LocalDate.of(1990, 11, 10),
                LocalDate.of(1990, 11, 12), EstadoPlan.PLANIFICADO, "Original"));
        var antes = repository.buscarPorId(id).orElseThrow();

        var editado = new PlanProduccion();
        editado.setIdPlan(id);
        editado.setFechaPlanificacion(LocalDate.of(1990, 11, 11));
        editado.setFechaProduccion(LocalDate.of(1990, 12, 24));
        editado.setObservaciones("Editado");
        editado.setIdUsuario(idUsuarioExtra);
        var afectadas = repository.actualizarSiEstado(editado, EstadoPlan.PLANIFICADO);

        assertThat(afectadas).isEqualTo(1);
        var leido = repository.buscarPorId(id).orElseThrow();
        assertThat(leido.getIdPlan()).isEqualTo(id);
        assertThat(leido.getFechaPlanificacion()).isEqualTo(LocalDate.of(1990, 11, 11));
        assertThat(leido.getFechaProduccion()).isEqualTo(LocalDate.of(1990, 12, 24));
        assertThat(leido.getObservaciones()).isEqualTo("Editado");
        assertThat(leido.getIdUsuario()).isEqualTo(idUsuarioExtra);
        assertThat(leido.getNombreResponsable())
                .isEqualTo("Responsable extra HU-20 test" + sufijo);
        assertThat(leido.getEstado()).isEqualTo(EstadoPlan.PLANIFICADO);
        assertThat(antes.getEstado()).isEqualTo(EstadoPlan.PLANIFICADO);
    }

    @Test
    @DisplayName("actualizarSiEstado devuelve 0 y no pisa un plan que dejó de estar PLANIFICADO")
    void actualizarSiEstadoDevuelveCeroSiElPlanYaNoEstaPlanificado() {
        var id = insertarConRepositorio(nueva(LocalDate.of(1990, 11, 10),
                LocalDate.of(1990, 11, 12), EstadoPlan.PLANIFICADO, "Original"));
        // Otra transacción lo inició entre la lectura del servicio y el UPDATE condicionado.
        repository.cambiarEstado(id, EstadoPlan.PLANIFICADO, EstadoPlan.EN_PROCESO);

        var editado = new PlanProduccion();
        editado.setIdPlan(id);
        editado.setFechaPlanificacion(LocalDate.of(1990, 11, 11));
        editado.setFechaProduccion(LocalDate.of(1990, 12, 24));
        editado.setObservaciones("Editado");
        editado.setIdUsuario(idUsuarioExtra);
        var afectadas = repository.actualizarSiEstado(editado, EstadoPlan.PLANIFICADO);

        assertThat(afectadas).isZero();
        var leido = repository.buscarPorId(id).orElseThrow();
        assertThat(leido.getEstado()).isEqualTo(EstadoPlan.EN_PROCESO);
        assertThat(leido.getFechaPlanificacion()).isEqualTo(LocalDate.of(1990, 11, 10));
        assertThat(leido.getFechaProduccion()).isEqualTo(LocalDate.of(1990, 11, 12));
        assertThat(leido.getObservaciones()).isEqualTo("Original");
        assertThat(leido.getIdUsuario()).isEqualTo(idUsuario);
    }

    /**
     * Crea un plan con el responsable por defecto de las pruebas para los tests que usan
     * {@link PlanProduccionRepository#insertar}, que es el camino de la aplicación.
     *
     * @param fechaPlanificacion fecha de planificación del plan
     * @param fechaProduccion    fecha de producción del plan
     * @param estado             estado inicial del plan
     * @param observaciones      observaciones, o {@code null} sin ellas
     * @return el plan a persistir
     */
    private PlanProduccion nueva(LocalDate fechaPlanificacion, LocalDate fechaProduccion,
                                 EstadoPlan estado, String observaciones) {
        var plan = new PlanProduccion();
        plan.setFechaPlanificacion(fechaPlanificacion);
        plan.setFechaProduccion(fechaProduccion);
        plan.setEstado(estado);
        plan.setObservaciones(observaciones);
        plan.setIdUsuario(idUsuario);
        return plan;
    }

    /**
     * Inserta por el repositorio —el camino real de la aplicación— y registra el id para que
     * la limpieza borre el plan antes que su responsable.
     *
     * @param plan plan a persistir
     * @return identificador asignado por la base
     */
    private Integer insertarConRepositorio(PlanProduccion plan) {
        var id = repository.insertar(plan);
        idsPlan.add(id);
        return id;
    }

    /**
     * Siembra un plan por el repositorio con la fecha de producción que usan los filtros de
     * rango; registra el id para que la limpieza lo borre con su detalle.
     *
     * @param fechaProduccion fecha de producción a sembrar
     * @param estado          estado a sembrar
     * @param idUsuarioRespo  responsable del plan
     * @return identificador del plan sembrado
     */
    private Integer sembrarPlan(LocalDate fechaProduccion, EstadoPlan estado,
                                Integer idUsuarioRespo) {
        var plan = nueva(fechaProduccion.minusDays(1), fechaProduccion, estado, null);
        plan.setIdUsuario(idUsuarioRespo);
        return insertarConRepositorio(plan);
    }

    private Integer insertarLinea(Integer idPlan, Integer idProducto, String cantidad) {
        var id = insertarConId("""
                INSERT INTO detalle_plan_produccion (id_plan, id_producto, cantidad_planificada)
                VALUES (:idPlan, :idProducto, :cantidad)
                """, new MapSqlParameterSource()
                        .addValue("idPlan", idPlan)
                        .addValue("idProducto", idProducto)
                        .addValue("cantidad", new BigDecimal(cantidad)));
        idsDetalle.add(id);
        return id;
    }

    private Integer insertarUsuario(String nombre) {
        // La tabla roles la siembra V3 con los roles del sistema; el primero basta como FK.
        var idRol = jdbc.queryForObject(
                "SELECT id_rol FROM roles ORDER BY id_rol ASC LIMIT 1",
                new MapSqlParameterSource(), Integer.class);
        // El correo tiene índice único: un fragmento de UUID por fila evita colisiones entre
        // las dos altas de la misma prueba y entre corridas.
        return insertarConId("""
                INSERT INTO usuarios (nombre, correo, password, estado, id_rol)
                VALUES (:nombre, :correo, :password, 1, :idRol)
                """, new MapSqlParameterSource()
                        .addValue("nombre", nombre)
                        .addValue("correo", "h20-" + UUID.randomUUID().toString()
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

    /**
     * Página ordenada por id ascendente, el orden determinista de las pruebas de paginación.
     */
    private static PageRequest pagina(int page, int size) {
        return new PageRequest(page, size, "p.id_plan", true);
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
