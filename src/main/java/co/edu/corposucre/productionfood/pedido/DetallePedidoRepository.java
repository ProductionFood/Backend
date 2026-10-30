package co.edu.corposucre.productionfood.pedido;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/**
 * Persistencia JDBC del detalle de los pedidos ({@code detalle_pedido}, HU-17).
 *
 * <p>Es la contraparte de {@link PedidoRepository} para el patrón cabecera-detalle de los
 * pedidos: aquí se escriben las líneas, mientras {@link PedidoRepository} solo las lee y las
 * cuenta para los derivados de la cabecera.
 *
 * <p>Las lecturas llevan {@code JOIN} con {@code productos} y {@code unidades_medida} para
 * devolver nombre y unidad junto a cada línea, y así mostrar el detalle sin una consulta extra
 * por producto.
 */
@Repository
public class DetallePedidoRepository {

    /**
     * Columnas enumeradas y con alias, compartidas por todas las lecturas. El {@code JOIN} a
     * {@code productos} aporta el nombre; el {@code JOIN} a {@code unidades_medida} aporta la
     * abreviatura con la que se muestra la unidad. Ambos son seguros: {@code id_producto} e
     * {@code id_unidad} son llaves foráneas {@code NOT NULL} y no descartan ninguna línea.
     */
    private static final String SELECT = """
            SELECT dp.id_detalle_pedido, dp.id_pedido, dp.id_producto,
                   p.nombre AS nombre_producto, u.abreviatura AS unidad,
                   dp.cantidad, dp.precio_unitario
            FROM detalle_pedido dp
            JOIN productos p ON p.id_producto = dp.id_producto
            JOIN unidades_medida u ON u.id_unidad = p.id_unidad
            """;

    /** Mapper de la fila base; el de lectura completa la fila con las columnas del {@code JOIN}. */
    private static final DetallePedidoRowMapper FILA_BASE = new DetallePedidoRowMapper();

    /**
     * Traduce la fila unida: la parte de {@code detalle_pedido} delega en {@link #FILA_BASE} y
     * el nombre y la unidad se leen de los alias del {@code JOIN}.
     */
    private static final RowMapper<DetallePedido> FILA_CON_JOIN = (rs, rowNum) -> {
        var base = FILA_BASE.mapRow(rs, rowNum);
        return new DetallePedido(base.idDetallePedido(), base.idPedido(), base.idProducto(),
                rs.getString("nombre_producto"), rs.getString("unidad"),
                base.cantidad(), base.precioUnitario());
    };

    private final NamedParameterJdbcTemplate jdbc;

    public DetallePedidoRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Devuelve las líneas de detalle de un pedido, en el orden en que se agregaron.
     *
     * <p>El {@code ORDER BY id_detalle_pedido} hace determinista la respuesta: sin él, la base
     * podría devolver las líneas en cualquier orden y el mismo pedido se mostraría con el
     * detalle revuelto entre visitas.
     *
     * @param idPedido identificador del pedido
     * @return líneas con su producto y unidad; vacía si el pedido no tiene detalle
     */
    public List<DetallePedido> listarPorPedido(Integer idPedido) {
        var params = new MapSqlParameterSource().addValue("idPedido", idPedido);
        return jdbc.query(
                SELECT + " WHERE dp.id_pedido = :idPedido ORDER BY dp.id_detalle_pedido",
                params, FILA_CON_JOIN);
    }

    /**
     * Devuelve una línea de detalle con los datos de su producto, por id.
     *
     * <p>La usan el PUT y el DELETE de HU-17 para comprobar que la línea existe —y, contra el
     * pedido de la URL, que le pertenece— antes de tocarla.
     *
     * @param idDetalle identificador de la línea
     * @return la línea, o vacío si no existe
     */
    public Optional<DetallePedido> obtenerPorId(Integer idDetalle) {
        var params = new MapSqlParameterSource().addValue("idDetalle", idDetalle);
        return jdbc.query(SELECT + " WHERE dp.id_detalle_pedido = :idDetalle", params,
                FILA_CON_JOIN).stream().findFirst();
    }

    /**
     * Devuelve una línea de detalle con {@code SELECT ... FOR UPDATE}, para editar o eliminar la
     * línea sin carreras con otra petición sobre la misma fila.
     *
     * <p>El bloqueo solo vive dentro de la transacción del llamador: quien invoca este método
     * debe hacerlo con una transacción abierta, porque sin ella MySQL confirma al terminar la
     * sentencia y el {@code FOR UPDATE} deja de proteger la operación. No abre transacción por
     * sí mismo: la abre el servicio, igual que en el resto del módulo.
     *
     * @param idDetalle identificador de la línea
     * @return la línea bloqueada, o vacío si no existe
     */
    public Optional<DetallePedido> obtenerPorIdParaActualizar(Integer idDetalle) {
        var params = new MapSqlParameterSource().addValue("idDetalle", idDetalle);
        return jdbc.query(
                SELECT + " WHERE dp.id_detalle_pedido = :idDetalle FOR UPDATE",
                params, FILA_CON_JOIN).stream().findFirst();
    }

    /**
     * Inserta una línea de detalle y devuelve el identificador que asignó la base.
     *
     * <p>El precio viaja siempre explícito (R-04): la aplicación copia {@code productos.precio}
     * en {@code precio_unitario} al agregar la línea y nunca deja que la base lo rellene.
     *
     * <p>El {@code UNIQUE KEY uk_detalle_pedido (id_pedido, id_producto)} de V2 respalda R-06:
     * el servicio comprueba antes con {@link #existeProducto(Integer, Integer)} para devolver un
     * {@code 409 PRODUCTO_DUPLICADO} legible, pero dos peticiones simultáneas pueden pasar ambas
     * la comprobación y solo la base las detiene.
     *
     * <p>No lleva {@code @Transactional}: la transacción la abre el servicio, que además
     * descuenta el stock en la misma operación.
     *
     * @param detalle línea a persistir; debe traer pedido, producto, cantidad y precio
     * @return identificador asignado a la línea
     */
    public Integer insertar(DetallePedido detalle) {
        var params = new MapSqlParameterSource()
                .addValue("idPedido", detalle.idPedido())
                .addValue("idProducto", detalle.idProducto())
                .addValue("cantidad", detalle.cantidad())
                .addValue("precioUnitario", detalle.precioUnitario());

        var generadas = new GeneratedKeyHolder();
        jdbc.update("""
                INSERT INTO detalle_pedido (id_pedido, id_producto, cantidad, precio_unitario)
                VALUES (:idPedido, :idProducto, :cantidad, :precioUnitario)
                """, params, generadas, new String[] {"id_detalle_pedido"});

        Integer id = claveGenerada(generadas);
        if (id == null) {
            throw new IllegalStateException("La base no devolvió el id de la línea insertada.");
        }
        return id;
    }

    /**
     * Reemplaza la cantidad de una línea de detalle.
     *
     * <p>No toca {@code precio_unitario} (R-04) ni {@code id_producto}: el producto de la línea
     * no se reemplaza, porque el {@code UNIQUE} del pedido prohíbe repetirlo (R-06). Para
     * cambiar de producto se elimina la línea y se agrega otra.
     *
     * @param idDetalle identificador de la línea
     * @param cantidad  cantidad nueva
     */
    public void actualizarCantidad(Integer idDetalle, BigDecimal cantidad) {
        var params = new MapSqlParameterSource()
                .addValue("idDetalle", idDetalle)
                .addValue("cantidad", cantidad);

        jdbc.update("""
                UPDATE detalle_pedido
                SET cantidad = :cantidad
                WHERE id_detalle_pedido = :idDetalle
                """, params);
    }

    /**
     * Elimina una línea de detalle de un pedido (R-08).
     *
     * @param idDetalle identificador de la línea
     */
    public void eliminar(Integer idDetalle) {
        var params = new MapSqlParameterSource().addValue("idDetalle", idDetalle);
        jdbc.update(
                "DELETE FROM detalle_pedido WHERE id_detalle_pedido = :idDetalle", params);
    }

    /**
     * Indica si el producto ya está en el pedido (R-06).
     *
     * <p>Lo usa el servicio para responder {@code PRODUCTO_DUPLICADO} antes de intentar el
     * {@code INSERT}; el {@code UNIQUE KEY uk_detalle_pedido} es la garantía ante dos peticiones
     * simultáneas.
     *
     * @param idPedido   identificador del pedido
     * @param idProducto identificador del producto
     * @return {@code true} si el producto ya es un ítem del pedido
     */
    public boolean existeProducto(Integer idPedido, Integer idProducto) {
        var params = new MapSqlParameterSource()
                .addValue("idPedido", idPedido)
                .addValue("idProducto", idProducto);

        Long total = jdbc.queryForObject("""
                SELECT COUNT(*) FROM detalle_pedido
                WHERE id_pedido = :idPedido AND id_producto = :idProducto
                """, params, Long.class);
        return total != null && total > 0;
    }

    /**
     * Suma en la base los subtotales de las líneas de un pedido.
     *
     * <p>El total lo calcula siempre el servidor. El producto de dos {@code decimal(10,2)}
     * devuelve en MySQL un decimal de escala 4 —por ejemplo 33.33 × 3333.33 da 111099.8889—,
     * así que el servicio redondea este resultado a escala 2 con {@code HALF_UP} antes de
     * exponerlo. {@code COALESCE} cubre el pedido sin líneas, que es el estado válido de un
     * pedido recién registrado.
     *
     * @param idPedido identificador del pedido
     * @return la suma de {@code cantidad * precio_unitario}; {@code 0} si el pedido no tiene
     *         líneas
     */
    public BigDecimal calcularTotal(Integer idPedido) {
        var params = new MapSqlParameterSource().addValue("idPedido", idPedido);

        BigDecimal total = jdbc.queryForObject("""
                SELECT COALESCE(SUM(dp.cantidad * dp.precio_unitario), 0)
                FROM detalle_pedido dp
                WHERE dp.id_pedido = :idPedido
                """, params, BigDecimal.class);
        return total == null ? BigDecimal.ZERO : total;
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
