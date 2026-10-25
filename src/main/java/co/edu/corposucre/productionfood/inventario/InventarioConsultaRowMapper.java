package co.edu.corposucre.productionfood.inventario;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;

import org.springframework.jdbc.core.RowMapper;

import co.edu.corposucre.productionfood.inventario.dto.InventarioResponse;

/**
 * Traduce una fila de {@code v_inventario_materia_prima} al record {@link InventarioResponse}.
 *
 * <p>Lee por nombre de columna y no por índice: la vista puede ganar columnas sin que el mapeo
 * se desplace en silencio a la columna equivocada.
 */
public class InventarioConsultaRowMapper implements RowMapper<InventarioResponse> {

    @Override
    public InventarioResponse mapRow(ResultSet rs, int rowNum) throws SQLException {
        // getObject con clase y no getTimestamp().toLocalDateTime(): la vista usa LEFT JOIN, así
        // que fecha_actualizacion puede llegar nula y getTimestamp devolvería null, provocando
        // un NullPointerException al convertirla.
        LocalDateTime fechaActualizacion =
                rs.getObject("fecha_actualizacion", LocalDateTime.class);

        return new InventarioResponse(
                rs.getObject("id_materia_prima", Integer.class),
                rs.getString("nombre"),
                rs.getString("unidad"),
                rs.getBigDecimal("cantidad_disponible"),
                rs.getBigDecimal("stock_minimo"),
                rs.getBigDecimal("faltante"),
                rs.getString("estado_stock"),
                rs.getBoolean("estado"),
                fechaActualizacion
        );
    }
}
