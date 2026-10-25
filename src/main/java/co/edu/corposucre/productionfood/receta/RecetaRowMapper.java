package co.edu.corposucre.productionfood.receta;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.springframework.jdbc.core.RowMapper;

/**
 * Traduce una fila de {@code recetas} unida a {@code productos} y {@code unidades_medida} al POJO
 * {@link Receta} (HU-12).
 *
 * <p>Lee por nombre de columna y no por índice: la consulta puede ganar columnas sin que el mapeo
 * se desplace en silencio a la columna equivocada. Los alias de producto y unidad
 * ({@code nombre_producto}, {@code nombre_unidad}, {@code abreviatura_unidad}) son la única fuente
 * de las proyecciones de solo lectura de {@link Receta}.
 *
 * <p>{@code estado} se selecciona con el alias {@code activo} porque es el nombre que usa el
 * contrato de HU-12, y {@code cantidad_producir} se fija a escala 2: la columna es
 * {@code decimal(10,2)} y la respuesta no debe depender de la escala con la que el driver
 * devuelva el valor.
 */
public class RecetaRowMapper implements RowMapper<Receta> {

    @Override
    public Receta mapRow(ResultSet rs, int rowNum) throws SQLException {
        var receta = new Receta();
        receta.setIdReceta(rs.getObject("id_receta", Integer.class));
        receta.setIdProducto(rs.getObject("id_producto", Integer.class));
        receta.setNombreProducto(rs.getString("nombre_producto"));
        receta.setNombre(rs.getString("nombre"));
        receta.setCantidadProducir(aEscala(rs.getBigDecimal("cantidad_producir"), 2));
        receta.setIdUnidad(rs.getObject("id_unidad", Integer.class));
        receta.setNombreUnidad(rs.getString("nombre_unidad"));
        receta.setAbreviaturaUnidad(rs.getString("abreviatura_unidad"));
        receta.setActivo(rs.getBoolean("activo"));
        return receta;
    }

    private static BigDecimal aEscala(BigDecimal valor, int escala) {
        return valor == null ? null : valor.setScale(escala, RoundingMode.HALF_UP);
    }
}
