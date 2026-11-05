package co.edu.corposucre.productionfood.produccion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EstadoPlanTest {

    @Test
    @DisplayName("PLANIFICADO puede pasar a EN_PROCESO y a CANCELADO")
    void planificadoTieneSusDosSalidas() {
        assertThat(EstadoPlan.PLANIFICADO.transicionesValidas())
                .containsExactlyInAnyOrder(EstadoPlan.EN_PROCESO, EstadoPlan.CANCELADO);
        assertThat(EstadoPlan.PLANIFICADO.puedeTransicionarA(EstadoPlan.EN_PROCESO)).isTrue();
        assertThat(EstadoPlan.PLANIFICADO.puedeTransicionarA(EstadoPlan.CANCELADO)).isTrue();
    }

    @Test
    @DisplayName("PLANIFICADO no puede completarse ni quedarse igual: sin salto ni auto-transición")
    void planificadoNoSaltaNiSeAutoTransiciona() {
        // CP-18: PLANIFICADO → COMPLETADO es un salto prohibido (R-01).
        assertThat(EstadoPlan.PLANIFICADO.puedeTransicionarA(EstadoPlan.COMPLETADO)).isFalse();
        assertThat(EstadoPlan.PLANIFICADO.puedeTransicionarA(EstadoPlan.PLANIFICADO)).isFalse();
    }

    @Test
    @DisplayName("EN_PROCESO puede pasar a COMPLETADO y a CANCELADO, pero no retroceder")
    void enProcesoTieneSusDosSalidasSinRetroceso() {
        assertThat(EstadoPlan.EN_PROCESO.transicionesValidas())
                .containsExactlyInAnyOrder(EstadoPlan.COMPLETADO, EstadoPlan.CANCELADO);
        assertThat(EstadoPlan.EN_PROCESO.puedeTransicionarA(EstadoPlan.COMPLETADO)).isTrue();
        assertThat(EstadoPlan.EN_PROCESO.puedeTransicionarA(EstadoPlan.CANCELADO)).isTrue();
        assertThat(EstadoPlan.EN_PROCESO.puedeTransicionarA(EstadoPlan.PLANIFICADO)).isFalse();
        assertThat(EstadoPlan.EN_PROCESO.puedeTransicionarA(EstadoPlan.EN_PROCESO)).isFalse();
    }

    @Test
    @DisplayName("COMPLETADO es terminal: no admite ninguna transición")
    void completadoEsTerminal() {
        assertThat(EstadoPlan.COMPLETADO.transicionesValidas()).isEmpty();
        assertThat(EstadoPlan.COMPLETADO.puedeTransicionarA(EstadoPlan.PLANIFICADO)).isFalse();
        assertThat(EstadoPlan.COMPLETADO.puedeTransicionarA(EstadoPlan.EN_PROCESO)).isFalse();
        assertThat(EstadoPlan.COMPLETADO.puedeTransicionarA(EstadoPlan.CANCELADO)).isFalse();
        assertThat(EstadoPlan.COMPLETADO.puedeTransicionarA(EstadoPlan.COMPLETADO)).isFalse();
    }

    @Test
    @DisplayName("CANCELADO es terminal: no admite ninguna transición")
    void canceladoEsTerminal() {
        assertThat(EstadoPlan.CANCELADO.transicionesValidas()).isEmpty();
        assertThat(EstadoPlan.CANCELADO.puedeTransicionarA(EstadoPlan.PLANIFICADO)).isFalse();
        assertThat(EstadoPlan.CANCELADO.puedeTransicionarA(EstadoPlan.EN_PROCESO)).isFalse();
        assertThat(EstadoPlan.CANCELADO.puedeTransicionarA(EstadoPlan.COMPLETADO)).isFalse();
        assertThat(EstadoPlan.CANCELADO.puedeTransicionarA(EstadoPlan.CANCELADO)).isFalse();
    }

    @Test
    @DisplayName("Las transiciones válidas son inmutables: nadie puede ampliar la máquina")
    void transicionesValidasEsInmutable() {
        for (EstadoPlan estado : EstadoPlan.values()) {
            Set<EstadoPlan> transiciones = estado.transicionesValidas();
            assertThatThrownBy(() -> transiciones.add(EstadoPlan.COMPLETADO))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
