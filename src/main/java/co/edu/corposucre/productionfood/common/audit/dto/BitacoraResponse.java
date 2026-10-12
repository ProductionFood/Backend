package co.edu.corposucre.productionfood.common.audit.dto;

import java.time.LocalDateTime;

/**
 * Fila de la bitácora tal como la ve el cliente.
 *
 * <p>{@code usuario} es nulo cuando la acción la ejecutó el sistema ({@code idUsuario} nulo).
 * El JOIN garantiza que un {@code idUsuario} no nulo trae su nombre, por lo que no se contempla
 * un usuario sin nombre: no se rellena con un valor inventado, se expone el que trae la consulta.
 */
public record BitacoraResponse(
        Integer idBitacora,
        Usuario usuario,
        String accion,
        String tablaAfectada,
        String detalle,
        LocalDateTime fecha
) {
    public record Usuario(Integer idUsuario, String nombre) {}
}
