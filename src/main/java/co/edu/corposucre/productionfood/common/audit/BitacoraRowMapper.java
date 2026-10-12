package co.edu.corposucre.productionfood.common.audit;

import java.sql.ResultSet;
import java.sql.SQLException;

import org.springframework.jdbc.core.RowMapper;

/**
 * Traduce la fila de {@code bitacora} unida a {@code usuarios} al record {@link Bitacora}.
 *
 * <p>El LEFT JOIN se usa porque {@code id_usuario} admite null (acciones del sistema): un
 * INNER JOIN descartaría esos registros, que son precisamente los que explican por qué
 * existe una fila sin autor.
 */
public class BitacoraRowMapper implements RowMapper<Bitacora> {

    @Override
    public Bitacora mapRow(ResultSet rs, int rowNum) throws SQLException {
        // getObject con clase y no getLong porque getLong devuelve 0 ante un NULL,
        // y el registro del sistema aparecería atribuido a un usuario inexistente.
        Long idUsuario = rs.getObject("id_usuario", Long.class);
        Long idRol = rs.getObject("id_rol", Long.class);

        return new Bitacora(
                rs.getObject("id_bitacora", Integer.class),
                idUsuario == null ? null : idUsuario.intValue(),
                // getString ya devuelve null si el LEFT JOIN no encontró autor.
                rs.getString("usuario_nombre"),
                idRol == null ? null : idRol.intValue(),
                rs.getString("accion"),
                rs.getString("tabla_afectada"),
                rs.getString("detalle"),
                rs.getTimestamp("fecha").toLocalDateTime()
        );
    }
}
