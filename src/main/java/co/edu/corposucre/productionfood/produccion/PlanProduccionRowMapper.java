package co.edu.corposucre.productionfood.produccion;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;

import org.springframework.jdbc.core.RowMapper;

/**
 * Traduce una fila de {@code planes_produccion} unida a {@code usuarios} al POJO
 * {@link PlanProduccion} (HU-20).
 *
 * <p>Lee por nombre de columna y no por índice: la consulta puede ganar columnas sin que el
 * mapeo se desplace en silencio a la columna equivocada.
 *
 * <p>El {@code JOIN} con {@code usuarios} aporta {@code nombre_responsable}, con el que se
 * muestra el plan sin resolverlo por una llamada extra. {@code cantidad_productos} llega de la
 * subconsulta correlacionada de {@link PlanProduccionRepository}: {@code COUNT(*)} nunca es
 * nulo, así que un plan sin líneas responde {@code 0}. El estado viaja como su nombre canónico,
 * que es el literal del {@code CHECK ck_planes_estado}.
 */
public class PlanProduccionRowMapper implements RowMapper<PlanProduccion> {

    @Override
    public PlanProduccion mapRow(ResultSet rs, int rowNum) throws SQLException {
        var plan = new PlanProduccion();
        plan.setIdPlan(rs.getObject("id_plan", Integer.class));
        plan.setFechaPlanificacion(rs.getObject("fecha_planificacion", LocalDate.class));
        plan.setFechaProduccion(rs.getObject("fecha_produccion", LocalDate.class));
        plan.setEstado(EstadoPlan.valueOf(rs.getString("estado")));
        plan.setObservaciones(rs.getString("observaciones"));
        plan.setIdUsuario(rs.getObject("id_usuario", Integer.class));
        plan.setNombreResponsable(rs.getString("nombre_responsable"));
        plan.setCantidadProductos(rs.getInt("cantidad_productos"));
        return plan;
    }
}
