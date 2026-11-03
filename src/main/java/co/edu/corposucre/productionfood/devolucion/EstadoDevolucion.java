package co.edu.corposucre.productionfood.devolucion;

import java.util.Map;
import java.util.Set;

/**
 * Estado de una devolución y su máquina de transiciones (HU-18, R-04).
 *
 * <p>Los valores coinciden exactamente con el {@code CHECK ck_devoluciones_estado} que V2
 * (§C-12) añadió a {@code devoluciones}. Añadir un valor aquí sin actualizar la migración hace
 * que el {@code INSERT} falle en la base, no en la compilación.
 *
 * <p>La máquina de estados vive solo aquí: {@code PENDIENTE} se aprueba ({@code APROBADA}),
 * que repone el stock (R-01), o se rechaza ({@code RECHAZADA}); una vez en {@code APROBADA} o
 * {@code RECHAZADA} no admite ninguna transición más, porque ambos son estados terminales
 * (R-04): una devolución aprobada por error se corrige con un ajuste de inventario, no
 * revirtiendo el estado, mismo criterio que la recepción de compra (HU-13 R-02). El servicio de
 * HU-18 delega en {@link #puedeTransicionarA(EstadoDevolucion)} para rechazar con un 409
 * cualquier salto prohibido, en lugar de reescribir las reglas en cada endpoint.
 */
public enum EstadoDevolucion {
    PENDIENTE,
    APROBADA,
    RECHAZADA;

    /**
     * Destinos permitidos desde cada estado. Los {@code Set} son inmutables: nadie puede ampliar
     * la máquina de estados en tiempo de ejecución desde el código que consulta.
     */
    private static final Map<EstadoDevolucion, Set<EstadoDevolucion>> TRANSICIONES = Map.of(
            PENDIENTE, Set.of(APROBADA, RECHAZADA),
            APROBADA, Set.of(),
            RECHAZADA, Set.of());

    /**
     * Conjunto inmutable de estados a los que se puede pasar desde este estado; vacío si el
     * estado es terminal.
     */
    public Set<EstadoDevolucion> transicionesValidas() {
        return TRANSICIONES.get(this);
    }

    /**
     * Indica si esta devolución en {@code this} puede pasar al estado {@code destino}.
     *
     * @param destino estado al que se quiere transicionar
     * @return {@code true} si la transición está permitida; una auto-transición nunca lo es
     */
    public boolean puedeTransicionarA(EstadoDevolucion destino) {
        return TRANSICIONES.get(this).contains(destino);
    }
}
