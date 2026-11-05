package co.edu.corposucre.productionfood.reporte;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Consultas de solo lectura del reporte de eficiencia de producción (HU-26).
 *
 * <p>Devuelven filas crudas: los porcentajes, los promedios y la tendencia se calculan en el
 * servicio con {@code CalculadoraEficienciaService}, para que el reporte y el registro de
 * consumos (HU-22) no puedan divergir. Aquí solo hay {@code SUM} y {@code COUNT}.
 *
 * <p>Un consumo «registrado» es el que tiene {@code cantidad_real} no nula (R-05). El filtro de
 * fechas es inclusivo en ambos extremos sobre {@code fecha_produccion}, sin funciones sobre la
 * columna, y solo se agrega a la consulta cuando el extremo existe; así MySQL usa
 * {@code idx_planes_fecha}.
 */
@Repository
public class EficienciaRepository {

    private static final String CONSUMO_REGISTRADO = """
            EXISTS (SELECT 1
                    FROM detalle_plan_produccion d
                    JOIN consumo_materia_prima c ON c.id_detalle_plan = d.id_detalle_plan
                    WHERE d.id_plan = p.id_plan AND c.cantidad_real IS NOT NULL)
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public EficienciaRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Planes del período con al menos un consumo registrado, de cualquier estado (R-05): los
     * planificados sin consumos quedan fuera solos y los cancelados vienen marcados por su
     * estado. Del más reciente al más antiguo.
     */
    public List<PlanEficienciaFila> planesConConsumo(LocalDate fechaInicio, LocalDate fechaFin) {
        var params = new MapSqlParameterSource();
        var sql = SELECT_PLAN + " WHERE " + CONSUMO_REGISTRADO + filtroFechas(fechaInicio, fechaFin, params)
                + " ORDER BY p.fecha_produccion DESC, p.id_plan DESC";
        return jdbc.query(sql, params, (rs, i) -> filaPlan(rs));
    }

    /** El plan sin importar si tiene consumos, para distinguir «inexistente» de «sin consumos». */
    public Optional<PlanEficienciaFila> plan(int idPlan) {
        return jdbc.query(SELECT_PLAN + " WHERE p.id_plan = :id",
                new MapSqlParameterSource("id", idPlan), (rs, i) -> filaPlan(rs))
                .stream().findFirst();
    }

    /** Líneas de producto de los planes indicados, en el orden de inserción de cada plan. */
    public List<ProductoPlanFila> productosDePlanes(Collection<Integer> idsPlan) {
        if (idsPlan.isEmpty()) {
            return List.of();
        }
        return jdbc.query("""
                SELECT d.id_plan, pr.nombre, d.cantidad_planificada, d.cantidad_producida
                FROM detalle_plan_produccion d
                JOIN productos pr ON pr.id_producto = d.id_producto
                WHERE d.id_plan IN (:ids)
                ORDER BY d.id_plan, d.id_detalle_plan
                """, new MapSqlParameterSource("ids", idsPlan),
                (rs, i) -> new ProductoPlanFila(rs.getInt("id_plan"), rs.getString("nombre"),
                        rs.getBigDecimal("cantidad_planificada"),
                        rs.getBigDecimal("cantidad_producida")));
    }

    /**
     * Consumos registrados de los planes indicados, cada uno con la planificada y la producida de
     * su línea (R-02). Ordenados por plan, materia prima y consumo.
     */
    public List<ConsumoEficienciaFila> consumosDePlanes(Collection<Integer> idsPlan) {
        if (idsPlan.isEmpty()) {
            return List.of();
        }
        return jdbc.query("""
                SELECT d.id_plan, mp.nombre, u.abreviatura, c.cantidad_teorica, c.cantidad_real,
                       d.cantidad_planificada, d.cantidad_producida
                FROM consumo_materia_prima c
                JOIN detalle_plan_produccion d ON d.id_detalle_plan = c.id_detalle_plan
                JOIN materias_primas mp ON mp.id_materia_prima = c.id_materia_prima
                JOIN unidades_medida u ON u.id_unidad = mp.id_unidad
                WHERE d.id_plan IN (:ids) AND c.cantidad_real IS NOT NULL
                ORDER BY d.id_plan, mp.nombre, c.id_consumo
                """, new MapSqlParameterSource("ids", idsPlan),
                (rs, i) -> new ConsumoEficienciaFila(rs.getInt("id_plan"), rs.getString("nombre"),
                        rs.getString("abreviatura"), rs.getBigDecimal("cantidad_teorica"),
                        rs.getBigDecimal("cantidad_real"),
                        rs.getBigDecimal("cantidad_planificada"),
                        rs.getBigDecimal("cantidad_producida")));
    }

    /**
     * Acumulado por materia prima de los planes completados del período, sin los cancelados ni los
     * que siguen en proceso (R-06): suma del
     * teórico y del real de los consumos registrados y cantidad de planes distintos.
     */
    public List<MateriaPrimaAcumuladaFila> materiasPrimasAcumuladas(LocalDate fechaInicio,
                                                                    LocalDate fechaFin) {
        var params = new MapSqlParameterSource();
        var sql = """
                SELECT mp.nombre, u.abreviatura, SUM(c.cantidad_teorica) AS teorica,
                       SUM(c.cantidad_real) AS real_total, COUNT(DISTINCT p.id_plan) AS planes
                FROM planes_produccion p
                JOIN detalle_plan_produccion d ON d.id_plan = p.id_plan
                JOIN consumo_materia_prima c ON c.id_detalle_plan = d.id_detalle_plan
                JOIN materias_primas mp ON mp.id_materia_prima = c.id_materia_prima
                JOIN unidades_medida u ON u.id_unidad = mp.id_unidad
                WHERE p.estado = 'COMPLETADO' AND c.cantidad_real IS NOT NULL
                """ + filtroFechas(fechaInicio, fechaFin, params) + """
                GROUP BY mp.id_materia_prima, mp.nombre, u.abreviatura
                ORDER BY mp.nombre
                """;
        return jdbc.query(sql, params, (rs, i) -> new MateriaPrimaAcumuladaFila(
                rs.getString("nombre"), rs.getString("abreviatura"), rs.getBigDecimal("teorica"),
                rs.getBigDecimal("real_total"), rs.getLong("planes")));
    }

    /**
     * Productos de los planes del período (sin los cancelados) que no tienen ninguna receta
     * activa (R-07), con los planes distintos que los incluyen.
     */
    public List<SinRecetaFila> productosSinReceta(LocalDate fechaInicio, LocalDate fechaFin) {
        var params = new MapSqlParameterSource();
        var sql = """
                SELECT pr.nombre, COUNT(DISTINCT p.id_plan) AS planes
                FROM planes_produccion p
                JOIN detalle_plan_produccion d ON d.id_plan = p.id_plan
                JOIN productos pr ON pr.id_producto = d.id_producto
                WHERE p.estado <> 'CANCELADO'
                  AND NOT EXISTS (SELECT 1 FROM recetas r
                                  WHERE r.id_producto = d.id_producto AND r.estado = 1)
                """ + filtroFechas(fechaInicio, fechaFin, params) + """
                GROUP BY pr.id_producto, pr.nombre
                ORDER BY pr.nombre
                """;
        return jdbc.query(sql, params,
                (rs, i) -> new SinRecetaFila(rs.getString("nombre"), rs.getLong("planes")));
    }

    private static final String SELECT_PLAN = """
            SELECT p.id_plan, p.fecha_produccion, p.estado, u.nombre AS responsable
            FROM planes_produccion p
            JOIN usuarios u ON u.id_usuario = p.id_usuario
            """;

    private static PlanEficienciaFila filaPlan(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new PlanEficienciaFila(rs.getInt("id_plan"),
                rs.getObject("fecha_produccion", LocalDate.class), rs.getString("estado"),
                rs.getString("responsable"));
    }

    /**
     * Condiciones inclusivas sobre {@code p.fecha_produccion}, una por cada extremo presente.
     * Sin funciones sobre la columna, para aprovechar {@code idx_planes_fecha}.
     */
    private static String filtroFechas(LocalDate inicio, LocalDate fin, MapSqlParameterSource params) {
        var condiciones = new StringBuilder();
        if (inicio != null) {
            condiciones.append(" AND p.fecha_produccion >= :inicio ");
            params.addValue("inicio", inicio);
        }
        if (fin != null) {
            condiciones.append(" AND p.fecha_produccion <= :fin ");
            params.addValue("fin", fin);
        }
        return condiciones.toString();
    }
}
