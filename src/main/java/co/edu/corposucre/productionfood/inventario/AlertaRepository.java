package co.edu.corposucre.productionfood.inventario;

import java.util.List;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import co.edu.corposucre.productionfood.inventario.dto.ResumenAlertasResponse;

/**
 * Consulta de solo lectura de las alertas de stock bajo (HU-27) sobre la vista
 * {@code v_inventario_materia_prima}.
 *
 * <p>La vista ya resuelve {@code estado_stock} y {@code faltante}, incluida la comparación
 * estricta {@code <} (R-02); aquí no se recalculan.
 */
@Repository
public class AlertaRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public AlertaRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Lista las materias primas {@code AGOTADO} o {@code BAJO}, de la más urgente a la menos:
     * primero las agotadas y, dentro de cada nivel, el mayor faltante (R-03).
     *
     * @param incluirInactivas true para no excluir las materias primas desactivadas (R-05)
     */
    public List<AlertaStockFila> listarAlertas(boolean incluirInactivas) {
        return jdbc.query("""
                SELECT id_materia_prima, nombre, unidad, cantidad_disponible,
                       stock_minimo, faltante, estado_stock, costo_unitario, fecha_actualizacion
                FROM v_inventario_materia_prima
                WHERE estado_stock IN ('BAJO', 'AGOTADO')
                  AND (:incluirInactivas OR estado = 1)
                ORDER BY FIELD(estado_stock, 'AGOTADO', 'BAJO'), faltante DESC
                """,
                new MapSqlParameterSource().addValue("incluirInactivas", incluirInactivas),
                new AlertaStockRowMapper());
    }

    /**
     * Cuenta las alertas de materias primas activas sin traer las filas: alimenta un indicador
     * que se consulta desde todas las pantallas.
     */
    public ResumenAlertasResponse resumen() {
        return jdbc.queryForObject("""
                SELECT COUNT(*) AS total,
                       COUNT(CASE WHEN estado_stock = 'AGOTADO' THEN 1 END) AS agotadas,
                       COUNT(CASE WHEN estado_stock = 'BAJO' THEN 1 END) AS bajas
                FROM v_inventario_materia_prima
                WHERE estado_stock IN ('BAJO', 'AGOTADO') AND estado = 1
                """,
                new MapSqlParameterSource(),
                (rs, fila) -> new ResumenAlertasResponse(
                        rs.getLong("total"), rs.getLong("agotadas"), rs.getLong("bajas")));
    }
}
