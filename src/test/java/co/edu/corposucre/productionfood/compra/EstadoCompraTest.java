package co.edu.corposucre.productionfood.compra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EstadoCompraTest {

    @Test
    @DisplayName("PENDIENTE puede pasar a RECIBIDA y a CANCELADA")
    void pendienteTieneSusDosSalidas() {
        assertThat(EstadoCompra.PENDIENTE.transicionesValidas())
                .containsExactlyInAnyOrder(EstadoCompra.RECIBIDA, EstadoCompra.CANCELADA);
        assertThat(EstadoCompra.PENDIENTE.puedeTransicionarA(EstadoCompra.RECIBIDA)).isTrue();
        assertThat(EstadoCompra.PENDIENTE.puedeTransicionarA(EstadoCompra.CANCELADA)).isTrue();
    }

    @Test
    @DisplayName("PENDIENTE no puede quedarse en PENDIENTE: no hay auto-transición")
    void pendienteNoSeAutoTransiciona() {
        assertThat(EstadoCompra.PENDIENTE.puedeTransicionarA(EstadoCompra.PENDIENTE)).isFalse();
    }

    @Test
    @DisplayName("RECIBIDA es terminal: no admite ninguna transición")
    void recibidaNoAdmiteNada() {
        assertThat(EstadoCompra.RECIBIDA.transicionesValidas()).isEmpty();
        assertThat(EstadoCompra.RECIBIDA.puedeTransicionarA(EstadoCompra.PENDIENTE)).isFalse();
        assertThat(EstadoCompra.RECIBIDA.puedeTransicionarA(EstadoCompra.CANCELADA)).isFalse();
        assertThat(EstadoCompra.RECIBIDA.puedeTransicionarA(EstadoCompra.RECIBIDA)).isFalse();
    }

    @Test
    @DisplayName("CANCELADA es terminal: no admite ninguna transición")
    void canceladaNoAdmiteNada() {
        assertThat(EstadoCompra.CANCELADA.transicionesValidas()).isEmpty();
        assertThat(EstadoCompra.CANCELADA.puedeTransicionarA(EstadoCompra.PENDIENTE)).isFalse();
        assertThat(EstadoCompra.CANCELADA.puedeTransicionarA(EstadoCompra.RECIBIDA)).isFalse();
        assertThat(EstadoCompra.CANCELADA.puedeTransicionarA(EstadoCompra.CANCELADA)).isFalse();
    }

    @Test
    @DisplayName("Las transiciones válidas son inmutables: nadie puede ampliar la máquina")
    void transicionesValidasEsInmutable() {
        for (EstadoCompra estado : EstadoCompra.values()) {
            Set<EstadoCompra> transiciones = estado.transicionesValidas();
            assertThatThrownBy(() -> transiciones.add(EstadoCompra.PENDIENTE))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
