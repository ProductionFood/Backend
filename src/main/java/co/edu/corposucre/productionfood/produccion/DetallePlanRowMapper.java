package co.edu.corposucre.productionfood.produccion;

import java.sql.ResultSet;
import java.sql.SQLException;

import org.springframework.jdbc.core.RowMapper;

/**
 * Traduce una fila de {@code detalle_plan_produccion} unida a {@code productos},
 * {@code unidades_medida} y {@code recetas} al POJO {@link DetallePlanProduccion} (HU-21).
 *
 * <p>Lee por nombre de columna y no por índice: la consulta puede ganar columnas sin que el
 * mapeo se desplace en silencio a la columna equivocada.
 *
 * <p>{@code nombre_producto} y {@code unidad} son alias de las proyecciones del {@code JOIN};
 * {@code unidad} es la abreviatura de {@code unidades_medida}. {@code tiene_receta} llega del
 * {@code LEFT JOIN} con {@code recetas} como un booleano calculado
 * ({@code r.id_receta IS NOT NULL}), no como una columna de la tabla.
 */
public class DetallePlanRowMapper implements RowMapper<DetallePlanProduccion> {

    @Override
    public DetallePlanProduccion mapRow(ResultSet rs, int rowNum) throws SQLException {
        var detalle = new DetallePlanProduccion();
        detalle.setIdDetallePlan(rs.getObject("id_detalle_plan", Integer.class));
        detalle.setIdPlan(rs.getObject("id_plan", Integer.class));
        detalle.setIdProducto(rs.getObject("id_producto", Integer.class));
        detalle.setNombreProducto(rs.getString("nombre_producto"));
        detalle.setUnidad(rs.getString("unidad"));
        detalle.setCantidadPlanificada(rs.getBigDecimal("cantidad_planificada"));
        detalle.setCantidadProducida(rs.getBigDecimal("cantidad_producida"));
        detalle.setTieneReceta(rs.getBoolean("tiene_receta"));
        return detalle;
    }
}
