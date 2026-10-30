package co.edu.corposucre.productionfood.pedido;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/**
 * Persistencia JDBC de los pedidos ({@code pedidos} y {@code detalle_pedido}, HU-16).
 *
 * <p>Se usa JDBC y no JPA por la misma razón que el módulo de compras: el modelo ya está descrito
 * por el esquema y no debe duplicarse en Hibernate. Además, el total y la cantidad de ítems de un
 * pedido son derivados que conviene calcular en una sola consulta con subconsultas correlacionadas,
 * en vez de cargar todas las líneas para sumarlas en memoria.
 *
 * <p>El {@code INSERT} no lleva {@code fecha_pedido}: la columna es
 * {@code NOT NULL DEFAULT (curdate())} desde V2 (§C-07), así que la fecha la pone la base y la
 * aplicación nunca la envía (R-01). Tampoco toca {@code fecha_pedido} en la edición: una vez
 * registrada es inmutable.
 *
 * <p>Las escrituras del detalle del pedido ({@code INSERT/UPDATE/DELETE} sobre
 * {@code detalle_pedido}) no viven aquí: en este módulo el detalle solo se lee y se cuenta.
 */
@Repository
public class PedidoRepository {

    /**
     * Origen de la consulta, compartido por {@link #buscar} y {@link #buscarPorId} para que el
     * listado y la vista de detalle devuelvan exactamente las mismas columnas.
     *
     * <p>El {@code INNER JOIN} con {@code clientes} es seguro porque {@code id_cliente} es
     * {@code NOT NULL} con llave foránea: no descarta pedidos. Los totales llegan de dos
     * subconsultas correlacionadas sobre {@code detalle_pedido}: {@code COALESCE} devuelve
     * {@code 0} mientras el pedido no tenga líneas, que es el estado válido de un pedido recién
     * registrado (el detalle llega después).
     */
    private static final String SELECT = """
            SELECT p.id_pedido, p.id_cliente, p.fecha_pedido, p.fecha_entrega, p.estado,
                   c.nombre AS nombre_cliente,
                   COALESCE((SELECT SUM(dp.cantidad * dp.precio_unitario)
                             FROM detalle_pedido dp
                             WHERE dp.id_pedido = p.id_pedido), 0) AS total_pedido,
                   COALESCE((SELECT COUNT(*)
                             FROM detalle_pedido dp
                             WHERE dp.id_pedido = p.id_pedido), 0) AS cantidad_items
            FROM pedidos p
            JOIN clientes c ON c.id_cliente = p.id_cliente
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public PedidoRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Devuelve una página de pedidos con los datos de su cliente y sus totales derivados.
     *
     * <p>Los cuatro filtros son opcionales y se acumulan. El orden se aplica sobre la propiedad
     * que resuelve el servicio desde su lista blanca —nunca sobre texto del usuario— y siempre
     * se desempata por {@code p.id_pedido DESC} para que un empate (dos pedidos del mismo día)
     * no deje la página indeterminada entre llamadas.
     *
     * @param idCliente       filtro por cliente, o {@code null} sin filtro
     * @param estado          filtro por estado, o {@code null} sin filtro
     * @param fechaInicio     primer día incluido del rango de {@code fecha_pedido}, o {@code null}
     *                        sin límite inferior
     * @param fechaFin        último día incluido del rango de {@code fecha_pedido}, o {@code null}
     *                        sin límite superior
     * @param propiedadOrden  columna ya validada por el servicio, con su prefijo {@code p.}
     * @param ascendente      {@code true} para orden ascendente, {@code false} para descendente
     * @param size            tamaño de la página
     * @param offset          desplazamiento de la página, en {@code long} porque
     *                        {@code page * size} puede desbordar un int
     * @return pedidos de la página, con su cliente y sus totales
     */
    public List<Pedido> buscar(Integer idCliente, EstadoPedido estado, LocalDate fechaInicio,
                               LocalDate fechaFin, String propiedadOrden, boolean ascendente,
                               int size, long offset) {
        var params = new MapSqlParameterSource();
        var where = armarFiltros(idCliente, estado, fechaInicio, fechaFin, params);
        params.addValue("tamano", size).addValue("desplazamiento", offset);

        var sql = SELECT + where
                + " ORDER BY " + propiedadOrden + (ascendente ? " ASC" : " DESC")
                + ", p.id_pedido DESC"
                + " LIMIT :tamano OFFSET :desplazamiento";

        return jdbc.query(sql, params, new PedidoRowMapper());
    }

    /**
     * Cuenta los pedidos que cumplen los mismos filtros que {@link #buscar}.
     *
     * <p>El {@code COUNT} no usa el {@code JOIN} con {@code clientes}: el filtro por cliente
     * compara contra la columna de la propia tabla y el total no diverge de la página.
     *
     * @param idCliente   filtro por cliente, o {@code null} sin filtro
     * @param estado      filtro por estado, o {@code null} sin filtro
     * @param fechaInicio primer día incluido del rango, o {@code null} sin límite inferior
     * @param fechaFin    último día incluido del rango, o {@code null} sin límite superior
     * @return total de pedidos que cumplen los filtros
     */
    public long contar(Integer idCliente, EstadoPedido estado, LocalDate fechaInicio,
                       LocalDate fechaFin) {
        var params = new MapSqlParameterSource();
        var where = armarFiltros(idCliente, estado, fechaInicio, fechaFin, params);

        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM pedidos p " + where, params,
                Long.class);
        return total == null ? 0L : total;
    }

    /**
     * Devuelve un pedido por su identificador, con su cliente y sus totales derivados.
     *
     * @param id identificador del pedido
     * @return el pedido, o vacío si no existe
     */
    public Optional<Pedido> buscarPorId(Integer id) {
        var params = new MapSqlParameterSource().addValue("id", id);
        return jdbc.query(SELECT + " WHERE p.id_pedido = :id", params, new PedidoRowMapper())
                .stream().findFirst();
    }

    /**
     * Inserta la cabecera de un pedido y devuelve el identificador que asignó la base.
     *
     * <p>El {@code INSERT} lleva cliente, estado y la fecha de entrega, pero nunca
     * {@code fecha_pedido}: no se envía para que actúe el {@code DEFAULT (curdate())} de la base
     * (R-01), y la aplicación nunca debe fijar la fecha de registro. El estado viaja como su
     * nombre canónico, que es el literal del {@code CHECK ck_pedidos_estado}; la inserción
     * siempre es {@code PENDIENTE} (R-02), valor que fija el servicio.
     *
     * <p>No lleva {@code @Transactional}: la transacción la abre el servicio que crea la cabecera
     * y su detalle, para que un fallo en una línea no deje un pedido a medias.
     *
     * @param pedido pedido a persistir; debe traer cliente y estado
     * @return identificador asignado al pedido
     */
    public Integer insertar(Pedido pedido) {
        var params = new MapSqlParameterSource()
                .addValue("idCliente", pedido.getIdCliente())
                .addValue("estado", pedido.getEstado().name())
                .addValue("fechaEntrega", pedido.getFechaEntrega());

        var generadas = new GeneratedKeyHolder();
        jdbc.update("""
                INSERT INTO pedidos (id_cliente, estado, fecha_entrega)
                VALUES (:idCliente, :estado, :fechaEntrega)
                """, params, generadas, new String[] {"id_pedido"});

        Integer id = claveGenerada(generadas);
        if (id == null) {
            throw new IllegalStateException("La base no devolvió el id del pedido insertado.");
        }
        return id;
    }

    /**
     * Actualiza los campos editables de la cabecera: el cliente y la fecha de entrega.
     *
     * <p>No toca {@code fecha_pedido}, que es inmutable una vez registrado (R-01), ni
     * {@code estado}: para eso está {@link #cambiarEstado(Integer, EstadoPedido, EstadoPedido)}, que es el
     * único camino que la máquina de estados de {@link EstadoPedido} permite auditar.
     *
     * @param pedido pedido con los valores nuevos; debe traer id, cliente y fecha de entrega
     */
    public void actualizar(Pedido pedido) {
        var params = new MapSqlParameterSource()
                .addValue("id", pedido.getIdPedido())
                .addValue("idCliente", pedido.getIdCliente())
                .addValue("fechaEntrega", pedido.getFechaEntrega());

        jdbc.update("""
                UPDATE pedidos
                SET id_cliente = :idCliente,
                    fecha_entrega = :fechaEntrega
                WHERE id_pedido = :id
                """, params);
    }

    /**
     * Cambia el estado de un pedido solo si sigue en el estado esperado.
     *
     * <p>La máquina de transiciones de {@link EstadoPedido} la valida el servicio; aquí se añade
     * la condición {@code estado = :esperado} para cerrar la ventana de carrera entre dos
     * transiciones simultáneas. El {@code UPDATE} bloquea la fila del pedido hasta el commit, de
     * modo que una segunda cancelación concurrente no puede volver a devolver stock: encuentra
     * la fila ya en {@code CANCELADO} y actualiza cero filas.
     *
     * @param id       identificador del pedido
     * @param esperado estado en el que el pedido debe estar para que el cambio se aplique
     * @param nuevo    estado nuevo
     * @return filas afectadas: {@code 1} si el pedido estaba en {@code esperado}; {@code 0} si
     *         otro proceso lo cambió antes
     */
    public int cambiarEstado(Integer id, EstadoPedido esperado, EstadoPedido nuevo) {
        var params = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("esperado", esperado.name())
                .addValue("nuevo", nuevo.name());

        return jdbc.update("""
                UPDATE pedidos SET estado = :nuevo
                WHERE id_pedido = :id AND estado = :esperado
                """, params);
    }

    /**
     * Devuelve los pares (lote, cantidad) que hay que reponer al cancelar un pedido.
     *
     * <p>La trazabilidad entre cada línea de {@code detalle_pedido} y el lote del que salió su
     * mercancía <strong>no</strong> vive en el detalle: vive en el kardex de producto terminado
     * (R-10 de HU-17). Cada {@code SALIDA_VENTA} guarda {@code id_lote}, la cantidad consumida y
     * {@code tabla_origen = 'detalle_pedido'} con {@code id_origen = id_detalle_pedido}. Esta
     * consulta recorre esos movimientos y devuelve exactamente lo que hay que devolver, sin
     * reconstruir el reparto FEFO en memoria.
     *
     * <p>Solo considera los {@code SALIDA_VENTA} ligados a líneas de este pedido: las entradas
     * de devolución de una cancelación anterior no vuelven a contarse. El orden es
     * {@code id_movimiento DESC}, del movimiento más reciente al más antiguo.
     *
     * @param idPedido pedido cuyas salidas de venta se reponen
     * @return pares lote-cantidad de las ventas del pedido; vacía si no tiene ninguna
     */
    public List<LineaDevolucion> lineasParaDevolucion(Integer idPedido) {
        var params = new MapSqlParameterSource().addValue("idPedido", idPedido);

        return jdbc.query("""
                SELECT m.id_lote, m.cantidad
                FROM movimientos_inventario_pt m
                JOIN detalle_pedido dp ON dp.id_detalle_pedido = m.id_origen
                WHERE m.tabla_origen = 'detalle_pedido'
                  AND dp.id_pedido = :idPedido
                  AND m.tipo_movimiento = 'SALIDA_VENTA'
                ORDER BY m.id_movimiento DESC
                """, params, (rs, fila) -> new LineaDevolucion(
                        rs.getInt("id_lote"), rs.getBigDecimal("cantidad")));
    }

    /**
     * Fragmento {@code WHERE} compartido por {@link #buscar} y {@link #contar}, para que todos
     * apliquen exactamente los mismos filtros y el total no diverja de la página.
     *
     * <p>Los cuatro filtros son opcionales y se acumulan. Solo se concatenan fragmentos
     * constantes; los valores viajan como parámetros con nombre. {@code p.fecha_pedido} es una
     * columna {@code date}, así que el {@link LocalDate} viaja sin convertir.
     */
    private static String armarFiltros(Integer idCliente, EstadoPedido estado,
                                       LocalDate fechaInicio, LocalDate fechaFin,
                                       MapSqlParameterSource params) {
        var where = new StringBuilder(" WHERE 1=1");
        if (idCliente != null) {
            where.append(" AND p.id_cliente = :idCliente");
            params.addValue("idCliente", idCliente);
        }
        if (estado != null) {
            where.append(" AND p.estado = :estado");
            params.addValue("estado", estado.name());
        }
        if (fechaInicio != null) {
            where.append(" AND p.fecha_pedido >= :fechaInicio");
            params.addValue("fechaInicio", fechaInicio);
        }
        if (fechaFin != null) {
            where.append(" AND p.fecha_pedido <= :fechaFin");
            params.addValue("fechaFin", fechaFin);
        }
        return where.toString();
    }

    /**
     * Extrae la llave generada como {@link Integer}.
     *
     * <p>El conector de MySQL devuelve la llave como {@code BigInteger}; pedirla con
     * {@code getKeyAs(Integer.class)} lanza {@code DataRetrievalFailureException} porque la
     * conversión de tipos no está soportada. Se toma como {@link Number} y se reduce al tipo de la
     * columna.
     */
    private static Integer claveGenerada(GeneratedKeyHolder generadas) {
        Number clave = generadas.getKey();
        return clave == null ? null : clave.intValue();
    }
}
