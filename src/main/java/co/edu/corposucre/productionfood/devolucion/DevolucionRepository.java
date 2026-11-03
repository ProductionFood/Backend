package co.edu.corposucre.productionfood.devolucion;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import co.edu.corposucre.productionfood.common.pagination.PageRequest;

/**
 * Persistencia JDBC de las devoluciones ({@code devoluciones}, HU-18).
 *
 * <p>Se usa JDBC y no JPA por la misma razón que el resto del proyecto: el modelo ya está
 * descrito por el esquema y no debe duplicarse en Hibernate. El acumulado de devoluciones por
 * línea se calcula en la base con una subconsulta correlacionada, en vez de cargar todas las
 * filas para sumarlas en memoria (R-03).
 *
 * <p>El {@code INSERT} no lleva {@code fecha_devolucion}: la columna es
 * {@code NOT NULL DEFAULT (curdate())} desde V2 (§C-07), así que la fecha la pone la base y la
 * aplicación nunca la envía (R-06). El {@code UPDATE} de estado tampoco la toca: una vez
 * registrada es inmutable.
 *
 * <p>Las lecturas llevan {@code JOIN} con {@code detalle_pedido}, {@code pedidos},
 * {@code clientes} y {@code productos} para devolver pedido, cliente, producto y cantidad
 * pedida junto a cada devolución, sin una consulta extra por fila.
 */
@Repository
public class DevolucionRepository {

    /**
     * Lista blanca de ordenamiento del listado: traduce el campo que pide el cliente al prefijo
     * de columna SQL de {@code devoluciones} (alias {@code d}). {@link PageRequest#of} rechaza
     * cualquier campo que no esté aquí, y la columna resuelta es la única que llega a
     * {@link #buscar}, de modo que el orden nunca es SQL arbitrario. El servicio de HU-18 arma
     * la página con estos mismos valores.
     */
    public static final Map<String, String> ORDEN = Map.of(
            "fechaDevolucion", "d.fecha_devolucion",
            "estado", "d.estado",
            "id", "d.id_devolucion");

    /** Orden por defecto del listado: la devolución más reciente primero. */
    public static final String ORDEN_POR_DEFECTO = "fechaDevolucion,desc";

    /**
     * Origen de la consulta, compartido por {@link #buscar} y {@link #buscarPorId} para que el
     * listado y la vista de detalle devuelvan exactamente las mismas columnas.
     *
     * <p>Los cinco {@code INNER JOIN} son seguros: las llaves foráneas de
     * {@code detalle_pedido}, {@code pedidos}, {@code clientes} y {@code productos} son
     * {@code NOT NULL}, así que ninguna devolución queda fuera. La última columna es una
     * subconsulta correlacionada sobre la misma línea: suma las devoluciones
     * {@code APROBADA} y {@code PENDIENTE} —y solo esas, R-03— de la que sale cada fila, con
     * {@code COALESCE} para que una línea sin devoluciones responda {@code 0} en lugar de
     * {@code NULL}. Es la misma cuenta que {@link #sumarDevueltoPorDetalle}, calculada en el
     * mismo {@code SELECT} para no hacer una consulta por fila.
     */
    private static final String SELECT = """
            SELECT d.id_devolucion, d.id_detalle_pedido, d.motivo, d.descripcion,
                   d.cantidad_devuelta, d.fecha_devolucion, d.estado,
                   p.id_pedido, c.nombre AS nombre_cliente,
                   pr.id_producto, pr.nombre AS nombre_producto,
                   dp.cantidad AS cantidad_pedida,
                   COALESCE((SELECT SUM(x.cantidad_devuelta)
                             FROM devoluciones x
                             WHERE x.id_detalle_pedido = d.id_detalle_pedido
                               AND x.estado IN ('APROBADA', 'PENDIENTE')), 0) AS comprometida_linea
            FROM devoluciones d
            JOIN detalle_pedido dp ON dp.id_detalle_pedido = d.id_detalle_pedido
            JOIN pedidos p ON p.id_pedido = dp.id_pedido
            JOIN clientes c ON c.id_cliente = p.id_cliente
            JOIN productos pr ON pr.id_producto = dp.id_producto
            """;

    /** Mapper de la fila base; el del listado le añade el compromiso de la línea. */
    private static final DevolucionRowMapper FILA_BASE = new DevolucionRowMapper();

    /**
     * Traduce la fila del listado: la devolución delega en {@link #FILA_BASE} y el compromiso
     * acumulado de la línea se lee de la subconsulta correlacionada del mismo {@code SELECT}.
     */
    private static final RowMapper<DevolucionFila> FILA_LISTADO = (rs, rowNum) ->
            new DevolucionFila(FILA_BASE.mapRow(rs, rowNum),
                    rs.getBigDecimal("comprometida_linea"));

    private final NamedParameterJdbcTemplate jdbc;

    public DevolucionRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Devuelve una página de devoluciones con sus {@code JOIN} y el compromiso de su línea.
     *
     * <p>Los cuatro filtros son opcionales y se acumulan; el filtro por pedido se resuelve
     * contra {@code detalle_pedido}, de donde sale la línea. El orden se aplica sobre la
     * propiedad que resuelve {@link PageRequest#of} desde {@link #ORDEN} —nunca sobre texto
     * del usuario— y siempre se desempata por {@code d.id_devolucion DESC} para que un empate
     * no deje la página indeterminada entre llamadas.
     *
     * <p>Una propiedad fuera de {@link #ORDEN} —una página construida a mano o un mapa de
     * orden desactualizado— cae al orden por defecto en vez de concatenarse en el
     * {@code ORDER BY}: una columna inexistente responde {@code ERROR 1054} y un 500, el borde
     * que ya dolió en HU-15. La defensa es redundante con {@link PageRequest#of} cuando el
     * servicio arma la página con {@link #ORDEN}, y garantiza además que {@link #buscar} nunca
     * recibe SQL de ordenamiento arbitrario.
     *
     * @param estado     estado a filtrar, o {@code null} sin filtrar
     * @param fechaInicio primer día incluido del rango de {@code fecha_devolucion}, o
     *                    {@code null} sin límite inferior
     * @param fechaFin   último día incluido del rango, o {@code null} sin límite superior
     * @param idPedido   pedido cuyas líneas se listan, o {@code null} sin filtrar
     * @param page       página solicitada, con el orden ya resuelto
     * @return devoluciones de la página, cada una con el compromiso de su línea
     */
    public List<DevolucionFila> buscar(EstadoDevolucion estado, LocalDate fechaInicio,
                                       LocalDate fechaFin, Integer idPedido, PageRequest page) {
        var params = new MapSqlParameterSource();
        var where = armarFiltros(estado, fechaInicio, fechaFin, idPedido, params);
        long offset = (long) page.page() * page.size();
        params.addValue("tamano", page.size()).addValue("desplazamiento", offset);

        var sql = SELECT + where
                + " ORDER BY " + columnaOrden(page) + (ascendente(page) ? " ASC" : " DESC")
                + ", d.id_devolucion DESC"
                + " LIMIT :tamano OFFSET :desplazamiento";

        return jdbc.query(sql, params, FILA_LISTADO);
    }

    /**
     * Cuenta las devoluciones que cumplen los mismos filtros que {@link #buscar}.
     *
     * <p>El {@code COUNT} solo necesita el {@code JOIN} con {@code detalle_pedido} —el que
     * resuelve el filtro por pedido— y no el resto: el total no diverge de la página.
     *
     * @param estado      estado a filtrar, o {@code null} sin filtrar
     * @param fechaInicio primer día incluido del rango, o {@code null} sin límite inferior
     * @param fechaFin    último día incluido del rango, o {@code null} sin límite superior
     * @param idPedido    pedido cuyas líneas se cuentan, o {@code null} sin filtrar
     * @return total de devoluciones que cumplen los filtros
     */
    public long contar(EstadoDevolucion estado, LocalDate fechaInicio, LocalDate fechaFin,
                       Integer idPedido) {
        var params = new MapSqlParameterSource();
        var where = armarFiltros(estado, fechaInicio, fechaFin, idPedido, params);

        Long total = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM devoluciones d
                JOIN detalle_pedido dp ON dp.id_detalle_pedido = d.id_detalle_pedido
                """ + where, params, Long.class);
        return total == null ? 0L : total;
    }

    /**
     * Devuelve una devolución por su identificador, con sus {@code JOIN}.
     *
     * @param id identificador de la devolución
     * @return la devolución, o vacío si no existe
     */
    public Optional<Devolucion> buscarPorId(Integer id) {
        var params = new MapSqlParameterSource().addValue("id", id);
        return jdbc.query(SELECT + " WHERE d.id_devolucion = :id", params, FILA_BASE)
                .stream().findFirst();
    }

    /**
     * Inserta una devolución y devuelve el identificador que asignó la base.
     *
     * <p>El {@code INSERT} lleva la línea, el motivo, la descripción, la cantidad y el estado,
     * pero nunca {@code fecha_devolucion}: no se envía para que actúe el
     * {@code DEFAULT (curdate())} de la base (R-06), y la aplicación nunca debe fijar la fecha
     * de registro. El estado viaja como su nombre canónico, que es el literal del
     * {@code CHECK ck_devoluciones_estado}; la inserción siempre es {@code PENDIENTE} (R-01),
     * valor que fija el servicio.
     *
     * <p>No lleva {@code @Transactional}: la transacción la abre el servicio.
     *
     * @param devolucion devolución a persistir; debe traer línea, motivo, cantidad y estado
     * @return identificador asignado a la devolución
     */
    public Integer insertar(Devolucion devolucion) {
        var params = new MapSqlParameterSource()
                .addValue("idDetallePedido", devolucion.idDetallePedido())
                .addValue("motivo", devolucion.motivo())
                .addValue("descripcion", devolucion.descripcion())
                .addValue("cantidadDevuelta", devolucion.cantidadDevuelta())
                .addValue("estado", devolucion.estado().name());

        var generadas = new GeneratedKeyHolder();
        jdbc.update("""
                INSERT INTO devoluciones (id_detalle_pedido, motivo, descripcion,
                                          cantidad_devuelta, estado)
                VALUES (:idDetallePedido, :motivo, :descripcion, :cantidadDevuelta, :estado)
                """, params, generadas, new String[] {"id_devolucion"});

        Integer id = claveGenerada(generadas);
        if (id == null) {
            throw new IllegalStateException("La base no devolvió el id de la devolución insertada.");
        }
        return id;
    }

    /**
     * Cambia el estado de una devolución solo si sigue en el estado esperado.
     *
     * <p>La máquina de transiciones de {@link EstadoDevolucion} la valida el servicio; aquí se
     * añade la condición {@code estado = :esperado} para cerrar la ventana de carrera entre dos
     * transiciones simultáneas, igual que en {@code PedidoRepository}. El {@code UPDATE} bloquea
     * la fila hasta el commit: una segunda aprobación concurrente encuentra la fila ya en
     * {@code APROBADA} y actualiza cero filas, de modo que el stock no se repone dos veces
     * (R-01).
     *
     * <p>Solo toca {@code estado}: {@code fecha_devolucion} queda intacta (R-06).
     *
     * @param id       identificador de la devolución
     * @param esperado estado en el que la devolución debe estar para que el cambio se aplique
     * @param nuevo    estado nuevo
     * @return filas afectadas: {@code 1} si estaba en {@code esperado}; {@code 0} si otro
     *         proceso la cambió antes
     */
    public int cambiarEstado(Integer id, EstadoDevolucion esperado, EstadoDevolucion nuevo) {
        var params = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("esperado", esperado.name())
                .addValue("nuevo", nuevo.name());

        return jdbc.update("""
                UPDATE devoluciones SET estado = :nuevo
                WHERE id_devolucion = :id AND estado = :esperado
                """, params);
    }

    /**
     * Suma en la base las unidades devueltas o en trámite de una línea de pedido (R-03).
     *
     * <p>La validación de que no se devuelve más de lo pedido es contra el acumulado, no contra
     * cada devolución por separado: sin contar las {@code PENDIENTE} se podrían registrar tres
     * devoluciones de 10 sobre una línea de 10 y, al aprobarlas todas, volverían 30 unidades al
     * inventario de una venta de 10. Las {@code RECHAZADA} no cuentan: liberan la cantidad para
     * una devolución posterior.
     *
     * <p>{@code COALESCE} cubre la línea sin devoluciones, que es el estado válido de una línea
     * recién entregada.
     *
     * @param idDetallePedido identificador de la línea de pedido
     * @return suma de {@code cantidad_devuelta} de las devoluciones {@code APROBADA} y
     *         {@code PENDIENTE}; {@code 0} si la línea no tiene devoluciones
     */
    public BigDecimal sumarDevueltoPorDetalle(Integer idDetallePedido) {
        var params = new MapSqlParameterSource().addValue("idDetallePedido", idDetallePedido);

        BigDecimal suma = jdbc.queryForObject("""
                SELECT COALESCE(SUM(cantidad_devuelta), 0)
                FROM devoluciones
                WHERE id_detalle_pedido = :idDetallePedido
                  AND estado IN ('APROBADA', 'PENDIENTE')
                """, params, BigDecimal.class);
        return suma == null ? BigDecimal.ZERO : suma;
    }

    /**
     * Fragmento {@code WHERE} compartido por {@link #buscar} y {@link #contar}, para que todos
     * apliquen exactamente los mismos filtros y el total no diverja de la página.
     *
     * <p>Los cuatro filtros son opcionales y se acumulan. Solo se concatenan fragmentos
     * constantes; los valores viajan como parámetros con nombre. Las columnas de fecha son
     * {@code date}, así que el {@link LocalDate} viaja sin convertir. El filtro por pedido se
     * aplica sobre {@code dp} porque la línea de la devolución vive en {@code detalle_pedido}.
     */
    private static String armarFiltros(EstadoDevolucion estado, LocalDate fechaInicio,
                                       LocalDate fechaFin, Integer idPedido,
                                       MapSqlParameterSource params) {
        var where = new StringBuilder(" WHERE 1=1");
        if (estado != null) {
            where.append(" AND d.estado = :estado");
            params.addValue("estado", estado.name());
        }
        if (fechaInicio != null) {
            where.append(" AND d.fecha_devolucion >= :fechaInicio");
            params.addValue("fechaInicio", fechaInicio);
        }
        if (fechaFin != null) {
            where.append(" AND d.fecha_devolucion <= :fechaFin");
            params.addValue("fechaFin", fechaFin);
        }
        if (idPedido != null) {
            where.append(" AND dp.id_pedido = :idPedido");
            params.addValue("idPedido", idPedido);
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
     * @return columna SQL del orden, con su alias {@code d}
     */
    private static String columnaOrden(PageRequest page) {
        return ORDEN.containsValue(page.propiedad()) ? page.propiedad() : "d.fecha_devolucion";
    }

    /**
     * Dirección del orden: la que pidió la página, o {@code false} —descendente— cuando la
     * propiedad cayó al orden por defecto, que es {@code fechaDevolucion,desc} completo.
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
