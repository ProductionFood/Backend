package co.edu.corposucre.productionfood.common.audit;

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
}
