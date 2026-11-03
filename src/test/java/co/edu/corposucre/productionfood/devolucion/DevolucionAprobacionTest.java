package co.edu.corposucre.productionfood.devolucion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import co.edu.corposucre.productionfood.common.error.ConflictoNegocioException;
import co.edu.corposucre.productionfood.common.error.RecursoNoEncontradoException;
import co.edu.corposucre.productionfood.devolucion.dto.AprobacionResponse;
import co.edu.corposucre.productionfood.devolucion.dto.CambiarEstadoDevolucionRequest;
import co.edu.corposucre.productionfood.inventario.InventarioPtRepository;
import co.edu.corposucre.productionfood.inventario.InventarioPtService;
import co.edu.corposucre.productionfood.inventario.LoteConsumido;
import co.edu.corposucre.productionfood.inventario.SaldosInventario;
import co.edu.corposucre.productionfood.inventario.TipoMovimientoPt;
import co.edu.corposucre.productionfood.pedido.DetallePedidoRepository;
import co.edu.corposucre.productionfood.pedido.PedidoRepository;

/**
 * Pruebas del cambio de estado de una devolución con Mockito (HU-18, R-01, R-02, R-04).
 *
 * <p>Cubren el flujo de {@code cambiarEstado}: la aprobación repone la mercancía en los lotes
 * del kardex en orden inverso al consumo, el rechazo no toca inventario, las transiciones
 * prohibidas y la carrera del {@code UPDATE} condicionado responden 409 sin reponer nada
 * (CP-20, CP-21) y las fallas del kardex revierten la aprobación (CP-24). El alta y la
 * consulta están en {@link DevolucionServiceTest}.
 */
class DevolucionAprobacionTest {

    private static final Integer ID_DETALLE = 88;
    private static final Integer ID_DEVOLUCION = 15;

    private DevolucionRepository devolucionRepository;
    private InventarioPtRepository inventarioPtRepository;
    private InventarioPtService inventarioPtService;
    private DevolucionService service;

    @BeforeEach
    void setUp() {
        devolucionRepository = mock(DevolucionRepository.class);
        inventarioPtRepository = mock(InventarioPtRepository.class);
        inventarioPtService = mock(InventarioPtService.class);
        service = new DevolucionService(devolucionRepository,
                mock(DetallePedidoRepository.class), mock(PedidoRepository.class),
                inventarioPtRepository, inventarioPtService);
    }

    // --------------------------------------------------------------- aprobar

    @Test
    @DisplayName("R-01/R-02: aprobar repone en cada lote del kardex, en orden inverso al consumo")
    void aprobarReponeEnCadaLoteDelKardexEnOrdenInverso() {
        dadoCambioDe(EstadoDevolucion.PENDIENTE, EstadoDevolucion.APROBADA, 1);
        // El kardex llega en id_movimiento DESC: primero el último lote consumido (R-02).
        cuandoSeLeaElKardexDeLaLinea(
                lote(21, "PF-2026-0919", "2.00"),
                lote(22, "PF-2026-0917", "1.00"));
        cuandoReponga(21, "10.00", "12.00");
        cuandoReponga(22, "5.00", "6.00");

        var r = service.cambiarEstado(ID_DEVOLUCION,
                new CambiarEstadoDevolucionRequest(EstadoDevolucion.APROBADA));

        assertThat(r.idDevolucion()).isEqualTo(ID_DEVOLUCION);
        assertThat(r.estado()).isEqualTo("APROBADA");
        assertThat(r.lotesRepuestos()).containsExactly(
                new AprobacionResponse.LoteRepuesto("PF-2026-0919", new BigDecimal("2.00"),
                        new BigDecimal("10.00"), new BigDecimal("12.00")),
                new AprobacionResponse.LoteRepuesto("PF-2026-0917", new BigDecimal("1.00"),
                        new BigDecimal("5.00"), new BigDecimal("6.00")));

        // Una entrada por lote, con el tipo y el origen de CP-18, en el orden del kardex.
        var ids = ArgumentCaptor.forClass(Integer.class);
        var cantidades = ArgumentCaptor.forClass(BigDecimal.class);
        verify(inventarioPtService, times(2)).registrarEntrada(ids.capture(),
                cantidades.capture(), eq(TipoMovimientoPt.ENTRADA_DEVOLUCION),
                eq("devoluciones"), eq(ID_DEVOLUCION), anyString());
        assertThat(ids.getAllValues()).containsExactly(21, 22);
        assertThat(cantidades.getAllValues())
                .extracting(BigDecimal::toPlainString)
                .containsExactly("2.00", "1.00");
        verify(devolucionRepository).cambiarEstado(ID_DEVOLUCION, EstadoDevolucion.PENDIENTE,
                EstadoDevolucion.APROBADA);
    }

    @Test
    @DisplayName("R-02: varias salidas del mismo lote se agrupan en una sola entrada")
    void aprobarAgrupaLasSalidasDelMismoLote() {
        dadoCambioDe(EstadoDevolucion.PENDIENTE, EstadoDevolucion.APROBADA, 1);
        cuandoSeLeaElKardexDeLaLinea(
                lote(21, "PF-2026-0919", "1.00"),
                lote(22, "PF-2026-0917", "0.50"),
                lote(21, "PF-2026-0919", "1.50"));
        cuandoReponga(21, "10.00", "12.50");
        cuandoReponga(22, "5.00", "5.50");

        var r = service.cambiarEstado(ID_DEVOLUCION,
                new CambiarEstadoDevolucionRequest(EstadoDevolucion.APROBADA));

        // El lote 21 conserva la posición de su primera aparición y suma sus dos salidas.
        var ids = ArgumentCaptor.forClass(Integer.class);
        var cantidades = ArgumentCaptor.forClass(BigDecimal.class);
        verify(inventarioPtService, times(2)).registrarEntrada(ids.capture(),
                cantidades.capture(), eq(TipoMovimientoPt.ENTRADA_DEVOLUCION),
                eq("devoluciones"), eq(ID_DEVOLUCION), anyString());
        assertThat(ids.getAllValues()).containsExactly(21, 22);
        assertThat(cantidades.getAllValues())
                .extracting(BigDecimal::toPlainString)
                .containsExactly("2.50", "0.50");
        assertThat(r.lotesRepuestos())
                .extracting(AprobacionResponse.LoteRepuesto::codigoLote)
                .containsExactly("PF-2026-0919", "PF-2026-0917");
    }

    @Test
    @DisplayName("R-01: rechazar responde con el estado y no toca inventario ni kardex")
    void rechazarNoTocaInventario() {
        dadoCambioDe(EstadoDevolucion.PENDIENTE, EstadoDevolucion.RECHAZADA, 1);

        var r = service.cambiarEstado(ID_DEVOLUCION,
                new CambiarEstadoDevolucionRequest(EstadoDevolucion.RECHAZADA));

        assertThat(r.idDevolucion()).isEqualTo(ID_DEVOLUCION);
        assertThat(r.estado()).isEqualTo("RECHAZADA");
        assertThat(r.lotesRepuestos()).isEmpty();
        verify(devolucionRepository).cambiarEstado(ID_DEVOLUCION, EstadoDevolucion.PENDIENTE,
                EstadoDevolucion.RECHAZADA);
        // Un rechazo no repone: ni se lee el kardex ni se entra al inventario.
        verifyNoInteractions(inventarioPtRepository, inventarioPtService);
    }

    // --------------------------------------------------- transiciones prohibidas

    @Test
    @DisplayName("CP-20: aprobar una devolución ya aprobada responde 409 sin reponer stock")
    void aprobarUnaYaAprobadaResponde409() {
        cuandoBusque(ID_DEVOLUCION, devolucion(ID_DEVOLUCION, EstadoDevolucion.APROBADA));

        assertThatThrownBy(() -> service.cambiarEstado(ID_DEVOLUCION,
                new CambiarEstadoDevolucionRequest(EstadoDevolucion.APROBADA)))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "TRANSICION_INVALIDA")
                .hasFieldOrPropertyWithValue("status", HttpStatus.CONFLICT);

        verify(devolucionRepository, never()).cambiarEstado(any(), any(), any());
        verifyNoInteractions(inventarioPtRepository, inventarioPtService);
    }

    @Test
    @DisplayName("CP-21: una devolución aprobada no vuelve a PENDIENTE y no toca inventario")
    void aprobarNoSeVuelveAPendiente() {
        cuandoBusque(ID_DEVOLUCION, devolucion(ID_DEVOLUCION, EstadoDevolucion.APROBADA));

        assertThatThrownBy(() -> service.cambiarEstado(ID_DEVOLUCION,
                new CambiarEstadoDevolucionRequest(EstadoDevolucion.PENDIENTE)))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "TRANSICION_INVALIDA")
                .hasFieldOrPropertyWithValue("status", HttpStatus.CONFLICT);

        verify(devolucionRepository, never()).cambiarEstado(any(), any(), any());
        verifyNoInteractions(inventarioPtRepository, inventarioPtService);
    }

    @Test
    @DisplayName("carrera: si el UPDATE condicional afecta 0 filas responde 409 sin reponer")
    void perderLaCarreraResponde409SinTocarInventario() {
        cuandoBusque(ID_DEVOLUCION, devolucion(ID_DEVOLUCION, EstadoDevolucion.PENDIENTE));
        when(devolucionRepository.cambiarEstado(ID_DEVOLUCION, EstadoDevolucion.PENDIENTE,
                EstadoDevolucion.APROBADA)).thenReturn(0);

        assertThatThrownBy(() -> service.cambiarEstado(ID_DEVOLUCION,
                new CambiarEstadoDevolucionRequest(EstadoDevolucion.APROBADA)))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "TRANSICION_INVALIDA")
                .hasFieldOrPropertyWithValue("status", HttpStatus.CONFLICT);

        // La otra transacción ganó: no se lee el kardex ni se repone una segunda vez.
        verifyNoInteractions(inventarioPtRepository, inventarioPtService);
    }

    @Test
    @DisplayName("cambiarEstado de una devolución inexistente responde 404")
    void cambiarEstadoInexistenteResponde404() {
        cuandoBusque(9999, null);

        assertThatThrownBy(() -> service.cambiarEstado(9999,
                new CambiarEstadoDevolucionRequest(EstadoDevolucion.APROBADA)))
                .isInstanceOf(RecursoNoEncontradoException.class)
                .hasMessage("No se encontró la devolución con id 9999.");

        verify(devolucionRepository, never()).cambiarEstado(any(), any(), any());
        verifyNoInteractions(inventarioPtRepository, inventarioPtService);
    }

    // ------------------------------------------------------- kardex insuficiente

    @Test
    @DisplayName("CP-24: un kardex sin movimientos revierte la aprobación sin tocar inventario")
    void aprobarSinMovimientosEnElKardexFalla() {
        dadoCambioDe(EstadoDevolucion.PENDIENTE, EstadoDevolucion.APROBADA, 1);
        cuandoSeLeaElKardexDeLaLinea();

        assertThatThrownBy(() -> service.cambiarEstado(ID_DEVOLUCION,
                new CambiarEstadoDevolucionRequest(EstadoDevolucion.APROBADA)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("solo respalda 0 unidades");

        verifyNoInteractions(inventarioPtService);
    }

    @Test
    @DisplayName("CP-24: un kardex que no respalda la cantidad revierte antes de reponer")
    void aprobarConKardexInsuficienteFallaAntesDeReponer() {
        dadoCambioDe(EstadoDevolucion.PENDIENTE, EstadoDevolucion.APROBADA, 1);
        cuandoSeLeaElKardexDeLaLinea(lote(21, "PF-2026-0919", "2.00"));

        assertThatThrownBy(() -> service.cambiarEstado(ID_DEVOLUCION,
                new CambiarEstadoDevolucionRequest(EstadoDevolucion.APROBADA)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("solo respalda 2 unidades");

        verifyNoInteractions(inventarioPtService);
    }

    @Test
    @DisplayName("CP-24: si registrarEntrada falla, la excepción se propaga sin tragársela")
    void falloAlReponerSePropaga() {
        dadoCambioDe(EstadoDevolucion.PENDIENTE, EstadoDevolucion.APROBADA, 1);
        cuandoSeLeaElKardexDeLaLinea(lote(21, "PF-2026-0919", "3.00"));
        when(inventarioPtService.registrarEntrada(any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("fallo de kardex"));

        // Sin capturar: la transacción revierte el estado junto con las entradas (CP-24).
        assertThatThrownBy(() -> service.cambiarEstado(ID_DEVOLUCION,
                new CambiarEstadoDevolucionRequest(EstadoDevolucion.APROBADA)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("fallo de kardex");

        // Nunca se llega a la relectura que montaría la respuesta aprobada.
        verify(devolucionRepository, times(1)).buscarPorId(ID_DEVOLUCION);
    }

    // ----------------------------------------------------------------- datos

    /**
     * Deja la primera lectura de {@code buscarPorId} en el estado dado y encadena la relectura
     * posterior al {@code UPDATE}, con la que el servicio monta la respuesta.
     */
    private void dadoCambioDe(EstadoDevolucion antes, EstadoDevolucion despues, int filas) {
        when(devolucionRepository.buscarPorId(ID_DEVOLUCION)).thenReturn(
                Optional.of(devolucion(ID_DEVOLUCION, antes)),
                Optional.of(devolucion(ID_DEVOLUCION, despues)));
        when(devolucionRepository.cambiarEstado(ID_DEVOLUCION, antes, despues))
                .thenReturn(filas);
    }

    private void cuandoBusque(Integer id, Devolucion devolucion) {
        when(devolucionRepository.buscarPorId(id)).thenReturn(Optional.ofNullable(devolucion));
    }

    /**
     * Fija la respuesta del kardex de la línea, tal como la devuelve
     * {@link InventarioPtRepository#movimientosDeVentaPorOrigen}: en orden inverso al consumo.
     */
    private void cuandoSeLeaElKardexDeLaLinea(LoteConsumido... movimientos) {
        when(inventarioPtRepository.movimientosDeVentaPorOrigen("detalle_pedido", ID_DETALLE))
                .thenReturn(List.of(movimientos));
    }

    private void cuandoReponga(Integer idLote, String saldoAnterior, String saldoPosterior) {
        when(inventarioPtService.registrarEntrada(eq(idLote), any(),
                eq(TipoMovimientoPt.ENTRADA_DEVOLUCION), eq("devoluciones"), eq(ID_DEVOLUCION),
                anyString()))
                .thenReturn(new SaldosInventario(new BigDecimal(saldoAnterior),
                        new BigDecimal(saldoPosterior)));
    }

    private static LoteConsumido lote(Integer idLote, String codigo, String cantidad) {
        return new LoteConsumido(idLote, codigo, LocalDate.of(2026, 10, 5),
                new BigDecimal(cantidad));
    }

    /**
     * Devolución con los campos que aportan los {@code JOIN} del repositorio; la línea y la
     * cantidad pedida son las del escenario de 30 con devolución de 3 de la spec §4.
     */
    private static Devolucion devolucion(Integer id, EstadoDevolucion estado) {
        return new Devolucion(id, ID_DETALLE, "Producto en mal estado",
                "Tres panes llegaron aplastados", new BigDecimal("3.00"),
                LocalDate.of(2026, 9, 18), estado, 47, "Tienda La Esquina", 3,
                "Pan francés", new BigDecimal("30.00"));
    }
}
