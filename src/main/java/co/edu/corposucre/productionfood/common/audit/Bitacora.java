package co.edu.corposucre.productionfood.common.audit;

import java.time.LocalDateTime;

/**
 * Fila de {@code bitacora} con los datos del autor resueltos por JOIN.
 *
 * <p>{@code idUsuario} nulo significa que la acción la realizó el sistema y no una persona
 * (por ejemplo, la script de semilla), por eso el resto de datos del autor pueden venir
 * también nulos en ese caso.
 *
 * @param idBitacora    identificador del registro de auditoría
 * @param idUsuario     autor de la acción, o null si la ejecutó el sistema
 * @param usuarioNombre nombre del autor, o null si la acción la ejecutó el sistema
 * @param accion        código de la acción registrada
 * @param tablaAfectada tabla sobre la que se produjo el cambio
 * @param detalle       resumen legible de la acción, o null si se registró sin resumen
 * @param fecha         momento en que ocurrió la acción
 */
public record Bitacora(
        Integer idBitacora,
        Integer idUsuario,
        String usuarioNombre,
        String accion,
        String tablaAfectada,
        String detalle,
        LocalDateTime fecha
) {
}
