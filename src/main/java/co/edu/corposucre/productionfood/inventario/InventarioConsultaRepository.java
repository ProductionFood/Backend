package co.edu.corposucre.productionfood.inventario;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import co.edu.corposucre.productionfood.inventario.dto.InventarioResponse;

/**
 * Consulta de solo lectura del inventario de materia prima sobre la vista
 * {@code v_inventario_materia_prima} (R-02).
 *
 * <p>Se usa JDBC y no JPA porque la vista no tiene entidad: mapearla obligaría a duplicar el
 * modelo en Hibernate para una proyección que nunca se modifica desde aquí (R-01). El mapeo
 * explícito de {@link InventarioConsultaRowMapper} deja a la vista como única fuente del cálculo
 * de {@code estado_stock} y {@code faltante}.
 */
@Repository
public class InventarioConsultaRepository {

    /**
     * Columnas enumeradas y no {@code SELECT *}: el mapeo depende de estos nombres y una
     * columna nueva en la vista no debe cambiar la proyección de la consulta por accidente.
     */
    private static final String SELECT = """
            SELECT id_materia_prima, nombre, unidad, stock_minimo, estado,
                   cantidad_disponible, fecha_actualizacion, estado_stock, faltante
            FROM v_inventario_materia_prima
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public InventarioConsultaRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Devuelve una página de la vista con los filtros de HU-10.
     *
     * <p>{@code columnaOrden} NUNCA es entrada directa del usuario: la resuelve el servicio contra
     * su lista blanca antes de llamar aquí, de modo que concatenarla es seguro. Cualquier otro
     * valor sería un error de programación del llamante y produciría un SQL inválido, no un orden
     * silenciosamente distinto.
     *
     * @param idMateriaPrima  filtra por una materia prima, o {@code null} para no restringir
     * @param soloStockBajo   true para limitar a {@code BAJO} y {@code AGOTADO}
     * @param incluirInactivas true para no filtrar por {@code estado}
     * @param columnaOrden    columna SQL de orden, ya resuelta por el servicio
     * @param ascendente      true para ASC, false para DESC
     * @param size            tamaño de página
     * @param offset          desplazamiento de la página; es {@code long} porque {@code page} no
     *                        tiene cota superior y {@code page * size} desbordaría un int
     */
    public List<InventarioResponse> buscar(Integer idMateriaPrima, boolean soloStockBajo,
                                           boolean incluirInactivas, String columnaOrden,
                                           boolean ascendente, int size, long offset) {
        var params = new MapSqlParameterSource();
        var where = armarFiltros(idMateriaPrima, soloStockBajo, incluirInactivas, params);
        params.addValue("tamano", size).addValue("desplazamiento", offset);

        var sql = SELECT + where
                + " ORDER BY " + columnaOrden + (ascendente ? " ASC" : " DESC")
                + " LIMIT :tamano OFFSET :desplazamiento";

        return jdbc.query(sql, params, new InventarioConsultaRowMapper());
    }

    /**
     * Cuenta las filas que cumplen los filtros, sin traerlas.
     *
     * <p>El total de la paginación sale de aquí y no del tamaño de la página: una página con 20
     * filas puede representar miles de registros.
     *
     * @param idMateriaPrima   filtra por una materia prima, o {@code null} para no restringir
     * @param soloStockBajo    true para limitar a {@code BAJO} y {@code AGOTADO}
     * @param incluirInactivas true para no filtrar por {@code estado}
     */
    public long contar(Integer idMateriaPrima, boolean soloStockBajo, boolean incluirInactivas) {
        var params = new MapSqlParameterSource();
        var where = armarFiltros(idMateriaPrima, soloStockBajo, incluirInactivas, params);

        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM v_inventario_materia_prima " + where, params, Long.class);
        return total == null ? 0L : total;
    }

    /**
     * Devuelve el detalle de una materia prima dentro de la vista.
     *
     * <p>Se resuelve con {@code query(...).stream().findFirst()} en vez de
     * {@code queryForObject} para que la ausencia sea un {@link Optional#empty()} y no una
     * {@code EmptyResultDataAccessException} que el servicio tendría que traducir.
     *
     * @param idMateriaPrima identificador de la materia prima
     * @return la fila de la vista, o vacío si no existe la materia prima
     */
    public Optional<InventarioResponse> buscarPorMateriaPrima(Integer idMateriaPrima) {
        var params = new MapSqlParameterSource().addValue("idMateriaPrima", idMateriaPrima);
        return jdbc.query(SELECT + " WHERE id_materia_prima = :idMateriaPrima", params,
                new InventarioConsultaRowMapper()).stream().findFirst();
    }

    /**
     * Fragmento {@code WHERE} compartido por {@link #buscar} y {@link #contar}, para que ambos
     * apliquen exactamente los mismos filtros y el total no diverja de la página.
     *
     * <p>Solo se concatenan fragmentos constantes; los valores del cliente viajan siempre como
     * parámetros con nombre.
     */
    private static String armarFiltros(Integer idMateriaPrima, boolean soloStockBajo,
                                       boolean incluirInactivas, MapSqlParameterSource params) {
        var where = new StringBuilder(" WHERE 1=1");
        if (idMateriaPrima != null) {
            where.append(" AND id_materia_prima = :idMateriaPrima");
            params.addValue("idMateriaPrima", idMateriaPrima);
        }
        if (soloStockBajo) {
            // estado_stock lo calcula la vista (R-05): AGOTADO y BAJO son las dos formas de
            // estar por debajo del mínimo.
            where.append(" AND estado_stock IN ('BAJO', 'AGOTADO')");
        }
        if (!incluirInactivas) {
            // R-04: por defecto las inactivas con stock se muestran; solo se excluyen si el
            // cliente lo pide explícitamente.
            where.append(" AND estado = 1");
        }
        return where.toString();
    }
}
