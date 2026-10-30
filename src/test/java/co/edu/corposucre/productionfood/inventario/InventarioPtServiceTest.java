package co.edu.corposucre.productionfood.inventario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;

import co.edu.corposucre.productionfood.common.error.ConflictoNegocioException;
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

    @Test
    @DisplayName("stockDisponible suma los lotes vendibles y responde 0 cuando no hay ninguno")
    void stockDisponibleSumaLosLotes() {
        when(inventarioPtRepository.lotesDisponiblesFefo(3)).thenReturn(List.of(
                lote(10, "PF-0919", "2026-09-19", "12.00"),
                lote(11, "PF-0921", "2026-09-21", "40.50")));
        when(inventarioPtRepository.lotesDisponiblesFefo(4)).thenReturn(List.of());

        assertThat(servicio.stockDisponible(3)).isEqualByComparingTo("52.50");
        assertThat(servicio.stockDisponible(3).scale()).isEqualTo(2);
        assertThat(servicio.stockDisponible(4)).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("La salida FEFO de un solo lote descuenta y registra SALIDA_VENTA")
    void salidaDeUnSoloLoteDescuentaYRegistraElKardex() {
        when(inventarioPtRepository.lotesDisponiblesFefo(3))
                .thenReturn(List.of(lote(10, "PF-0919", "2026-09-19", "12.00")));
        when(inventarioPtRepository.bloquearSaldos(List.of(10)))
                .thenReturn(List.of(saldo(10, "12.00")));

        var consumidos = servicio.registrarSalidaFefo(3, new BigDecimal("5.00"),
                "detalle_pedido", 88);

        assertThat(consumidos).containsExactly(
                consumido(10, "PF-0919", "2026-09-19", "5.00"));
        verify(inventarioPtRepository).actualizarSaldo(10, new BigDecimal("7.00"));
        verify(inventarioPtRepository).registrarMovimiento(10,
                TipoMovimientoPt.SALIDA_VENTA, new BigDecimal("5.00"),
                new BigDecimal("12.00"), new BigDecimal("7.00"), "detalle_pedido", 88,
                "Salida FEFO desde detalle_pedido #88");
    }

    @Test
    @DisplayName("R-02: la salida consume varios lotes en orden FEFO y no toca el más nuevo")
    void salidaConsumeVariosLotesEnOrdenFefo() {
        when(inventarioPtRepository.lotesDisponiblesFefo(3)).thenReturn(List.of(
                lote(10, "PF-0919", "2026-09-19", "12.00"),
                lote(11, "PF-0921", "2026-09-21", "40.00"),
                lote(12, "PF-0922", "2026-09-22", "25.00")));
        when(inventarioPtRepository.bloquearSaldos(List.of(10, 11, 12))).thenReturn(List.of(
                saldo(10, "12.00"),
                saldo(11, "40.00"),
                saldo(12, "25.00")));

        var consumidos = servicio.registrarSalidaFefo(3, new BigDecimal("30.00"),
                "detalle_pedido", 88);

        assertThat(consumidos).containsExactly(
                consumido(10, "PF-0919", "2026-09-19", "12.00"),
                consumido(11, "PF-0921", "2026-09-21", "18.00"));
        var orden = inOrder(inventarioPtRepository);
        orden.verify(inventarioPtRepository).actualizarSaldo(10, new BigDecimal("0.00"));
        orden.verify(inventarioPtRepository).actualizarSaldo(11, new BigDecimal("22.00"));
        verify(inventarioPtRepository, never()).actualizarSaldo(eq(12), any());
        verify(inventarioPtRepository, never()).registrarMovimiento(eq(12), any(), any(), any(),
                any(), any(), any(), any());
    }

    @Test
    @DisplayName("CP-06: la salida deja el lote exacto en 0 sin negativos")
    void salidaExactaDejaElSaldoEnCero() {
        when(inventarioPtRepository.lotesDisponiblesFefo(3))
                .thenReturn(List.of(lote(10, "PF-0919", "2026-09-19", "77.00")));
        when(inventarioPtRepository.bloquearSaldos(List.of(10)))
                .thenReturn(List.of(saldo(10, "77.00")));

        var consumidos = servicio.registrarSalidaFefo(3, new BigDecimal("77.00"),
                "detalle_pedido", 88);

        assertThat(consumidos).containsExactly(
                consumido(10, "PF-0919", "2026-09-19", "77.00"));
        verify(inventarioPtRepository).actualizarSaldo(10, new BigDecimal("0.00"));
        verify(inventarioPtRepository).registrarMovimiento(10,
                TipoMovimientoPt.SALIDA_VENTA, new BigDecimal("77.00"),
                new BigDecimal("77.00"), new BigDecimal("0.00"), "detalle_pedido", 88,
                "Salida FEFO desde detalle_pedido #88");
    }

    @Test
    @DisplayName("Un producto sin lotes vendibles responde STOCK_INSUFICIENTE sin bloquear nada")
    void sinLotesVendiblesRespondeStockInsuficiente() {
        when(inventarioPtRepository.lotesDisponiblesFefo(3)).thenReturn(List.of());

        assertThatThrownBy(() -> servicio.registrarSalidaFefo(3, new BigDecimal("10.00"),
                "detalle_pedido", 88))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "STOCK_INSUFICIENTE")
                .hasFieldOrPropertyWithValue("status", HttpStatus.CONFLICT)
                .hasMessage("Solo hay 0.00 unidades disponibles del producto 3. "
                        + "Se solicitaron 10.00.");

        verify(inventarioPtRepository, never()).bloquearSaldos(any());
        verifyNoSalidas();
    }

    @Test
    @DisplayName("R-05: el total bloqueado manda sobre la lectura previa y evita sobrevender")
    void salidaRevalidaElStockConLosSaldosBloqueados() {
        // La vista decía 77, pero al bloquear otra venta ya consumió: la autoridad es el bloqueo.
        when(inventarioPtRepository.lotesDisponiblesFefo(3)).thenReturn(List.of(
                lote(10, "PF-0919", "2026-09-19", "12.00"),
                lote(11, "PF-0921", "2026-09-21", "40.00"),
                lote(12, "PF-0922", "2026-09-22", "25.00")));
        when(inventarioPtRepository.bloquearSaldos(List.of(10, 11, 12))).thenReturn(List.of(
                saldo(10, "5.00"),
                saldo(11, "40.00"),
                saldo(12, "25.00")));

        assertThatThrownBy(() -> servicio.registrarSalidaFefo(3, new BigDecimal("77.00"),
                "detalle_pedido", 88))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "STOCK_INSUFICIENTE")
                .hasMessage("Solo hay 70.00 unidades disponibles del producto 3. "
                        + "Se solicitaron 77.00.");

        verifyNoSalidas();
    }

    @Test
    @DisplayName("Un lote que desaparece al bloquear se salta y se consume del siguiente")
    void loteQueDesapareceAlBloquearSeSalta() {
        when(inventarioPtRepository.lotesDisponiblesFefo(3)).thenReturn(List.of(
                lote(10, "PF-0919", "2026-09-19", "12.00"),
                lote(11, "PF-0921", "2026-09-21", "40.00")));
        // El lote 10 ya no tiene fila de inventario: otra transacción lo consumió.
        when(inventarioPtRepository.bloquearSaldos(List.of(10, 11)))
                .thenReturn(List.of(saldo(11, "40.00")));

        var consumidos = servicio.registrarSalidaFefo(3, new BigDecimal("30.00"),
                "detalle_pedido", 88);

        assertThat(consumidos).containsExactly(
                consumido(11, "PF-0921", "2026-09-21", "30.00"));
        verify(inventarioPtRepository, never()).actualizarSaldo(eq(10), any());
        verify(inventarioPtRepository, never()).registrarMovimiento(eq(10), any(), any(), any(),
                any(), any(), any(), any());
        verify(inventarioPtRepository).actualizarSaldo(11, new BigDecimal("10.00"));
    }

    @ParameterizedTest
    @MethodSource("cantidadesInvalidas")
    @DisplayName("La salida FEFO con cantidad nula, cero o negativa responde CANTIDAD_INVALIDA")
    void salidaFefoConCantidadInvalidaNoTocaElInventario(BigDecimal cantidad) {
        assertThatThrownBy(() -> servicio.registrarSalidaFefo(3, cantidad,
                "detalle_pedido", 88))
                .isInstanceOf(ParametroInvalidoException.class)
                .hasFieldOrPropertyWithValue("codigo", "CANTIDAD_INVALIDA");

        verifyNoInteractions(inventarioPtRepository);
    }

    @Test
    @DisplayName("R-07/R-08: la devolución reparte en orden inverso al FEFO con ENTRADA_DEVOLUCION")
    void devolucionReparteEnOrdenInversoAlFefo() {
        when(inventarioPtRepository.movimientosNetosDeOrigen("detalle_pedido", 88))
                .thenReturn(List.of(
                        consumido(10, "PF-0919", "2026-09-19", "4.00"),
                        consumido(11, "PF-0921", "2026-09-21", "6.00")));
        when(inventarioPtRepository.bloquearYObtener(11))
                .thenReturn(Optional.of(saldo(11, "33.00")));
        when(inventarioPtRepository.bloquearYObtener(10))
                .thenReturn(Optional.of(saldo(10, "2.00")));

        servicio.registrarDevolucion("detalle_pedido", 88, new BigDecimal("8.00"));

        var orden = inOrder(inventarioPtRepository);
        // El que vence después (11) se repone primero: 6 de 8.
        orden.verify(inventarioPtRepository).bloquearYObtener(11);
        orden.verify(inventarioPtRepository).actualizarSaldo(11, new BigDecimal("39.00"));
        orden.verify(inventarioPtRepository).registrarMovimiento(11,
                TipoMovimientoPt.ENTRADA_DEVOLUCION, new BigDecimal("6.00"),
                new BigDecimal("33.00"), new BigDecimal("39.00"), "detalle_pedido", 88,
                "Devolución a detalle_pedido #88");
        // Los 2 restantes vuelven al que vence antes (10).
        orden.verify(inventarioPtRepository).bloquearYObtener(10);
        orden.verify(inventarioPtRepository).actualizarSaldo(10, new BigDecimal("4.00"));
        orden.verify(inventarioPtRepository).registrarMovimiento(10,
                TipoMovimientoPt.ENTRADA_DEVOLUCION, new BigDecimal("2.00"),
                new BigDecimal("2.00"), new BigDecimal("4.00"), "detalle_pedido", 88,
                "Devolución a detalle_pedido #88");
    }

    @Test
    @DisplayName("Si el kardex no tiene neto suficiente, la devolución aborta con IllegalStateException")
    void devolucionSinNetoSuficienteEsInconsistenciaDeKardex() {
        when(inventarioPtRepository.movimientosNetosDeOrigen("detalle_pedido", 88))
                .thenReturn(List.of(consumido(10, "PF-0919", "2026-09-19", "3.00")));

        assertThatThrownBy(() -> servicio.registrarDevolucion("detalle_pedido", 88,
                new BigDecimal("5.00")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("El kardex de detalle_pedido #88 solo respalda 3.00 unidades; "
                        + "se intentaron devolver 5.00.");

        verify(inventarioPtRepository, never()).bloquearYObtener(any());
        verifyNoSalidas();
    }

    @ParameterizedTest
    @MethodSource("cantidadesInvalidas")
    @DisplayName("La devolución con cantidad nula, cero o negativa responde CANTIDAD_INVALIDA")
    void devolucionConCantidadInvalidaNoTocaElInventario(BigDecimal cantidad) {
        assertThatThrownBy(() -> servicio.registrarDevolucion("detalle_pedido", 88, cantidad))
                .isInstanceOf(ParametroInvalidoException.class)
                .hasFieldOrPropertyWithValue("codigo", "CANTIDAD_INVALIDA");

        verifyNoInteractions(inventarioPtRepository);
    }

    private void verifyNoSalidas() {
        verify(inventarioPtRepository, never()).actualizarSaldo(any(), any());
        verify(inventarioPtRepository, never()).registrarMovimiento(any(), any(), any(), any(),
                any(), any(), any(), any());
    }

    private static LoteDisponible lote(int id, String codigo, String vence, String saldo) {
        return new LoteDisponible(id, codigo, LocalDate.parse(vence), new BigDecimal(saldo));
    }

    private static LoteConsumido consumido(int id, String codigo, String vence, String cantidad) {
        return new LoteConsumido(id, codigo, LocalDate.parse(vence), new BigDecimal(cantidad));
    }

    private static SaldoLote saldo(int id, String cantidad) {
        return new SaldoLote(id, new BigDecimal(cantidad));
    }

    private static Stream<BigDecimal> cantidadesInvalidas() {
        return Stream.of(new BigDecimal[] {null, BigDecimal.ZERO, new BigDecimal("-10.00")});
    }
}
