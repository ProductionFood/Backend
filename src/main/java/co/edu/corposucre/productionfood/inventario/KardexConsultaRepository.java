package co.edu.corposucre.productionfood.inventario;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import co.edu.corposucre.productionfood.inventario.dto.MovimientoResponse;

/**
 * Consulta de solo lectura del kardex de materia prima
 * ({@code movimientos_inventario_mp}, V2 §C-04).
 *
 * <p>Se usa JDBC y no JPA porque la tabla no tiene entidad: la aplicación solo inserta en ella a
 * través de {@link MovimientoInventarioRepository}, y este repositorio se limita a leer. Separar
 * lectura y escritura evita convertir el repositorio de inserción en un repositorio mixto y deja
 * el kardex como una proyección explícita.
 *
 * <p>El orden es fijo por {@code id_movimiento DESC} y no acepta el orden del cliente: el kardex
 * se lee siempre del movimiento más reciente al más antiguo y así aprovecha el índice
 * {@code idx_mov_mp_materia}.
 */
@Repository
public class KardexConsultaRepository {

    /**
     * Origen de la consulta, compartido por {@link #buscar} y {@link #contar} para que el total
     * no pueda divergir de la página por un JOIN distinto.
     *
     * <p>El {@code LEFT JOIN} con {@code usuarios} es necesario porque {@code id_usuario} admite
     * {@code NULL}: un {@code INNER JOIN} ocultaría los movimientos del sistema (CP-16).
     */
    private static final String FROM = """
            FROM movimientos_inventario_mp m
            LEFT JOIN usuarios u ON u.id_usuario = m.id_usuario
            """;

    /**
     * Columnas enumeradas y con alias: {@code usuario_nombre} es el nombre que lee
     * {@link MovimientoInventarioRowMapper}, y una columna nueva en la tabla no debe cambiar la
     * proyección por accidente.
     */
    private static final String SELECT = """
            SELECT m.fecha, m.tipo_movimiento, m.cantidad, m.saldo_anterior,
                   m.saldo_posterior, m.tabla_origen, m.id_origen,
                   u.nombre AS usuario_nombre
            """ + FROM;

    private final NamedParameterJdbcTemplate jdbc;

    public KardexConsultaRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Devuelve una página del kardex de una materia prima, del movimiento más reciente al más
     * antiguo.
     *
     * @param idMateriaPrima materia prima cuyo kardex se consulta
     * @param desde          primer día incluido del rango, o {@code null} sin límite inferior
     * @param hasta          último día incluido del rango, o {@code null} sin límite superior
     * @param size           tamaño de página
     * @param offset         desplazamiento de la página; es {@code long} porque {@code page} no
     *                       tiene cota superior y {@code page * size} desbordaría un int
     */
    public List<MovimientoResponse> buscar(Integer idMateriaPrima, LocalDate desde,
                                           LocalDate hasta, int size, long offset) {
        var params = new MapSqlParameterSource();
        var where = armarFiltros(idMateriaPrima, desde, hasta, params);
        params.addValue("tamano", size).addValue("desplazamiento", offset);

        var sql = SELECT + where
                + " ORDER BY m.id_movimiento DESC"
                + " LIMIT :tamano OFFSET :desplazamiento";

        return jdbc.query(sql, params, new MovimientoInventarioRowMapper());
    }

    /**
     * Cuenta los movimientos que cumplen los filtros, sin traerlos.
     *
     * <p>Reutiliza el mismo {@code FROM} y el mismo {@code WHERE} que {@link #buscar}: el total de
     * la paginación sale de aquí y debe corresponder exactamente a las filas que se paginan.
     *
     * @param idMateriaPrima materia prima cuyo kardex se cuenta
     * @param desde          primer día incluido del rango, o {@code null} sin límite inferior
     * @param hasta          último día incluido del rango, o {@code null} sin límite superior
     */
    public long contar(Integer idMateriaPrima, LocalDate desde, LocalDate hasta) {
        var params = new MapSqlParameterSource();
        var where = armarFiltros(idMateriaPrima, desde, hasta, params);

        Long total = jdbc.queryForObject("SELECT COUNT(*) " + FROM + where, params, Long.class);
        return total == null ? 0L : total;
    }

    /**
     * Fragmento {@code WHERE} compartido por {@link #buscar} y {@link #contar}.
     *
     * <p>Los extremos del rango se convierten a {@link Timestamp} en Java: {@code desde} se toma
     * como la medianoche de ese día y {@code hasta} como la medianoche del día siguiente,
     * comparada con {@code <}. Así el rango es inclusivo en ambos extremos sin aplicar funciones
     * sobre la columna {@code fecha}, que impedirían usar el índice.
     */
    private static String armarFiltros(Integer idMateriaPrima, LocalDate desde, LocalDate hasta,
                                       MapSqlParameterSource params) {
        var where = new StringBuilder(" WHERE m.id_materia_prima = :idMateriaPrima");
        params.addValue("idMateriaPrima", idMateriaPrima);
        if (desde != null) {
            where.append(" AND m.fecha >= :desde");
            params.addValue("desde", Timestamp.valueOf(desde.atStartOfDay()));
        }
        if (hasta != null) {
            where.append(" AND m.fecha < :hastaExclusivo");
            params.addValue("hastaExclusivo", Timestamp.valueOf(hasta.plusDays(1).atStartOfDay()));
        }
        return where.toString();
    }
}
