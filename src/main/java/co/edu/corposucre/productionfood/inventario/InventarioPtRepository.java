package co.edu.corposucre.productionfood.inventario;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import co.edu.corposucre.productionfood.inventario.dto.MovimientoResponse;

/**
 * Escritura y consulta del inventario de producto terminado
 * ({@code inventario_producto_terminado} y {@code movimientos_inventario_pt}, V2 §C-02 y §C-04).
 *
 * <p>Se usa JDBC y no JPA porque el kardex no tiene entidad: la aplicación solo inserta en él y lo
 * lee de forma plana. El saldo del lote es un dato que mueven otras historias (HU-17, HU-18) y el
 * kardex queda como una proyección explícita.
 *
 * <p>Ninguno de los métodos abre transacción: la transacción es la que abre el servicio, que debe
 * cubrir el {@code INSERT} del lote, su fila de inventario y el movimiento de entrada como una
 * sola operación (R-02).
 */
@Repository
public class InventarioPtRepository {

    /**
     * Origen de la consulta del kardex, compartido por {@link #buscarMovimientos} y
     * {@link #contarMovimientos} para que el total no pueda divergir de la página por un JOIN
     * distinto.
     *
     * <p>El {@code LEFT JOIN} con {@code usuarios} es necesario porque {@code id_usuario} admite
     * {@code NULL}: un {@code INNER JOIN} ocultaría los movimientos del sistema (CP-16).
     */
    private static final String FROM_MOVIMIENTOS = """
            FROM movimientos_inventario_pt m
            LEFT JOIN usuarios u ON u.id_usuario = m.id_usuario
            """;

    /**
     * Columnas enumeradas y con alias: {@code usuario_nombre} es el nombre que lee
     * {@link MovimientoPtRowMapper}, y una columna nueva en la tabla no debe cambiar la proyección
     * por accidente.
     */
    private static final String SELECT_MOVIMIENTOS = """
            SELECT m.fecha, m.tipo_movimiento, m.cantidad, m.saldo_anterior,
                   m.saldo_posterior, m.tabla_origen, m.id_origen,
                   u.nombre AS usuario_nombre
            """ + FROM_MOVIMIENTOS;

    private final NamedParameterJdbcTemplate jdbc;

    public InventarioPtRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Crea la fila de inventario de un lote recién registrado (CA-05).
     *
     * <p>Es lo que convierte el lote en stock vendible: el saldo inicial coincide con la cantidad
     * de ingreso. La relación es {@code 1:1} porque {@code id_lote} es {@code UNIQUE}, y la fila
     * debe insertarse en la misma transacción que el lote (R-02): un lote sin inventario no aparece
     * en el stock y no da ningún error.
     *
     * @param idLote   lote al que pertenece el saldo
     * @param cantidad cantidad de ingreso, que pasa a ser el saldo disponible
     */
    public void crearParaLote(Integer idLote, BigDecimal cantidad) {
        var params = new MapSqlParameterSource()
                .addValue("idLote", idLote)
                .addValue("cantidad", cantidad);

        jdbc.update("""
                INSERT INTO inventario_producto_terminado (id_lote, cantidad_disponible)
                VALUES (:idLote, :cantidad)
                """, params);
    }

    /**
     * Inserta un movimiento de kardex de producto terminado.
     *
     * <p>{@code id_usuario} y {@code fecha} se omiten a propósito: la fecha la pone
     * {@code DEFAULT CURRENT_TIMESTAMP} y el usuario se añadirá cuando el llamador pueda
     * resolverlo; ambos admiten {@code NULL}. {@code id_lote} y los dos saldos son obligatorios
     * porque el kardex por lote es la trazabilidad completa de la historia.
     *
     * @param idLote         lote cuyo saldo cambia
     * @param tipo           tipo de movimiento del catálogo de V2
     * @param cantidad       cantidad movida, positiva: el signo lo da el tipo
     * @param saldoAnterior  saldo del lote antes del movimiento
     * @param saldoPosterior saldo del lote después del movimiento
     * @param tablaOrigen    tabla que originó el movimiento, o {@code null}
     * @param idOrigen       identificador de la fila origen, o {@code null}
     * @param observacion    texto libre para el kardex, o {@code null}
     */
    public void registrarMovimiento(Integer idLote, TipoMovimientoPt tipo, BigDecimal cantidad,
                                    BigDecimal saldoAnterior, BigDecimal saldoPosterior,
                                    String tablaOrigen, Integer idOrigen, String observacion) {
        // El tipo viaja como String (el nombre del enum) y no como enum: el CHECK de la base
        // compara contra esos literales y un ordinal lo rechazaría.
        var params = new MapSqlParameterSource()
                .addValue("idLote", idLote)
                .addValue("tipo", tipo.name())
                .addValue("cantidad", cantidad)
                .addValue("saldoAnterior", saldoAnterior)
                .addValue("saldoPosterior", saldoPosterior)
                .addValue("tablaOrigen", tablaOrigen)
                .addValue("idOrigen", idOrigen)
                .addValue("observacion", observacion);

        jdbc.update("""
                INSERT INTO movimientos_inventario_pt
                    (id_lote, tipo_movimiento, cantidad, saldo_anterior, saldo_posterior,
                     tabla_origen, id_origen, observacion)
                VALUES (:idLote, :tipo, :cantidad, :saldoAnterior, :saldoPosterior,
                        :tablaOrigen, :idOrigen, :observacion)
                """, params);
    }

    /**
     * Devuelve una página del kardex de un lote, ordenada por la columna y la dirección recibidas.
     *
     * <p>{@code columnaOrden} NUNCA es entrada directa del usuario: la resuelve el servicio contra
     * su lista blanca antes de llamar aquí, de modo que concatenarla es seguro. Cualquier otro
     * valor sería un error de programación del llamante y produciría un SQL inválido, no un orden
     * silenciosamente distinto.
     *
     * <p>El {@code ORDER BY} añade siempre {@code m.id_movimiento DESC} como desempate: la columna
     * pedida puede repetirse (dos entradas con la misma fecha o la misma cantidad) y sin un
     * criterio único la paginación podría devolver la misma fila en dos páginas y omitir otra.
     *
     * @param idLote       lote cuyo kardex se consulta
     * @param columnaOrden columna SQL de orden, ya resuelta por el servicio
     * @param ascendente   true para ASC, false para DESC
     * @param size         tamaño de página
     * @param offset       desplazamiento de la página; es {@code long} porque {@code page} no tiene
     *                     cota superior y {@code page * size} desbordaría un int
     */
    public List<MovimientoResponse> buscarMovimientos(Integer idLote, String columnaOrden,
                                                      boolean ascendente, int size, long offset) {
        var params = new MapSqlParameterSource()
                .addValue("idLote", idLote)
                .addValue("tamano", size)
                .addValue("desplazamiento", offset);

        var sql = SELECT_MOVIMIENTOS
                + " WHERE m.id_lote = :idLote"
                + " ORDER BY " + columnaOrden + (ascendente ? " ASC" : " DESC")
                + ", m.id_movimiento DESC"
                + " LIMIT :tamano OFFSET :desplazamiento";

        return jdbc.query(sql, params, new MovimientoPtRowMapper());
    }

    /**
     * Cuenta los movimientos del kardex de un lote, sin traerlos.
     *
     * <p>Reutiliza el mismo {@code FROM} que {@link #buscarMovimientos}: el total de la paginación
     * sale de aquí y debe corresponder exactamente a las filas que se paginan.
     *
     * @param idLote lote cuyo kardex se cuenta
     */
    public long contarMovimientos(Integer idLote) {
        var params = new MapSqlParameterSource().addValue("idLote", idLote);

        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) " + FROM_MOVIMIENTOS + " WHERE m.id_lote = :idLote",
                params, Long.class);
        return total == null ? 0L : total;
    }
}
