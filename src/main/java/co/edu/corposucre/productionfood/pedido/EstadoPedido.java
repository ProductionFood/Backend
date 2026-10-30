package co.edu.corposucre.productionfood.pedido;

import java.util.Map;
import java.util.Set;

/**
 * Estado de un pedido y su máquina de transiciones (HU-16, R-02).
 *
 * <p>Los valores coinciden exactamente con el {@code CHECK ck_pedidos_estado} que V2 (§C-12)
 * añadió a {@code pedidos}. Añadir un valor aquí sin actualizar la migración hace que el
 * {@code INSERT} falle en la base, no en la compilación.
 *
 * <p>La máquina de estados vive solo aquí: {@code PENDIENTE} entra en preparación
 * ({@code EN_PREPARACION}) o se cancela ({@code CANCELADO}), y {@code EN_PREPARACION} se entrega
 * ({@code ENTREGADO}) o se cancela; una vez en {@code ENTREGADO} o {@code CANCELADO} no admite
 * ninguna transición más. El servicio de HU-16 delega en
 * {@link #puedeTransicionarA(EstadoPedido)} para rechazar con un 409 cualquier salto prohibido
 * ({@code ENTREGADO} a {@code PENDIENTE} o a {@code CANCELADO}), en lugar de reescribir las
 * reglas en cada endpoint.
 */
public enum EstadoPedido {
    PENDIENTE,
    EN_PREPARACION,
    ENTREGADO,
    CANCELADO;

    /**
     * Destinos permitidos desde cada estado. Los {@code Set} son inmutables: nadie puede ampliar la
     * máquina de estados en tiempo de ejecución desde el código que consulta.
     */
    private static final Map<EstadoPedido, Set<EstadoPedido>> TRANSICIONES = Map.of(
            PENDIENTE, Set.of(EN_PREPARACION, CANCELADO),
            EN_PREPARACION, Set.of(ENTREGADO, CANCELADO),
            ENTREGADO, Set.of(),
            CANCELADO, Set.of());

    /**
     * Conjunto inmutable de estados a los que se puede pasar desde este estado; vacío si el estado
     * es terminal.
     */
    public Set<EstadoPedido> transicionesValidas() {
        return TRANSICIONES.get(this);
    }

    /**
     * Indica si este pedido en {@code this} puede pasar al estado {@code destino}.
     *
     * @param destino estado al que se quiere transicionar
     * @return {@code true} si la transición está permitida; una auto-transición nunca lo es
     */
    public boolean puedeTransicionarA(EstadoPedido destino) {
        return TRANSICIONES.get(this).contains(destino);
    }
}
