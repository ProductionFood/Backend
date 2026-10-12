package co.edu.corposucre.productionfood.common.audit;

import java.time.LocalDateTime;

/**
 * Fila de {@code bitacora} con los datos del autor resueltos por JOIN.
 *
 * <p>Se incluye {@code idRol} porque la auditoría no solo necesita saber QUIÉN actuó sino
 * BAJO QUÉ PERMISOS lo hizo: un cambio de rol posterior es justamente el escenario que un
 *investigador necesita poder contrastar, y el rol actual es la única referencia disponible
 * sin duplicar el historial. Sigue el mismo criterio que {@code UsuarioResponse}, que también
 * expone su rol. Al provenir de {@code usuarios.id_rol} no requiere un JOIN adicional.
 *
 * <p>{@code idUsuario} nulo significa que la acción la realizó el sistema y no una persona
 * (por ejemplo, la script de semilla), por eso el resto de datos del autor pueden venir
 * también nulos en ese caso.
 *
 * @param idBitacora    identificador del registro de auditoría
 * @param idUsuario     autor de la acción, o null si la ejecutó el sistema
 * @param usuarioNombre nombre del autor, o null si la acción la ejecutó el sistema
 * @param idRol         rol del autor, o null si la acción la ejecutó el sistema
 * @param accion        código de la acción registrada
 * @param tablaAfectada tabla sobre la que se produjo el cambio
 * @param detalle       resumen legible de la acción, o null si se registró sin resumen
 * @param fecha         momento en que ocurrió la acción
 */
public record Bitacora(
        Integer idBitacora,
        Integer idUsuario,
        String usuarioNombre,
        Integer idRol,
        String accion,
        String tablaAfectada,
        String detalle,
        LocalDateTime fecha
) {
}
