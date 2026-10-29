package co.edu.corposucre.productionfood.compra;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;

import org.springframework.jdbc.core.RowMapper;

/**
 * Traduce una fila de {@code compras} unida a {@code detalle_compra}, {@code materias_primas} y
 * {@code unidades_medida} al registro {@link CompraDetalleFila} (HU-15 R-06).
 *
 * <p>Lee por nombre de columna, igual que {@link CompraRowMapper}: la consulta puede cambiar de
 * forma sin que el mapeo se desplace en silencio a la columna equivocada. Las columnas del
 * detalle se leen con {@code getObject}/{@code getBigDecimal} sin defecto: en la fila de una
 * compra sin líneas el {@code LEFT JOIN} las devuelve {@code NULL} y el registro las conserva
 * así, que es como el servicio distingue «sin línea» de «con línea».
 */
public class CompraDetalleFilaRowMapper implements RowMapper<CompraDetalleFila> {

    @Override
    public CompraDetalleFila mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new CompraDetalleFila(
                rs.getObject("id_compra", Integer.class),
                rs.getObject("id_proveedor", Integer.class),
                rs.getObject("fecha", LocalDate.class),
                EstadoCompra.valueOf(rs.getString("estado")),
                rs.getString("nombre_proveedor"),
                rs.getBigDecimal("total_compra"),
                rs.getInt("cantidad_items"),
                rs.getObject("id_materia_prima", Integer.class),
                rs.getObject("id_detalle_compra", Integer.class),
                rs.getBigDecimal("cantidad"),
                rs.getBigDecimal("precio_unitario"),
                rs.getString("nombre_materia_prima"),
                rs.getString("abreviatura_unidad"),
                rs.getBigDecimal("costo_referencia"));
    }
}
