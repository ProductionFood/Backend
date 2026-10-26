package co.edu.corposucre.productionfood.compra;

import java.util.Map;
import java.util.Set;

/**
 * Estado de una compra y su máquina de transiciones (HU-13, R-02).
 *
 * <p>Los valores coinciden exactamente con el {@code CHECK ck_compras_estado} que V2 (§C-12)
 * añadió a {@code compras}. Añadir un valor aquí sin actualizar la migración hace que el
 * {@code INSERT} falle en la base, no en la compilación.
 *
 * <p>La máquina de estados vive solo aquí: {@code PENDIENTE} puede recibir su materia prima
 * ({@code RECIBIDA}) o cancelarse ({@code CANCELADA}), y una vez en {@code RECIBIDA} o
 * {@code CANCELADA} no admite ninguna transición más. El servicio de HU-13 delega en
 * {@link #puedeTransicionarA(EstadoCompra)} para rechazar con un 409 cualquier salto prohibido
 * ({@code RECIBIDA} a {@code PENDIENTE} o a {@code CANCELADA}), en lugar de reescribir las reglas
 * en cada endpoint.
 */
public enum EstadoCompra {
    PENDIENTE,
    RECIBIDA,
    CANCELADA;

    /**
     * Destinos permitidos desde cada estado. Los {@code Set} son inmutables: nadie puede ampliar la
     * máquina de estados en tiempo de ejecución desde el código que consulta.
     */
    private static final Map<EstadoCompra, Set<EstadoCompra>> TRANSICIONES = Map.of(
            PENDIENTE, Set.of(RECIBIDA, CANCELADA),
            RECIBIDA, Set.of(),
            CANCELADA, Set.of());

    /**
     * Conjunto inmutable de estados a los que se puede pasar desde este estado; vacío si el estado
     * es terminal.
     */
    public Set<EstadoCompra> transicionesValidas() {
        return TRANSICIONES.get(this);
    }

    /**
     * Indica si esta compra en {@code this} puede pasar al estado {@code destino}.
     *
     * @param destino estado al que se quiere transicionar
     * @return {@code true} si la transición está permitida; una auto-transición nunca lo es
     */
    public boolean puedeTransicionarA(EstadoCompra destino) {
        return TRANSICIONES.get(this).contains(destino);
    }
}
