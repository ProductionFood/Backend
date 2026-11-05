package co.edu.corposucre.productionfood.inventario;

import java.util.List;
import java.util.Optional;

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
     * Consulta el nivel de UNA materia prima y la devuelve solo si está {@code AGOTADO} o
     * {@code BAJO}; con stock suficiente (incluida la igualdad exacta con el mínimo) o si no
     * existe, el resultado es vacío. Usa la misma vista y el mismo filtro de nivel que
     * {@link #listarAlertas(boolean)}; no filtra por estado activo porque la usa quien acaba de
     * mover el inventario de esa materia prima.
     *
     * @param idMateriaPrima materia prima consultada
     */
    public Optional<AlertaStockFila> buscarAlertaPorMateriaPrima(Integer idMateriaPrima) {
        return jdbc.query("""
                SELECT id_materia_prima, nombre, unidad, cantidad_disponible,
                       stock_minimo, faltante, estado_stock, costo_unitario, fecha_actualizacion
                FROM v_inventario_materia_prima
                WHERE id_materia_prima = :idMateriaPrima
                  AND estado_stock IN ('BAJO', 'AGOTADO')
                """,
                new MapSqlParameterSource().addValue("idMateriaPrima", idMateriaPrima),
                new AlertaStockRowMapper()).stream().findFirst();
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

    /**
     * Para cada materia prima activa en alerta, el proveedor de su última compra {@code RECIBIDA}
     * que la incluya (R-06): la de fecha más reciente y, a igual fecha, la de mayor id. Si nunca
     * se recibió, {@code idProveedor} y {@code nombreProveedor} salen nulos. Conserva el orden de
     * urgencia de {@link #listarAlertas(boolean)}.
     */
    public List<SugerenciaFila> sugerenciaCompra() {
        return jdbc.query("""
                SELECT v.id_materia_prima, v.nombre, v.unidad, v.cantidad_disponible,
                       v.stock_minimo, v.faltante, v.estado_stock, v.costo_unitario,
                       v.fecha_actualizacion, p.id_proveedor, p.nombre AS nombre_proveedor
                FROM v_inventario_materia_prima v
                LEFT JOIN proveedores p ON p.id_proveedor = (
                    SELECT c.id_proveedor
                    FROM compras c
                    JOIN detalle_compra d ON d.id_compra = c.id_compra
                    WHERE c.estado = 'RECIBIDA' AND d.id_materia_prima = v.id_materia_prima
                    ORDER BY c.fecha DESC, c.id_compra DESC
                    LIMIT 1)
                WHERE v.estado_stock IN ('BAJO', 'AGOTADO') AND v.estado = 1 AND v.faltante > 0
                ORDER BY FIELD(v.estado_stock, 'AGOTADO', 'BAJO'), v.faltante DESC
                """,
                new MapSqlParameterSource(),
                (rs, fila) -> new SugerenciaFila(
                        new AlertaStockRowMapper().mapRow(rs, fila),
                        rs.getObject("id_proveedor", Integer.class),
                        rs.getString("nombre_proveedor")));
    }
}
