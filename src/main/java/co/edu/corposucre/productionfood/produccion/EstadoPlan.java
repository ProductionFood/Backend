package co.edu.corposucre.productionfood.produccion;

import java.util.Map;
import java.util.Set;

/**
 * Estado de un plan de producción y su máquina de transiciones (HU-20, R-01).
 *
 * <p>Los valores coinciden exactamente con el {@code CHECK ck_planes_estado} que V2 (§C-12)
 * añadió a {@code planes_produccion}. Añadir un valor aquí sin actualizar la migración hace que
 * el {@code INSERT} falle en la base, no en la compilación.
 *
 * <p>La máquina de estados vive solo aquí: {@code PLANIFICADO} entra en producción
 * ({@code EN_PROCESO}) o se cancela, {@code EN_PROCESO} se completa ({@code COMPLETADO}) o se
 * cancela, y una vez en {@code COMPLETADO} o {@code CANCELADO} no admite ninguna transición más.
 * A diferencia de compras, pedidos y devoluciones, <strong>ninguna transición toca
 * inventario</strong> (R-06): el movimiento lo registra HU-22 y HU-23, no el cambio de estado.
 * El servicio de HU-20 delega en {@link #puedeTransicionarA(EstadoPlan)} para rechazar con un
 * 409 {@code TRANSICION_INVALIDA} cualquier salto prohibido —por ejemplo {@code COMPLETADO} a
 * {@code EN_PROCESO}— en lugar de reescribir las reglas en cada endpoint.
 */
public enum EstadoPlan {
    PLANIFICADO,
    EN_PROCESO,
    COMPLETADO,
    CANCELADO;

    /**
     * Destinos permitidos desde cada estado. Los {@code Set} son inmutables: nadie puede ampliar
     * la máquina de estados en tiempo de ejecución desde el código que consulta.
     */
    private static final Map<EstadoPlan, Set<EstadoPlan>> TRANSICIONES = Map.of(
            PLANIFICADO, Set.of(EN_PROCESO, CANCELADO),
            EN_PROCESO, Set.of(COMPLETADO, CANCELADO),
            COMPLETADO, Set.of(),
            CANCELADO, Set.of());

    /**
     * Conjunto inmutable de estados a los que se puede pasar desde este estado; vacío si el
     * estado es terminal.
     */
    public Set<EstadoPlan> transicionesValidas() {
        return TRANSICIONES.get(this);
    }

    /**
     * Indica si este plan en {@code this} puede pasar al estado {@code destino}.
     *
     * @param destino estado al que se quiere transicionar
     * @return {@code true} si la transición está permitida; una auto-transición nunca lo es
     */
    public boolean puedeTransicionarA(EstadoPlan destino) {
        return TRANSICIONES.get(this).contains(destino);
    }
}
