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
 * Persistencia JDBC del detalle de los planes de producción
 * ({@code detalle_plan_produccion}, HU-21).
 *
 * <p>Es la contraparte de {@link PlanProduccionRepository} para el patrón cabecera-detalle de los
 * planes: aquí se escriben y se leen las líneas, mientras la cabecera solo las cuenta para su
 * derivado {@code cantidad_productos}. Se usa JDBC y no JPA por la misma razón que el resto del
 * proyecto: el modelo ya está descrito por el esquema y no debe duplicarse en Hibernate.
 *
 * <p>Las lecturas llevan {@code JOIN} con {@code productos} y {@code unidades_medida} para
 * devolver el nombre del producto y la abreviatura de su unidad junto a cada línea, sin una
 * consulta extra por fila. El {@code LEFT JOIN} con {@code recetas} aporta {@code tieneReceta}:
 * es un {@code LEFT} y no un {@code INNER} porque un producto puede no tener receta y la línea
 * debe seguir apareciendo.
 *
 * <p>El {@code INSERT} no envía {@code cantidad_producida}: la columna es
 * {@code NOT NULL DEFAULT 0.00}, así que la base la inicializa y la aplicación no puede olvidar
 * el valor cero.
 */
@Repository
public class DetallePlanRepository {

    /**
     * Lista blanca de ordenamiento del detalle: traduce el campo que pide el cliente a la columna
     * —o expresión— SQL de la consulta. {@link PageRequest#of} rechaza cualquier campo que no
     * esté aquí, y el valor resuelto es el único que llega a {@link #listarPorPlan}, de modo que
     * el orden nunca es SQL arbitrario. La propiedad {@code avance} es la relación entre lo
     * producido y lo planificado, calculada en la misma consulta.
     */
    static final Map<String, String> ORDEN = Map.of(
            "producto", "p.nombre",
            "cantidadPlanificada", "d.cantidad_planificada",
            "avance", "(d.cantidad_producida / d.cantidad_planificada)");

    /**
     * Origen de la consulta, compartido por todas las lecturas para que el listado, el detalle y
     * la búsqueda bloqueada devuelvan exactamente las mismas columnas.
     *
     * <p>Los dos {@code INNER JOIN} son seguros: {@code id_producto} e {@code id_unidad} son
     * llaves foráneas {@code NOT NULL} y no descartan ninguna línea. El {@code LEFT JOIN} con
     * {@code recetas} no duplica filas porque {@code recetas} tiene una llave única por
     * {@code id_producto}.
     */
    private static final String SELECT = """
            SELECT d.id_detalle_plan, d.id_plan, d.id_producto,
                   p.nombre AS nombre_producto, u.abreviatura AS unidad,
                   d.cantidad_planificada, d.cantidad_producida,
                   (r.id_receta IS NOT NULL) AS tiene_receta
            FROM detalle_plan_produccion d
            JOIN productos p ON p.id_producto = d.id_producto
            JOIN unidades_medida u ON u.id_unidad = p.id_unidad
            LEFT JOIN recetas r ON r.id_producto = d.id_producto
            """;

    private static final DetallePlanRowMapper FILA = new DetallePlanRowMapper();

    private final NamedParameterJdbcTemplate jdbc;

    public DetallePlanRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Devuelve una página de líneas de un plan con el nombre de su producto y la abreviatura de
     * su unidad.
     *
     * <p>El orden se aplica sobre la columna que resolvió {@link PageRequest#of} desde
     * {@link #ORDEN} —nunca sobre texto del usuario— y siempre se desempata por
     * {@code d.id_detalle_plan ASC} para que dos líneas con el mismo valor de orden no dejen la
     * página indeterminada entre llamadas.
     *
     * @param idPlan identificador del plan cuyas líneas se listan
     * @param page   página solicitada, con el orden ya resuelto
     * @return líneas de la página, con producto y unidad; vacía si el plan no tiene líneas
     */
    public List<DetallePlanProduccion> listarPorPlan(Integer idPlan, PageRequest page) {
        var params = new MapSqlParameterSource().addValue("idPlan", idPlan);
        long offset = (long) page.page() * page.size();
        params.addValue("tamano", page.size()).addValue("desplazamiento", offset);

        var sql = SELECT
                + " WHERE d.id_plan = :idPlan"
                + " ORDER BY " + columnaOrden(page) + (ascendente(page) ? " ASC" : " DESC")
                + ", d.id_detalle_plan ASC"
                + " LIMIT :tamano OFFSET :desplazamiento";

        return jdbc.query(sql, params, FILA);
    }

    /**
     * Cuenta las líneas de un plan, sin traerlas.
     *
     * <p>El {@code COUNT} no necesita ninguno de los {@code JOIN} del {@link #SELECT}: el filtro
     * cae sobre {@code detalle_plan_produccion}, así que el total no diverge de la página.
     *
     * @param idPlan identificador del plan
     * @return número de líneas del plan; {@code 0} si no tiene
     */
    public long contarPorPlan(Integer idPlan) {
        var params = new MapSqlParameterSource().addValue("idPlan", idPlan);
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM detalle_plan_produccion WHERE id_plan = :idPlan",
                params, Long.class);
        return total == null ? 0L : total;
    }

    /**
     * Devuelve una línea de detalle por su identificador, con los datos de su producto y su
     * unidad.
     *
     * @param idDetalle identificador de la línea
     * @return la línea, o vacío si no existe
     */
    public Optional<DetallePlanProduccion> buscarPorId(Integer idDetalle) {
        var params = new MapSqlParameterSource().addValue("id", idDetalle);
        return jdbc.query(SELECT + " WHERE d.id_detalle_plan = :id", params, FILA)
                .stream().findFirst();
    }

    /**
     * Devuelve una línea de detalle con {@code SELECT ... FOR UPDATE}, para borrarla sin carreras
     * con el consumo de materia prima de HU-22.
     *
     * <p>El bloqueo solo vive dentro de la transacción del llamador: quien invoca este método
     * debe hacerlo con una transacción abierta, porque sin ella MySQL confirma al terminar la
     * sentencia y el {@code FOR UPDATE} deja de proteger la operación. No abre transacción por sí
     * mismo: la abre el servicio, igual que en el resto del módulo.
     *
     * @param idDetalle identificador de la línea
     * @return la línea bloqueada, o vacío si no existe
     */
    public Optional<DetallePlanProduccion> buscarPorIdParaActualizar(Integer idDetalle) {
        var params = new MapSqlParameterSource().addValue("id", idDetalle);
        return jdbc.query(SELECT + " WHERE d.id_detalle_plan = :id FOR UPDATE", params, FILA)
                .stream().findFirst();
    }

    /**
     * Inserta una línea de detalle y devuelve el identificador que asignó la base.
     *
     * <p>Solo envía {@code id_plan}, {@code id_producto} y {@code cantidad_planificada}:
     * {@code cantidad_producida} se omite a propósito para que aplique el {@code DEFAULT 0.00}
     * del esquema.
     *
     * <p>El {@code UNIQUE KEY uk_detalle_plan (id_plan, id_producto)} respalda la validación del
     * servicio: {@link #existeProducto} permite devolver un error legible antes de intentar el
     * {@code INSERT}, pero dos peticiones simultáneas pueden pasar ambas la comprobación y solo
     * la base las detiene.
     *
     * <p>No lleva {@code @Transactional}: la transacción la abre el servicio.
     *
     * @param detalle línea a persistir; debe traer plan, producto y cantidad planificada
     * @return identificador asignado a la línea
     */
    public Integer insertar(DetallePlanProduccion detalle) {
        var params = new MapSqlParameterSource()
                .addValue("idPlan", detalle.getIdPlan())
                .addValue("idProducto", detalle.getIdProducto())
                .addValue("cantidadPlanificada", detalle.getCantidadPlanificada());

        var generadas = new GeneratedKeyHolder();
        jdbc.update("""
                INSERT INTO detalle_plan_produccion (id_plan, id_producto, cantidad_planificada)
                VALUES (:idPlan, :idProducto, :cantidadPlanificada)
                """, params, generadas, new String[] {"id_detalle_plan"});

        Integer id = claveGenerada(generadas);
        if (id == null) {
            throw new IllegalStateException(
                    "La base no devolvió el id de la línea del plan insertada.");
        }
        return id;
    }

    /**
     * Reemplaza la cantidad planificada de una línea.
     *
     * <p>Es el compromiso de producción de HU-26. La regla de que solo se edita mientras el plan
     * está {@code PLANIFICADO} la valida el servicio antes de llamar aquí; el repositorio solo
     * escribe la columna.
     *
     * @param idDetalle identificador de la línea
     * @param cantidad  nueva cantidad planificada, mayor que cero
     * @return número de filas afectadas: {@code 1} si la línea existe, {@code 0} si no
     */
    public int actualizarPlanificada(Integer idDetalle, BigDecimal cantidad) {
        var params = new MapSqlParameterSource()
                .addValue("id", idDetalle)
                .addValue("cantidad", cantidad);

        return jdbc.update("""
                UPDATE detalle_plan_produccion
                SET cantidad_planificada = :cantidad
                WHERE id_detalle_plan = :id
                """, params);
    }

    /**
     * Reemplaza la cantidad producida de una línea, que es el avance real de la producción.
     *
     * @param idDetalle identificador de la línea
     * @param cantidad  nueva cantidad producida, mayor o igual que cero
     * @return número de filas afectadas: {@code 1} si la línea existe, {@code 0} si no
     */
    public int actualizarProducida(Integer idDetalle, BigDecimal cantidad) {
        var params = new MapSqlParameterSource()
                .addValue("id", idDetalle)
                .addValue("cantidad", cantidad);

        return jdbc.update("""
                UPDATE detalle_plan_produccion
                SET cantidad_producida = :cantidad
                WHERE id_detalle_plan = :id
                """, params);
    }

    /**
     * Elimina una línea de detalle.
     *
     * <p>El servicio debe comprobar antes con {@link #tieneConsumos} que la línea no tenga
     * consumos de materia prima: la llave foránea de {@code consumo_materia_prima} no es
     * {@code ON DELETE CASCADE} y la base rechazaría el borrado.
     *
     * @param idDetalle identificador de la línea
     * @return número de filas afectadas: {@code 1} si se borró, {@code 0} si no existía
     */
    public int eliminar(Integer idDetalle) {
        var params = new MapSqlParameterSource().addValue("id", idDetalle);
        return jdbc.update(
                "DELETE FROM detalle_plan_produccion WHERE id_detalle_plan = :id", params);
    }

    /**
     * Indica si el producto ya está en el plan.
     *
     * <p>Lo usa el servicio para responder el duplicado antes de intentar el {@code INSERT}; el
     * {@code UNIQUE KEY uk_detalle_plan (id_plan, id_producto)} es la garantía ante dos peticiones
     * simultáneas.
     *
     * @param idPlan     identificador del plan
     * @param idProducto identificador del producto
     * @return {@code true} si el producto ya es una línea del plan
     */
    public boolean existeProducto(Integer idPlan, Integer idProducto) {
        var params = new MapSqlParameterSource()
                .addValue("idPlan", idPlan)
                .addValue("idProducto", idProducto);

        Long total = jdbc.queryForObject("""
                SELECT COUNT(*) FROM detalle_plan_produccion
                WHERE id_plan = :idPlan AND id_producto = :idProducto
                """, params, Long.class);
        return total != null && total > 0;
    }

    /**
     * Indica si la línea ya registró consumo de materia prima.
     *
     * <p>Protege el borrado de HU-21 frente a HU-22: una línea con consumos no puede eliminarse
     * sin dejar huérfano el registro de material, y la llave foránea de
     * {@code consumo_materia_prima} tampoco lo permitiría.
     *
     * @param idDetalle identificador de la línea
     * @return {@code true} si la línea tiene al menos un consumo registrado
     */
    public boolean tieneConsumos(Integer idDetalle) {
        var params = new MapSqlParameterSource().addValue("id", idDetalle);

        Long total = jdbc.queryForObject("""
                SELECT COUNT(*) FROM consumo_materia_prima
                WHERE id_detalle_plan = :id
                """, params, Long.class);
        return total != null && total > 0;
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
     * @return columna o expresión SQL del orden
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
