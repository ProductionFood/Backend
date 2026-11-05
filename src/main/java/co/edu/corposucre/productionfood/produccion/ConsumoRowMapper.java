package co.edu.corposucre.productionfood.produccion;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;

import org.springframework.jdbc.core.RowMapper;

/**
 * Traduce una fila de {@code consumo_materia_prima} unida a {@code detalle_plan_produccion},
 * {@code productos}, {@code planes_produccion}, {@code materias_primas} y {@code unidades_medida}
 * al POJO {@link ConsumoMateriaPrima} (HU-22).
 *
 * <p>Lee por nombre de columna y no por índice: la consulta puede ganar columnas sin que el
 * mapeo se desplace en silencio a la columna equivocada.
 *
 * <p>{@code nombre_producto}, {@code nombre_materia_prima}, {@code unidad} y {@code estado_plan}
 * son alias de las proyecciones del {@code JOIN}; {@code unidad} es la abreviatura de
 * {@code unidades_medida}. {@code cantidad_real} y {@code fecha_registro} se leen tolerando
 * {@code null} para que una fila anterior a V2 no rompa el mapeo, y {@code estado_plan} solo se
 * convierte a {@link EstadoPlan} cuando llega informado.
 */
public class ConsumoRowMapper implements RowMapper<ConsumoMateriaPrima> {

    @Override
    public ConsumoMateriaPrima mapRow(ResultSet rs, int rowNum) throws SQLException {
        var consumo = new ConsumoMateriaPrima();
        consumo.setIdConsumo(rs.getObject("id_consumo", Integer.class));
        consumo.setIdDetallePlan(rs.getObject("id_detalle_plan", Integer.class));
        consumo.setIdPlan(rs.getObject("id_plan", Integer.class));
        consumo.setNombreProducto(rs.getString("nombre_producto"));
        consumo.setIdMateriaPrima(rs.getObject("id_materia_prima", Integer.class));
        consumo.setNombreMateriaPrima(rs.getString("nombre_materia_prima"));
        consumo.setUnidad(rs.getString("unidad"));
        consumo.setCantidadTeorica(rs.getBigDecimal("cantidad_teorica"));
        consumo.setCantidadReal(rs.getBigDecimal("cantidad_real"));
        consumo.setFechaRegistro(rs.getObject("fecha_registro", LocalDateTime.class));
        var estado = rs.getString("estado_plan");
        consumo.setEstadoPlan(estado == null ? null : EstadoPlan.valueOf(estado));
        return consumo;
    }
}
