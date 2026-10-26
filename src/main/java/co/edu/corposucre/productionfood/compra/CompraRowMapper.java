package co.edu.corposucre.productionfood.compra;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;

import org.springframework.jdbc.core.RowMapper;

/**
 * Traduce una fila de {@code compras} unida a {@code proveedores} al POJO {@link Compra} (HU-13).
 *
 * <p>Lee por nombre de columna y no por índice: la consulta puede ganar columnas sin que el mapeo
 * se desplace en silencio a la columna equivocada.
 *
 * <p>El {@code JOIN} a {@code proveedores} aporta el nombre del proveedor con el que se muestra
 * la compra sin resolverlo por una llamada extra. {@code total_compra} y {@code cantidad_items}
 * llegan de las subconsultas correlacionadas de {@link CompraRepository}: {@code COALESCE} garantiza
 * {@code 0} en una compra sin líneas, así que el total nunca es nulo. El total se fija a escala 2,
 * igual que su columna de origen ({@code decimal(10,2)}).
 */
public class CompraRowMapper implements RowMapper<Compra> {

    @Override
    public Compra mapRow(ResultSet rs, int rowNum) throws SQLException {
        var compra = new Compra();
        compra.setIdCompra(rs.getObject("id_compra", Integer.class));
        compra.setIdProveedor(rs.getObject("id_proveedor", Integer.class));
        compra.setFecha(rs.getObject("fecha", LocalDate.class));
        compra.setEstado(EstadoCompra.valueOf(rs.getString("estado")));
        compra.setNombreProveedor(rs.getString("nombre_proveedor"));
        compra.setTotalCompra(aEscala(rs.getBigDecimal("total_compra")));
        compra.setCantidadItems(rs.getInt("cantidad_items"));
        return compra;
    }

    private static BigDecimal aEscala(BigDecimal valor) {
        return valor == null ? null : valor.setScale(2, RoundingMode.HALF_UP);
    }
}
