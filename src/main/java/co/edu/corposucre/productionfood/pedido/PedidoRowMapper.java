package co.edu.corposucre.productionfood.pedido;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;

import org.springframework.jdbc.core.RowMapper;

/**
 * Traduce una fila de {@code pedidos} unida a {@code clientes} al POJO {@link Pedido} (HU-16).
 *
 * <p>Lee por nombre de columna y no por índice: la consulta puede ganar columnas sin que el
 * mapeo se desplace en silencio a la columna equivocada.
 *
 * <p>El {@code JOIN} a {@code clientes} aporta el nombre del cliente con el que se muestra el
 * pedido sin resolverlo por una llamada extra. {@code total_pedido} y {@code cantidad_items}
 * llegan de las subconsultas correlacionadas de {@link PedidoRepository}: {@code COALESCE}
 * garantiza {@code 0} en un pedido sin líneas, así que el total nunca es nulo. El total se fija
 * a escala 2, igual que su columna de origen ({@code decimal(10,2)}).
 */
public class PedidoRowMapper implements RowMapper<Pedido> {

    @Override
    public Pedido mapRow(ResultSet rs, int rowNum) throws SQLException {
        var pedido = new Pedido();
        pedido.setIdPedido(rs.getObject("id_pedido", Integer.class));
        pedido.setIdCliente(rs.getObject("id_cliente", Integer.class));
        pedido.setFechaPedido(rs.getObject("fecha_pedido", LocalDate.class));
        pedido.setFechaEntrega(rs.getObject("fecha_entrega", LocalDate.class));
        pedido.setEstado(EstadoPedido.valueOf(rs.getString("estado")));
        pedido.setNombreCliente(rs.getString("nombre_cliente"));
        pedido.setTotalPedido(aEscala(rs.getBigDecimal("total_pedido")));
        pedido.setCantidadItems(rs.getInt("cantidad_items"));
        return pedido;
    }

    private static BigDecimal aEscala(BigDecimal valor) {
        return valor == null ? null : valor.setScale(2, RoundingMode.HALF_UP);
    }
}
