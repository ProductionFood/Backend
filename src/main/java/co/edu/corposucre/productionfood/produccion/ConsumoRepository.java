package co.edu.corposucre.productionfood.produccion;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import co.edu.corposucre.productionfood.common.pagination.PageRequest;

/**
 * Persistencia JDBC del consumo real de materia prima ({@code consumo_materia_prima}, HU-22).
 *
 * <p>Es la contraparte de {@link DetallePlanRepository} para el registro de materiales: cada
 * fila asocia una línea del plan con una materia prima y guarda cuánto se calculó
 * ({@code cantidad_teorica}) y cuánto se gastó ({@code cantidad_real}). Se usa JDBC y no JPA por
 * la misma razón que el resto del proyecto: el modelo ya está descrito por el esquema y no debe
 * duplicarse en Hibernate.
 *
 * <p>El {@link #SELECT} es compartido por todas las lecturas y une
 * {@code detalle_plan_produccion}, {@code productos}, {@code planes_produccion},
 * {@code materias_primas} y {@code unidades_medida} para devolver el plan, el producto, la
 * materia prima, su unidad y el estado del plan en la misma consulta, sin una llamada extra por
 * fila. Todos los {@code JOIN} son {@code INNER} porque las llaves foráneas que siguen son
 * {@code NOT NULL} y no descartan ninguna fila.
 *
 * <p>Ningún método lleva {@code @Transactional}: la transacción la abre el servicio, igual que en
 * el resto del módulo. El {@code UNIQUE KEY uk_consumo_mp (id_detalle_plan, id_materia_prima)}
 * es la garantía ante duplicados concurrentes: {@link #existeConsumo} permite responder el
 * duplicado antes de intentar el {@code INSERT}, pero dos peticiones simultáneas pueden pasar
 * ambas la comprobación y solo la base las detiene.
 */
@Repository
public class ConsumoRepository {

    /**
     * Lista blanca de ordenamiento del consumo: traduce el campo que pide el cliente a la columna
     * SQL de la consulta. {@link PageRequest#of} rechaza cualquier campo que no esté aquí, y el
     * valor resuelto es el único que llega a {@link #listarPorPlan}, de modo que el orden nunca es
     * SQL arbitrario.
     */
    static final Map<String, String> ORDEN = Map.of(
            "producto", "p.nombre",
            "materiaPrima", "mp.nombre",
            "cantidadTeorica", "c.cantidad_teorica",
            "cantidadReal", "c.cantidad_real",
            "fechaRegistro", "c.fecha_registro");

    /**
     * Orden por defecto del listado: producto ascendente, el mismo que usan los demás detalles del
     * sistema.
     */
    static final String ORDEN_POR_DEFECTO = "producto,asc";

    /**
     * Origen de la consulta, compartido por todas las lecturas para que el listado, el detalle y
     * la búsqueda bloqueada devuelvan exactamente las mismas columnas.
     *
     * <p>Los alias {@code nombre_producto}, {@code nombre_materia_prima}, {@code unidad} y
     * {@code estado_plan} son las proyecciones de los {@code JOIN} que consume
     * {@link ConsumoRowMapper}; {@code id_plan} sale de la línea del plan porque
     * {@code consumo_materia_prima} no lo almacena.
     */
    private static final String SELECT = """
            SELECT c.id_consumo, c.id_detalle_plan, d.id_plan,
                   p.nombre AS nombre_producto,
                   c.id_materia_prima, mp.nombre AS nombre_materia_prima,
                   u.abreviatura AS unidad,
                   c.cantidad_teorica, c.cantidad_real, c.fecha_registro,
                   pl.estado AS estado_plan
            FROM consumo_materia_prima c
            JOIN detalle_plan_produccion d ON d.id_detalle_plan = c.id_detalle_plan
            JOIN productos p ON p.id_producto = d.id_producto
            JOIN planes_produccion pl ON pl.id_plan = d.id_plan
            JOIN materias_primas mp ON mp.id_materia_prima = c.id_materia_prima
            JOIN unidades_medida u ON u.id_unidad = mp.id_unidad
            """;

    private static final ConsumoRowMapper FILA = new ConsumoRowMapper();

    private final NamedParameterJdbcTemplate jdbc;

    public ConsumoRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Devuelve una página de consumos de un plan con el producto, la materia prima, su unidad y el
     * estado del plan.
     *
     * <p>El orden se aplica sobre la columna que resolvió {@link PageRequest#of} desde
     * {@link #ORDEN} —nunca sobre texto del usuario— y siempre se desempata por
     * {@code c.id_consumo ASC} para que dos filas con el mismo valor de orden no dejen la página
     * indeterminada entre llamadas.
     *
     * @param idPlan identificador del plan cuyos consumos se listan
     * @param page   página solicitada, con el orden ya resuelto
     * @return consumos de la página; vacía si el plan no tiene consumos
     */
    public List<ConsumoMateriaPrima> listarPorPlan(Integer idPlan, PageRequest page) {
        var params = new MapSqlParameterSource().addValue("idPlan", idPlan);
        long offset = (long) page.page() * page.size();
        params.addValue("tamano", page.size()).addValue("desplazamiento", offset);

        var sql = SELECT
                + " WHERE d.id_plan = :idPlan"
                + " ORDER BY " + columnaOrden(page) + (ascendente(page) ? " ASC" : " DESC")
                + ", c.id_consumo ASC"
                + " LIMIT :tamano OFFSET :desplazamiento";

        return jdbc.query(sql, params, FILA);
    }

    /**
     * Cuenta los consumos de un plan, sin traerlos.
     *
     * <p>El {@code COUNT} solo necesita la línea del plan, porque
     * {@code consumo_materia_prima} no almacena {@code id_plan}: el total no diverge de la página
     * que filtra por la misma columna.
     *
     * @param idPlan identificador del plan
     * @return número de consumos del plan; {@code 0} si no tiene
     */
    public long contarPorPlan(Integer idPlan) {
        var params = new MapSqlParameterSource().addValue("idPlan", idPlan);
        Long total = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM consumo_materia_prima c
                JOIN detalle_plan_produccion d ON d.id_detalle_plan = c.id_detalle_plan
                WHERE d.id_plan = :idPlan
                """, params, Long.class);
        return total == null ? 0L : total;
    }

    /**
     * Devuelve los consumos de una línea de detalle en el orden en que se registraron.
     *
     * @param idDetallePlan identificador de la línea del plan
     * @return consumos de la línea, ordenados por {@code id_consumo}; vacía si no tiene
     */
    public List<ConsumoMateriaPrima> listarPorDetallePlan(Integer idDetallePlan) {
        var params = new MapSqlParameterSource().addValue("id", idDetallePlan);
        return jdbc.query(SELECT
                + " WHERE c.id_detalle_plan = :id"
                + " ORDER BY c.id_consumo ASC", params, FILA);
    }

    /**
     * Devuelve un consumo por su identificador, con los datos de su línea, su producto y su
     * materia prima.
     *
     * @param id identificador del consumo
     * @return el consumo, o vacío si no existe
     */
    public Optional<ConsumoMateriaPrima> buscarPorId(Integer id) {
        var params = new MapSqlParameterSource().addValue("id", id);
        return jdbc.query(SELECT + " WHERE c.id_consumo = :id", params, FILA)
                .stream().findFirst();
    }

    /**
     * Devuelve un consumo con {@code SELECT ... FOR UPDATE}, para modificarlo sin carreras con
     * otra actualización del mismo registro.
     *
     * <p>El bloqueo solo vive dentro de la transacción del llamador: quien invoca este método
     * debe hacerlo con una transacción abierta, porque sin ella MySQL confirma al terminar la
     * sentencia y el {@code FOR UPDATE} deja de proteger la operación. No abre transacción por sí
     * mismo: la abre el servicio, igual que en el resto del módulo.
     *
     * @param id identificador del consumo
     * @return el consumo bloqueado, o vacío si no existe
     */
    public Optional<ConsumoMateriaPrima> buscarPorIdParaActualizar(Integer id) {
        var params = new MapSqlParameterSource().addValue("id", id);
        return jdbc.query(SELECT + " WHERE c.id_consumo = :id FOR UPDATE", params, FILA)
                .stream().findFirst();
    }

    /**
     * Indica si la línea ya registró consumo de esa materia prima.
     *
     * <p>Lo usa el servicio para responder el duplicado antes de intentar el {@code INSERT}; el
     * {@code UNIQUE KEY uk_consumo_mp (id_detalle_plan, id_materia_prima)} es la garantía ante
     * dos peticiones simultáneas.
     *
     * @param idDetallePlan  identificador de la línea del plan
     * @param idMateriaPrima identificador de la materia prima
     * @return {@code true} si ya existe el consumo para esa línea y materia prima
     */
    public boolean existeConsumo(Integer idDetallePlan, Integer idMateriaPrima) {
        var params = new MapSqlParameterSource()
                .addValue("idDetallePlan", idDetallePlan)
                .addValue("idMateriaPrima", idMateriaPrima);

        Long total = jdbc.queryForObject("""
                SELECT COUNT(*) FROM consumo_materia_prima
                WHERE id_detalle_plan = :idDetallePlan
                  AND id_materia_prima = :idMateriaPrima
                """, params, Long.class);
        return total != null && total > 0;
    }

    /**
     * Inserta un consumo y devuelve el identificador que asignó la base.
     *
     * <p>Solo envía {@code id_detalle_plan}, {@code id_materia_prima}, {@code cantidad_teorica} y
     * {@code cantidad_real}: {@code fecha_registro} se omite a propósito para que aplique el
     * {@code DEFAULT CURRENT_TIMESTAMP} del esquema.
     *
     * <p>No lleva {@code @Transactional}: la transacción la abre el servicio. El
     * {@code UNIQUE KEY uk_consumo_mp} rechaza el duplicado aunque la comprobación previa de
     * {@link #existeConsumo} haya pasado.
     *
     * @param consumo consumo a persistir; debe traer línea, materia prima y ambas cantidades
     * @return identificador asignado al consumo
     */
    public Integer insertar(ConsumoMateriaPrima consumo) {
        var params = new MapSqlParameterSource()
                .addValue("idDetallePlan", consumo.getIdDetallePlan())
                .addValue("idMateriaPrima", consumo.getIdMateriaPrima())
                .addValue("cantidadTeorica", consumo.getCantidadTeorica())
                .addValue("cantidadReal", consumo.getCantidadReal());

        var generadas = new GeneratedKeyHolder();
        jdbc.update("""
                INSERT INTO consumo_materia_prima
                    (id_detalle_plan, id_materia_prima, cantidad_teorica, cantidad_real)
                VALUES (:idDetallePlan, :idMateriaPrima, :cantidadTeorica, :cantidadReal)
                """, params, generadas, new String[] {"id_consumo"});

        Integer id = claveGenerada(generadas);
        if (id == null) {
            throw new IllegalStateException(
                    "La base no devolvió el id del consumo de materia prima insertado.");
        }
        return id;
    }

    /**
     * Reemplaza la cantidad real de un consumo.
     *
     * <p>Es la corrección del consumo registrado: la regla de que solo se edita mientras el plan
     * está {@code EN_PROCESO} la valida el servicio antes de llamar aquí; el repositorio solo
     * escribe la columna, sin tocar la teórica ni la fecha de registro.
     *
     * @param idConsumo   identificador del consumo
     * @param cantidadReal nueva cantidad real, mayor o igual que cero
     * @return número de filas afectadas: {@code 1} si el consumo existe, {@code 0} si no
     */
    public int actualizarReal(Integer idConsumo, BigDecimal cantidadReal) {
        var params = new MapSqlParameterSource()
                .addValue("id", idConsumo)
                .addValue("cantidadReal", cantidadReal);

        return jdbc.update("""
                UPDATE consumo_materia_prima
                SET cantidad_real = :cantidadReal
                WHERE id_consumo = :id
                """, params);
    }

    /**
     * Resuelve la columna de orden a partir de la propiedad ya traducida por la lista blanca.
     *
     * <p>Si la propiedad no pertenece a {@link #ORDEN} se usa el nombre del producto, en lugar de
     * concatenar una columna desconocida en el {@code ORDER BY}: una columna inexistente responde
     * {@code ERROR 1054} y un 500. La defensa es redundante con {@link PageRequest#of} cuando el
     * servicio arma la página con {@link #ORDEN}, y garantiza además que {@link #listarPorPlan}
     * nunca recibe SQL de ordenamiento arbitrario.
     *
     * @param page página cuyo orden se va a aplicar
     * @return columna SQL del orden
     */
    private static String columnaOrden(PageRequest page) {
        return ORDEN.containsValue(page.propiedad()) ? page.propiedad() : "p.nombre";
    }

    /**
     * Dirección del orden: la que pidió la página, o {@code true} —ascendente— cuando la
     * propiedad cayó al orden por defecto.
     *
     * @param page página cuyo orden se va a aplicar
     * @return {@code true} para orden ascendente, {@code false} para descendente
     */
    private static boolean ascendente(PageRequest page) {
        return ORDEN.containsValue(page.propiedad()) && page.ascendente();
    }

    /**
     * Extrae la llave generada como {@link Integer}.
     *
     * <p>El conector de MySQL devuelve la llave como {@code BigInteger}; pedirla con
     * {@code getKeyAs(Integer.class)} lanza {@code DataRetrievalFailureException} porque la
     * conversión de tipos no está soportada. Se toma como {@link Number} y se reduce al tipo de
     * la columna.
     */
    private static Integer claveGenerada(GeneratedKeyHolder generadas) {
        Number clave = generadas.getKey();
        return clave == null ? null : clave.intValue();
    }
}
