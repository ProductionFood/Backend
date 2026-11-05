package co.edu.corposucre.productionfood.produccion;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import co.edu.corposucre.productionfood.common.pagination.PageRequest;

/**
 * Persistencia JDBC de los planes de producción ({@code planes_produccion}, HU-20).
 *
 * <p>Se usa JDBC y no JPA por la misma razón que el resto del proyecto: el modelo ya está
 * descrito por el esquema y no debe duplicarse en Hibernate.
 *
 * <p>Las lecturas llevan {@code JOIN} con {@code usuarios} para devolver el nombre del
 * responsable junto a cada plan, sin una consulta extra por fila, y cuentan las líneas del plan
 * con una subconsulta correlacionada sobre {@code detalle_plan_produccion} en el mismo
 * {@code SELECT}.
 *
 * <p>El {@code UPDATE} de estado toca solo la columna {@code estado}: <strong>ninguna
 * operación de este repositorio mueve inventario</strong> (R-06), porque el consumo lo registran
 * HU-22 y HU-23 y cancelar un plan no revierte lo ya consumido.
 */
@Repository
public class PlanProduccionRepository {

    /**
     * Lista blanca de ordenamiento del listado: traduce el campo que pide el cliente a la
     * columna SQL de {@code planes_produccion} (alias {@code p}). {@link PageRequest#of}
     * rechaza cualquier campo que no esté aquí, y la columna resuelta es la única que llega a
     * {@link #buscar}, de modo que el orden nunca es SQL arbitrario. El servicio de HU-20 arma
     * la página con estos mismos valores.
     */
    public static final Map<String, String> ORDEN = Map.of(
            "fechaProduccion", "p.fecha_produccion",
            "fechaPlanificacion", "p.fecha_planificacion",
            "estado", "p.estado",
            "id", "p.id_plan");

    /** Orden por defecto del listado: la producción más próxima primero. */
    public static final String ORDEN_POR_DEFECTO = "fechaProduccion,desc";

    /**
     * Origen de la consulta, compartido por {@link #buscar} y {@link #buscarPorId} para que el
     * listado y la vista de detalle devuelvan exactamente las mismas columnas.
     *
     * <p>El {@code INNER JOIN} con {@code usuarios} es seguro: la llave foránea
     * {@code planes_produccion.id_usuario} es {@code NOT NULL}, así que ningún plan queda
     * fuera. La última columna es una subconsulta correlacionada sobre el mismo plan: cuenta
     * sus líneas de {@code detalle_plan_produccion} sin traerlas, para que el listado muestre
     * cuántos productos tiene cada plan en la misma consulta. {@code COUNT(*)} nunca es nulo,
     * así que un plan recién creado responde {@code 0}.
     */
    private static final String SELECT = """
            SELECT p.id_plan, p.fecha_planificacion, p.fecha_produccion, p.estado,
                   p.observaciones, p.id_usuario,
                   u.nombre AS nombre_responsable,
                   (SELECT COUNT(*) FROM detalle_plan_produccion d
                    WHERE d.id_plan = p.id_plan) AS cantidad_productos
            FROM planes_produccion p
            JOIN usuarios u ON u.id_usuario = p.id_usuario
            """;

    private static final PlanProduccionRowMapper FILA = new PlanProduccionRowMapper();

    private final NamedParameterJdbcTemplate jdbc;

    public PlanProduccionRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Devuelve una página de planes con el nombre de su responsable y sus líneas contadas.
     *
     * <p>Los cuatro filtros son opcionales y se acumulan. El orden se aplica sobre la
     * propiedad que resuelve {@link PageRequest#of} desde {@link #ORDEN} —nunca sobre texto
     * del usuario— y siempre se desempata por {@code p.id_plan DESC} para que un empate no
     * deje la página indeterminada entre llamadas.
     *
     * <p>Una propiedad fuera de {@link #ORDEN} —una página construida a mano o un mapa de
     * orden desactualizado— cae al orden por defecto en vez de concatenarse en el
     * {@code ORDER BY}: una columna inexistente responde {@code ERROR 1054} y un 500, el borde
     * que ya dolió en HU-15. La defensa es redundante con {@link PageRequest#of} cuando el
     * servicio arma la página con {@link #ORDEN}, y garantiza además que {@link #buscar} nunca
     * recibe SQL de ordenamiento arbitrario.
     *
     * @param estado             estado a filtrar, o {@code null} sin filtrar
     * @param idUsuario          responsable cuyos planes se listan, o {@code null} sin filtrar
     * @param fechaProduccionDesde primer día incluido del rango de {@code fecha_produccion},
     *                            o {@code null} sin límite inferior
     * @param fechaProduccionHasta último día incluido del rango de {@code fecha_produccion},
     *                            o {@code null} sin límite superior
     * @param page               página solicitada, con el orden ya resuelto
     * @return planes de la página, cada uno con su responsable y sus líneas contadas
     */
    public List<PlanProduccion> buscar(EstadoPlan estado, Integer idUsuario,
                                       LocalDate fechaProduccionDesde,
                                       LocalDate fechaProduccionHasta, PageRequest page) {
        var params = new MapSqlParameterSource();
        var where = armarFiltros(estado, idUsuario, fechaProduccionDesde,
                fechaProduccionHasta, params);
        long offset = (long) page.page() * page.size();
        params.addValue("tamano", page.size()).addValue("desplazamiento", offset);

        var sql = SELECT + where
                + " ORDER BY " + columnaOrden(page) + (ascendente(page) ? " ASC" : " DESC")
                + ", p.id_plan DESC"
                + " LIMIT :tamano OFFSET :desplazamiento";

        return jdbc.query(sql, params, FILA);
    }

    /**
     * Cuenta los planes que cumplen los mismos filtros que {@link #buscar}.
     *
     * <p>El {@code COUNT} no necesita ni el {@code JOIN} con {@code usuarios} ni la
     * subconsulta de líneas: todos los filtros caen sobre columnas de
     * {@code planes_produccion}, así que el total no diverge de la página.
     *
     * @param estado               estado a filtrar, o {@code null} sin filtrar
     * @param idUsuario            responsable cuyos planes se cuentan, o {@code null} sin filtrar
     * @param fechaProduccionDesde primer día incluido del rango de {@code fecha_produccion},
     *                            o {@code null} sin límite inferior
     * @param fechaProduccionHasta último día incluido del rango de {@code fecha_produccion},
     *                            o {@code null} sin límite superior
     * @return total de planes que cumplen los filtros
     */
    public long contar(EstadoPlan estado, Integer idUsuario, LocalDate fechaProduccionDesde,
                       LocalDate fechaProduccionHasta) {
        var params = new MapSqlParameterSource();
        var where = armarFiltros(estado, idUsuario, fechaProduccionDesde,
                fechaProduccionHasta, params);

        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM planes_produccion p " + where, params, Long.class);
        return total == null ? 0L : total;
    }

    /**
     * Devuelve un plan por su identificador, con el nombre de su responsable y sus líneas
     * contadas.
     *
     * @param id identificador del plan
     * @return el plan, o vacío si no existe
     */
    public Optional<PlanProduccion> buscarPorId(Integer id) {
        var params = new MapSqlParameterSource().addValue("id", id);
        return jdbc.query(SELECT + " WHERE p.id_plan = :id", params, FILA)
                .stream().findFirst();
    }

    /**
     * Inserta la cabecera de un plan y devuelve el identificador que asignó la base.
     *
     * <p>Lleva las dos fechas, el estado, las observaciones y el responsable: a diferencia de
     * {@code pedidos}, ninguna columna de {@code planes_produccion} tiene {@code DEFAULT}, así
     * que la aplicación sí envía las fechas. El estado viaja como su nombre canónico, que es el
     * literal del {@code CHECK ck_planes_estado}; la inserción siempre es {@code PLANIFICADO}
     * (CA-02/CP-06), valor que fija el servicio.
     *
     * <p>No lleva {@code @Transactional}: la transacción la abre el servicio.
     *
     * @param plan plan a persistir; debe traer fechas, estado y responsable
     * @return identificador asignado al plan
     */
    public Integer insertar(PlanProduccion plan) {
        var params = new MapSqlParameterSource()
                .addValue("fechaPlanificacion", plan.getFechaPlanificacion())
                .addValue("fechaProduccion", plan.getFechaProduccion())
                .addValue("estado", plan.getEstado().name())
                .addValue("observaciones", plan.getObservaciones())
                .addValue("idUsuario", plan.getIdUsuario());

        var generadas = new GeneratedKeyHolder();
        jdbc.update("""
                INSERT INTO planes_produccion (fecha_planificacion, fecha_produccion, estado,
                                               observaciones, id_usuario)
                VALUES (:fechaPlanificacion, :fechaProduccion, :estado, :observaciones,
                        :idUsuario)
                """, params, generadas, new String[] {"id_plan"});

        Integer id = claveGenerada(generadas);
        if (id == null) {
            throw new IllegalStateException(
                    "La base no devolvió el id del plan insertado.");
        }
        return id;
    }

    /**
     * Actualiza los campos editables de la cabecera: las dos fechas, las observaciones y el
     * responsable.
     *
     * <p>No toca el estado: la máquina de transiciones de {@link EstadoPlan} vive en el
     * servicio, con su propio endpoint (HU-20 R-01), y {@code id_plan} es inmutable. Validar
     * que el plan siga {@code PLANIFICADO} antes de editar (R-05) es responsabilidad del
     * servicio, no de la persistencia.
     *
     * @param plan con los valores nuevos; debe traer id, fechas, observaciones y responsable
     */
    public void actualizar(PlanProduccion plan) {
        var params = new MapSqlParameterSource()
                .addValue("id", plan.getIdPlan())
                .addValue("fechaPlanificacion", plan.getFechaPlanificacion())
                .addValue("fechaProduccion", plan.getFechaProduccion())
                .addValue("observaciones", plan.getObservaciones())
                .addValue("idUsuario", plan.getIdUsuario());

        jdbc.update("""
                UPDATE planes_produccion
                SET fecha_planificacion = :fechaPlanificacion,
                    fecha_produccion = :fechaProduccion,
                    observaciones = :observaciones,
                    id_usuario = :idUsuario
                WHERE id_plan = :id
                """, params);
    }

    /**
     * Cambia el estado de un plan.
     *
     * <p>La máquina de transiciones de {@link EstadoPlan} la valida el servicio (R-01); aquí
     * solo se escribe el estado nuevo. <strong>No toca inventario ni ningún otro campo
     * (R-06)</strong>: cancelar un plan no devuelve el material ya consumido, y completarlo no
     * genera lotes (R-07) —el movimiento lo registran HU-22 y HU-23—.
     *
     * <p>No lleva {@code @Transactional}: la transacción la abre el servicio.
     *
     * @param id     identificador del plan
     * @param estado estado nuevo
     */
    public void cambiarEstado(Integer id, EstadoPlan estado) {
        var params = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("estado", estado.name());

        jdbc.update("UPDATE planes_produccion SET estado = :estado WHERE id_plan = :id",
                params);
    }

    /**
     * Cuenta las líneas de detalle de un plan, sin traerlas.
     *
     * <p>Es la misma cifra que la subconsulta {@code cantidad_productos} de {@link #SELECT},
     * pero resuelta contra la base en el momento: útil para R-02 —no iniciar un plan sin
     * productos— después de persistir las líneas de HU-21, cuando el valor derivado del plan
     * leído antes del {@code INSERT} ya está desactualizado.
     *
     * @param idPlan identificador del plan
     * @return número de líneas de {@code detalle_plan_produccion} de ese plan; {@code 0} si no
     *         tiene
     */
    public int contarDetalles(Integer idPlan) {
        var params = new MapSqlParameterSource().addValue("idPlan", idPlan);

        Integer total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM detalle_plan_produccion WHERE id_plan = :idPlan",
                params, Integer.class);
        return total == null ? 0 : total;
    }

    /**
     * Fragmento {@code WHERE} compartido por {@link #buscar} y {@link #contar}, para que todos
     * apliquen exactamente los mismos filtros y el total no diverja de la página.
     *
     * <p>Los cuatro filtros son opcionales y se acumulan. Solo se concatenan fragmentos
     * constantes; los valores viajan como parámetros con nombre. Las columnas de fecha son
     * {@code date}, así que el {@link LocalDate} viaja sin convertir. El filtro por responsable
     * se aplica sobre {@code p.id_usuario}: no hace falta pasar por {@code usuarios} para
     * filtrar por la llave.
     */
    private static String armarFiltros(EstadoPlan estado, Integer idUsuario,
                                       LocalDate fechaProduccionDesde,
                                       LocalDate fechaProduccionHasta,
                                       MapSqlParameterSource params) {
        var where = new StringBuilder(" WHERE 1=1");
        if (estado != null) {
            where.append(" AND p.estado = :estado");
            params.addValue("estado", estado.name());
        }
        if (idUsuario != null) {
            where.append(" AND p.id_usuario = :idUsuario");
            params.addValue("idUsuario", idUsuario);
        }
        if (fechaProduccionDesde != null) {
            where.append(" AND p.fecha_produccion >= :fechaProduccionDesde");
            params.addValue("fechaProduccionDesde", fechaProduccionDesde);
        }
        if (fechaProduccionHasta != null) {
            where.append(" AND p.fecha_produccion <= :fechaProduccionHasta");
            params.addValue("fechaProduccionHasta", fechaProduccionHasta);
        }
        return where.toString();
    }

    /**
     * Resuelve la columna de orden a partir de la propiedad ya traducida por la lista blanca.
     *
     * <p>Si la propiedad no pertenece a {@link #ORDEN} se usa la columna del orden por defecto,
     * en lugar de concatenar una columna desconocida en el {@code ORDER BY}.
     *
     * @param page página cuyo orden se va a aplicar
     * @return columna SQL del orden, con su alias {@code p}
     */
    private static String columnaOrden(PageRequest page) {
        return ORDEN.containsValue(page.propiedad()) ? page.propiedad() : "p.fecha_produccion";
    }

    /**
     * Dirección del orden: la que pidió la página, o {@code false} —descendente— cuando la
     * propiedad cayó al orden por defecto, que es {@code fechaProduccion,desc} completo.
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
     * conversión de tipos no está soportada. Se toma como {@link Number} y se reduce al tipo
     * de la columna.
     */
    private static Integer claveGenerada(GeneratedKeyHolder generadas) {
        Number clave = generadas.getKey();
        return clave == null ? null : clave.intValue();
    }
}
