package co.edu.corposucre.productionfood.common.audit;

import java.sql.Timestamp;
import java.util.List;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Acceso a {@code bitacora} por JDBC, sin entidad JPA.
 *
 * <p>La tabla es de solo escritura desde la aplicación y se consulta de forma plana
 * (con JOIN a usuarios) para el listado de auditoría; mapearla a una entidad obligaría a
 * duplicar el modelo y a registrar en Hibernate una tabla que nunca se modifica
 * desde un repositorio JPA.
 */
@Repository
public class BitacoraRepository {

    /**
     * Proyección de lectura con el autor resuelto. El alias {@code usuario_nombre} es obligatorio
     * porque es la columna que lee {@link BitacoraRowMapper}; sin él el fallo aparece en runtime,
     * no en compilación.
     */
    private static final String SELECT_CON_AUTOR = """
            SELECT b.id_bitacora, b.id_usuario, u.nombre AS usuario_nombre, b.accion,
                   b.tabla_afectada, b.detalle, b.fecha
            FROM bitacora b
            LEFT JOIN usuarios u ON u.id_usuario = b.id_usuario
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public BitacoraRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Registra una acción de auditoría.
     *
     * @param idUsuario autor de la acción, o null si la ejecutó el sistema
     * @param accion    código de la acción, con máximo 50 caracteres
     * @param tabla     tabla afectada, con máximo 50 caracteres
     * @param detalle   resumen legible de la acción, o null si no aplica
     */
    public void insertar(Integer idUsuario, String accion, String tabla, String detalle) {
        // fecha no se incluye en el INSERT: la aporta DEFAULT CURRENT_TIMESTAMP y así la
        // marca de tiempo la fija la base de datos, no el reloj de la aplicación (CA-03).
        var params = new MapSqlParameterSource()
                .addValue("idUsuario", idUsuario)
                .addValue("accion", accion)
                .addValue("tabla", tabla)
                .addValue("detalle", detalle);

        jdbc.update("""
                INSERT INTO bitacora (id_usuario, accion, tabla_afectada, detalle)
                VALUES (:idUsuario, :accion, :tabla, :detalle)
                """, params);
    }

    /**
     * Devuelve una página de la bitácora, más reciente primero según el orden recibido.
     *
     * <p>{@code columnaOrden} NUNCA es entrada directa del usuario: la resuelve el servicio
     * contra su lista blanca antes de llamar aquí, de modo que concatenarla es seguro. Si
     * llegara null sería un error de programación del llamante (el servicio siempre aplica un
     * default) y se traduciría en un SQL inválido, no en un orden silenciosamente distinto.
     *
     * @param filtros      filtros opcionales; un campo nulo no restringe
     * @param columnaOrden columna SQL de orden, ya resuelta por la lista blanca del servicio
     * @param ascendente   true para ASC, false para DESC
     * @param size         tamaño de página
     * @param offset       desplazamiento de la página; es {@code long} porque {@code page}
     *                     no tiene cota superior y {@code page * size} desbordaría un int
     */
    public List<Bitacora> buscar(BitacoraFiltros filtros, String columnaOrden, boolean ascendente,
                                 int size, long offset) {
        var params = new MapSqlParameterSource();
        var where = armarFiltros(filtros, params);
        params.addValue("tamano", size).addValue("desplazamiento", offset);

        var sql = SELECT_CON_AUTOR + where
                + " ORDER BY " + columnaOrden + (ascendente ? " ASC" : " DESC")
                + " LIMIT :tamano OFFSET :desplazamiento";

        return jdbc.query(sql, params, new BitacoraRowMapper());
    }

    /**
     * Cuenta los registros que cumplen los filtros, sin traer filas.
     *
     * <p>El total de la paginación sale de aquí y no de {@code buscar().size()}: el tamaño de la
     * página miente sobre cuántas filas hay en total.
     *
     * @param filtros filtros opcionales; un campo nulo no restringe
     */
    public long contar(BitacoraFiltros filtros) {
        var params = new MapSqlParameterSource();
        var where = armarFiltros(filtros, params);

        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM bitacora b " + where, params, Long.class);
        return total == null ? 0L : total;
    }

    /**
     * Catálogo de acciones distintas ya registradas, ordenado alfabéticamente.
     */
    public List<String> listarAcciones() {
        return jdbc.queryForList(
                "SELECT DISTINCT accion FROM bitacora ORDER BY accion",
                new MapSqlParameterSource(), String.class);
    }

    /**
     * Fragmento {@code WHERE} compartido por {@link #buscar} y {@link #contar}, para que ambos
     * apliquen exactamente los mismos filtros y el total no diverja de la página.
     *
     * <p>El patrón {@code :x IS NULL OR ...} deja que la base ignore el filtro cuando llega nulo,
     * evitando construir la consulta con concatenación de cadenas.
     */
    private static String armarFiltros(BitacoraFiltros filtros, MapSqlParameterSource params) {
        params.addValue("idUsuario", filtros.idUsuario());
        params.addValue("tabla", filtros.tabla());
        params.addValue("accion", filtros.accion());
        params.addValue("fechaInicio", filtros.fechaInicio() == null
                ? null : Timestamp.valueOf(filtros.fechaInicio().atStartOfDay()));
        // El día siguiente se calcula en Java y se compara con '<' en vez de '<= fin': 'fecha'
        // es datetime, así que '<= 2026-09-18' compararía contra la medianoche del día 18 y
        // dejaría fuera todo lo ocurrido durante ese día salvo la propia medianoche.
        params.addValue("fechaFinMasUnDia", filtros.fechaFin() == null
                ? null : Timestamp.valueOf(filtros.fechaFin().plusDays(1).atStartOfDay()));

        return """
                WHERE (:idUsuario IS NULL OR b.id_usuario = :idUsuario)
                  AND (:tabla IS NULL OR b.tabla_afectada = :tabla)
                  AND (:accion IS NULL OR b.accion = :accion)
                  AND (:fechaInicio IS NULL OR b.fecha >= :fechaInicio)
                  AND (:fechaFinMasUnDia IS NULL OR b.fecha < :fechaFinMasUnDia)
                """;
    }
}
