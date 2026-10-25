package co.edu.corposucre.productionfood.receta;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.springframework.jdbc.core.RowMapper;

/**
 * Traduce una fila de {@code detalle_receta} unida a {@code materias_primas} y
 * {@code unidades_medida} al POJO {@link DetalleReceta} (HU-12).
 *
 * <p>Lee por nombre de columna y no por índice: la consulta puede ganar columnas sin que el mapeo
 * se desplace en silencio a la columna equivocada.
 *
 * <p>El {@code JOIN} a {@code materias_primas} aporta el nombre y el costo unitario de referencia
 * (HU-08 R-03) con el que PR3 calcula el costo estimado de la línea; el {@code JOIN} a
 * {@code unidades_medida} aporta la abreviatura con la que se muestra la cantidad. Sin esas
 * proyecciones el consumidor tendría que resolver cada materia prima por una llamada extra.
 *
 * <p>{@code cantidad} se fija a escala 3 porque la columna es {@code decimal(10,3)} (R-06) y el
 * tercer decimal es significativo en repostería. Los costos se fijan a escala 2, igual que su
 * columna de origen.
 */
public class DetalleRecetaRowMapper implements RowMapper<DetalleReceta> {

    @Override
    public DetalleReceta mapRow(ResultSet rs, int rowNum) throws SQLException {
        var detalle = new DetalleReceta();
        detalle.setIdDetalleReceta(rs.getObject("id_detalle_receta", Integer.class));
        detalle.setIdReceta(rs.getObject("id_receta", Integer.class));
        detalle.setIdMateriaPrima(rs.getObject("id_materia_prima", Integer.class));
        detalle.setNombreMateriaPrima(rs.getString("nombre_materia_prima"));
        detalle.setAbreviaturaUnidad(rs.getString("abreviatura_unidad"));
        detalle.setCantidad(aEscala(rs.getBigDecimal("cantidad"), 3));
        detalle.setCostoUnitario(aEscala(rs.getBigDecimal("costo_unitario"), 2));
        return detalle;
    }

    private static BigDecimal aEscala(BigDecimal valor, int escala) {
        return valor == null ? null : valor.setScale(escala, RoundingMode.HALF_UP);
    }
}
