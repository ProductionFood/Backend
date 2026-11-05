package co.edu.corposucre.productionfood.inventario;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;

import org.springframework.jdbc.core.RowMapper;

/**
 * Traduce una fila de {@code v_inventario_materia_prima} a {@link AlertaStockFila}.
 *
 * <p>Lee por nombre de columna, y {@code fecha_actualizacion} con {@code getObject} porque el
 * {@code LEFT JOIN} de la vista puede entregarla nula.
 */
public class AlertaStockRowMapper implements RowMapper<AlertaStockFila> {

    @Override
    public AlertaStockFila mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new AlertaStockFila(
                rs.getObject("id_materia_prima", Integer.class),
                rs.getString("nombre"),
                rs.getString("unidad"),
                rs.getBigDecimal("cantidad_disponible"),
                rs.getBigDecimal("stock_minimo"),
                rs.getBigDecimal("faltante"),
                rs.getString("estado_stock"),
                rs.getBigDecimal("costo_unitario"),
                rs.getObject("fecha_actualizacion", LocalDateTime.class)
        );
    }
}
