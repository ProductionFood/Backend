package co.edu.corposucre.productionfood.inventario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import co.edu.corposucre.productionfood.common.error.ParametroInvalidoException;
import co.edu.corposucre.productionfood.common.error.RecursoNoEncontradoException;

class InventarioPtServiceTest {

    private InventarioPtRepository inventarioPtRepository;
    private InventarioPtService servicio;

    @BeforeEach
    void setUp() {
        inventarioPtRepository = mock(InventarioPtRepository.class);
        servicio = new InventarioPtService(inventarioPtRepository);
    }

    @Test
    @DisplayName("Una entrada de 100 sobre un saldo de 12,50 deja 112,50 y registra el kardex")
    void entradaSumaLaCantidadYRegistraElKardex() {
        when(inventarioPtRepository.bloquearYObtener(5))
                .thenReturn(Optional.of(new SaldoLote(5, new BigDecimal("12.50"))));

        var saldos = servicio.registrarEntrada(5, new BigDecimal("100.00"),
                TipoMovimientoPt.ENTRADA_DEVOLUCION, "pedidos", 14,
                "Devolucion por cancelacion del pedido 14");

        assertThat(saldos.anterior()).isEqualByComparingTo("12.50");
        assertThat(saldos.posterior()).isEqualByComparingTo("112.50");
        verify(inventarioPtRepository).actualizarSaldo(5, new BigDecimal("112.50"));
        verify(inventarioPtRepository).registrarMovimiento(5,
                TipoMovimientoPt.ENTRADA_DEVOLUCION, new BigDecimal("100.00"),
                new BigDecimal("12.50"), new BigDecimal("112.50"), "pedidos", 14,
                "Devolucion por cancelacion del pedido 14");
    }

    @ParameterizedTest
    @MethodSource("cantidadesInvalidas")
    @DisplayName("Cantidad nula, cero o negativa responde 400 CANTIDAD_INVALIDA sin tocar el lote")
    void cantidadInvalidaNoTocaElInventario(BigDecimal cantidad) {
        assertThatThrownBy(() -> servicio.registrarEntrada(5, cantidad,
                TipoMovimientoPt.ENTRADA_DEVOLUCION, "pedidos", 14, null))
                .isInstanceOf(ParametroInvalidoException.class)
                .hasFieldOrPropertyWithValue("codigo", "CANTIDAD_INVALIDA");

        verifyNoInteractions(inventarioPtRepository);
    }

    @Test
    @DisplayName("Un lote sin fila de inventario responde 404 RECURSO_NO_ENCONTRADO")
    void loteSinInventarioResponde404() {
        when(inventarioPtRepository.bloquearYObtener(9999)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servicio.registrarEntrada(9999, BigDecimal.TEN,
                TipoMovimientoPt.ENTRADA_DEVOLUCION, "pedidos", 14, null))
                .isInstanceOf(RecursoNoEncontradoException.class)
                .hasFieldOrPropertyWithValue("codigo", "RECURSO_NO_ENCONTRADO")
                .hasMessage("No se encontró el inventario del lote con id 9999.");

        verify(inventarioPtRepository, never()).actualizarSaldo(any(), any());
        verify(inventarioPtRepository, never()).registrarMovimiento(any(), any(), any(), any(),
                any(), any(), any(), any());
    }

    @Test
    @DisplayName("El orden es bloqueo, actualización del saldo y movimiento de kardex")
    void bloqueaActualizaYRegistraEnEseOrden() {
        when(inventarioPtRepository.bloquearYObtener(5))
                .thenReturn(Optional.of(new SaldoLote(5, new BigDecimal("12.50"))));

        servicio.registrarEntrada(5, new BigDecimal("100.00"),
                TipoMovimientoPt.ENTRADA_DEVOLUCION, "pedidos", 14, null);

        var orden = inOrder(inventarioPtRepository);
        orden.verify(inventarioPtRepository).bloquearYObtener(5);
        orden.verify(inventarioPtRepository).actualizarSaldo(5, new BigDecimal("112.50"));
        orden.verify(inventarioPtRepository).registrarMovimiento(5,
                TipoMovimientoPt.ENTRADA_DEVOLUCION, new BigDecimal("100.00"),
                new BigDecimal("12.50"), new BigDecimal("112.50"), "pedidos", 14, null);
    }

    private static Stream<BigDecimal> cantidadesInvalidas() {
        return Stream.of(new BigDecimal[] {null, BigDecimal.ZERO, new BigDecimal("-10.00")});
    }
}
