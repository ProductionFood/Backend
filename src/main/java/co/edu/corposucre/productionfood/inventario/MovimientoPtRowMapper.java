package co.edu.corposucre.productionfood.inventario;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;

import org.springframework.jdbc.core.RowMapper;

import co.edu.corposucre.productionfood.inventario.dto.MovimientoResponse;
import co.edu.corposucre.productionfood.inventario.dto.OrigenResponse;

/**
 * Traduce una fila del kardex de producto terminado, unida a {@code usuarios}, al record
 * {@link MovimientoResponse}.
 *
 * <p>La consulta debe proyectar estas columnas con estos alias: {@code m.fecha},
 * {@code m.tipo_movimiento}, {@code m.cantidad}, {@code m.saldo_anterior},
 * {@code m.saldo_posterior}, {@code m.tabla_origen}, {@code m.id_origen} y
 * {@code u.nombre AS usuario_nombre}. El alias es obligatorio porque es el nombre que este mapeo
 * lee; sin él el fallo aparece en runtime, no en compilación.
 *
 * <p>El {@code LEFT JOIN} con {@code usuarios} se usa porque {@code id_usuario} admite
 * {@code NULL} (movimientos del sistema): un {@code INNER JOIN} descartaría precisamente las
 * filas que no tienen autor humano (CP-16).
 */
public class MovimientoPtRowMapper implements RowMapper<MovimientoResponse> {

    private static final String USUARIO_SISTEMA = "Sistema";

    @Override
    public MovimientoResponse mapRow(ResultSet rs, int rowNum) throws SQLException {
        // getString ya devuelve null si el LEFT JOIN no encontró autor; se traduce a "Sistema"
        // y nunca a cadena vacía, que se confundiría con un nombre en blanco (CP-16).
        String usuario = rs.getString("usuario_nombre");
        String tablaOrigen = rs.getString("tabla_origen");
        // getObject con clase y no getInt: getInt devolvería 0 ante un NULL y el movimiento
        // aparecería apuntando a un documento inexistente.
        Integer idOrigen = rs.getObject("id_origen", Integer.class);

        // Solo se omite el origen cuando faltan los dos datos; con cualquiera de ellos se
        // devuelve el objeto y el campo ausente queda nulo.
        OrigenResponse origen = (tablaOrigen == null && idOrigen == null)
                ? null
                : new OrigenResponse(tablaOrigen, idOrigen);

        return new MovimientoResponse(
                // La columna es NOT NULL, pero se lee con getObject por la misma razón que en la
                // consulta de inventario: no se fuerza una conversión que podría lanzar NPE.
                rs.getObject("fecha", LocalDateTime.class),
                rs.getString("tipo_movimiento"),
                rs.getBigDecimal("cantidad"),
                rs.getBigDecimal("saldo_anterior"),
                rs.getBigDecimal("saldo_posterior"),
                origen,
                usuario == null ? USUARIO_SISTEMA : usuario
        );
    }
}
