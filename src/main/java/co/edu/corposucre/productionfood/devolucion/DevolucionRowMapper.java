package co.edu.corposucre.productionfood.devolucion;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;

import org.springframework.jdbc.core.RowMapper;

/**
 * Traduce una fila del {@code SELECT ... JOIN} de {@link DevolucionRepository} al registro
 * {@link Devolucion} (HU-18).
 *
 * <p>Lee tanto las columnas de la propia tabla como las que aportan los {@code JOIN} con
 * {@code detalle_pedido}, {@code pedidos}, {@code clientes} y {@code productos}: el listado y
 * la vista de detalle comparten la misma consulta, así que un solo mapper cubre ambas
 * lecturas.
 *
 * <p>Lee por nombre de columna y no por índice: la consulta puede ganar columnas sin que el
 * mapeo se desplace en silencio a la columna equivocada.
 */
public class DevolucionRowMapper implements RowMapper<Devolucion> {

    @Override
    public Devolucion mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new Devolucion(
                rs.getObject("id_devolucion", Integer.class),
                rs.getObject("id_detalle_pedido", Integer.class),
                rs.getString("motivo"),
                rs.getString("descripcion"),
                rs.getBigDecimal("cantidad_devuelta"),
                rs.getObject("fecha_devolucion", LocalDate.class),
                EstadoDevolucion.valueOf(rs.getString("estado")),
                rs.getObject("id_pedido", Integer.class),
                rs.getString("nombre_cliente"),
                rs.getObject("id_producto", Integer.class),
                rs.getString("nombre_producto"),
                rs.getBigDecimal("cantidad_pedida"));
    }
}
