package co.edu.corposucre.productionfood.inventario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
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
import org.springframework.dao.DataIntegrityViolationException;

import co.edu.corposucre.productionfood.common.error.ConflictoNegocioException;
import co.edu.corposucre.productionfood.common.error.ParametroInvalidoException;
import co.edu.corposucre.productionfood.common.error.RecursoNoEncontradoException;

class InventarioServiceTest {

    private InventarioRepository inventarioRepository;
    private MovimientoInventarioRepository movimientoRepository;
    private InventarioConsultaRepository inventarioConsultaRepository;
    private KardexConsultaRepository kardexRepository;
    private InventarioService servicio;

    @BeforeEach
    void setUp() {
        inventarioRepository = mock(InventarioRepository.class);
        movimientoRepository = mock(MovimientoInventarioRepository.class);
        inventarioConsultaRepository = mock(InventarioConsultaRepository.class);
        kardexRepository = mock(KardexConsultaRepository.class);
        servicio = new InventarioService(inventarioRepository, movimientoRepository,
                inventarioConsultaRepository, kardexRepository);
    }

    @Test
    @DisplayName("Una entrada de 100 sobre un saldo de 12,50 deja 112,50 y registra el kardex")
    void entradaSumaLaCantidadYRegistraElKardex() {
        when(inventarioRepository.bloquearYObtener(5))
                .thenReturn(Optional.of(inventarioCon(5, "12.50")));

        var saldos = servicio.registrarEntrada(5, new BigDecimal("100.00"),
                TipoMovimiento.ENTRADA_LOTE, "lotes", 14, "Lote H-2026-0912");

        // CP-02: 12,50 + 100 = 112,50.
        assertThat(saldos.anterior()).isEqualByComparingTo("12.50");
        assertThat(saldos.posterior()).isEqualByComparingTo("112.50");
        verify(inventarioRepository).actualizarSaldo(5, new BigDecimal("112.50"));
        // CP-03: el movimiento guarda los saldos con la escala decimal(10,2).
        verify(movimientoRepository).insertar(5, "ENTRADA_LOTE", new BigDecimal("100.00"),
                new BigDecimal("12.50"), new BigDecimal("112.50"), "lotes", 14,
                "Lote H-2026-0912");
    }

    @Test
    @DisplayName("Una salida de 12,50 sobre un saldo de 112,50 deja 100,00 y registra el kardex")
    void salidaRestaLaCantidadYRegistraElKardex() {
        when(inventarioRepository.bloquearYObtener(5))
                .thenReturn(Optional.of(inventarioCon(5, "112.50")));

        var saldos = servicio.registrarSalida(5, new BigDecimal("12.50"),
                TipoMovimiento.SALIDA_PRODUCCION, "planes_produccion", 3, null);

        assertThat(saldos.anterior()).isEqualByComparingTo("112.50");
        assertThat(saldos.posterior()).isEqualByComparingTo("100.00");
        verify(inventarioRepository).actualizarSaldo(5, new BigDecimal("100.00"));
        verify(movimientoRepository).insertar(5, "SALIDA_PRODUCCION",
                new BigDecimal("12.50"), new BigDecimal("112.50"), new BigDecimal("100.00"),
                "planes_produccion", 3, null);
    }

    @Test
    @DisplayName("Una salida de 25 sobre un saldo de 10 responde 409 STOCK_INSUFICIENTE sin escribir")
    void salidaSinSaldoSuficienteNoEscribeNada() {
        when(inventarioRepository.bloquearYObtener(5))
                .thenReturn(Optional.of(inventarioCon(5, "10.00")));

        assertThatThrownBy(() -> servicio.registrarSalida(5, new BigDecimal("25.00"),
                TipoMovimiento.SALIDA_PRODUCCION, "planes_produccion", 3, null))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "STOCK_INSUFICIENTE");

        verify(inventarioRepository).bloquearYObtener(5);
        verify(inventarioRepository, never()).actualizarSaldo(any(), any());
        verifyNoInteractions(movimientoRepository);
    }

    @ParameterizedTest
    @MethodSource("cantidadesInvalidas")
    @DisplayName("Cantidad nula, cero o negativa responde 400 CANTIDAD_INVALIDA sin tocar el inventario")
    void cantidadInvalidaNoTocaElInventario(BigDecimal cantidad) {
        assertThatThrownBy(() -> servicio.registrarEntrada(5, cantidad,
                TipoMovimiento.ENTRADA_LOTE, "lotes", 14, null))
                .isInstanceOf(ParametroInvalidoException.class)
                .hasFieldOrPropertyWithValue("codigo", "CANTIDAD_INVALIDA");
        assertThatThrownBy(() -> servicio.registrarSalida(5, cantidad,
                TipoMovimiento.SALIDA_PRODUCCION, "planes_produccion", 3, null))
                .isInstanceOf(ParametroInvalidoException.class)
                .hasFieldOrPropertyWithValue("codigo", "CANTIDAD_INVALIDA");

        verifyNoInteractions(inventarioRepository, movimientoRepository);
    }

    @Test
    @DisplayName("Una materia prima sin fila de inventario responde 404 RECURSO_NO_ENCONTRADO")
    void inventarioInexistenteResponde404() {
        when(inventarioRepository.bloquearYObtener(9999)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servicio.registrarEntrada(9999, BigDecimal.TEN,
                TipoMovimiento.ENTRADA_LOTE, "lotes", 14, null))
                .isInstanceOf(RecursoNoEncontradoException.class)
                .hasFieldOrPropertyWithValue("codigo", "RECURSO_NO_ENCONTRADO")
                .hasMessage("No se encontró inventario de la materia prima con id 9999.");

        verify(inventarioRepository, never()).actualizarSaldo(any(), any());
        verifyNoInteractions(movimientoRepository);
    }

    @Test
    @DisplayName("El orden es bloqueo, actualización del saldo y movimiento de kardex")
    void bloqueaActualizaYRegistraEnEseOrden() {
        when(inventarioRepository.bloquearYObtener(5))
                .thenReturn(Optional.of(inventarioCon(5, "12.50")));

        servicio.registrarEntrada(5, new BigDecimal("100.00"),
                TipoMovimiento.ENTRADA_LOTE, "lotes", 14, null);

        var orden = inOrder(inventarioRepository, movimientoRepository);
        orden.verify(inventarioRepository).bloquearYObtener(5);
        orden.verify(inventarioRepository).actualizarSaldo(5, new BigDecimal("112.50"));
        orden.verify(movimientoRepository).insertar(5, "ENTRADA_LOTE",
                new BigDecimal("100.00"), new BigDecimal("12.50"), new BigDecimal("112.50"),
                "lotes", 14, null);
    }

    @Test
    @DisplayName("Si falla el kardex la excepción se propaga para que revierta el llamador")
    void falloDelKardexSePropagaParaRevertirLaTransaccion() {
        when(inventarioRepository.bloquearYObtener(5))
                .thenReturn(Optional.of(inventarioCon(5, "12.50")));
        doThrow(new DataIntegrityViolationException("kardex caído"))
                .when(movimientoRepository).insertar(any(), any(), any(), any(), any(), any(),
                        any(), any());

        assertThatThrownBy(() -> servicio.registrarEntrada(5, new BigDecimal("100.00"),
                TipoMovimiento.ENTRADA_LOTE, "lotes", 14, null))
                .isInstanceOf(DataIntegrityViolationException.class);

        // El servicio no captura el fallo: el UPDATE ya se ejecutó y es la transacción del
        // llamador la que lo revierte junto con el kardex (CP-18).
        verify(inventarioRepository).actualizarSaldo(5, new BigDecimal("112.50"));
    }

    private static Stream<BigDecimal> cantidadesInvalidas() {
        return Stream.of(new BigDecimal[] {null, BigDecimal.ZERO, new BigDecimal("-10.00")});
    }

    private static Inventario inventarioCon(int idMateriaPrima, String cantidadDisponible) {
        var inventario = new Inventario();
        inventario.setIdMateriaPrima(idMateriaPrima);
        inventario.setCantidadDisponible(new BigDecimal(cantidadDisponible));
        return inventario;
    }
}
