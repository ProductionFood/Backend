package co.edu.corposucre.productionfood.inventario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import co.edu.corposucre.productionfood.common.error.ParametroInvalidoException;
import co.edu.corposucre.productionfood.common.error.RecursoNoEncontradoException;
import co.edu.corposucre.productionfood.inventario.dto.InventarioResponse;
import co.edu.corposucre.productionfood.inventario.dto.MovimientoResponse;
import co.edu.corposucre.productionfood.inventario.dto.OrigenResponse;

class KardexConsultaServiceTest {

    private static final LocalDate DESDE = LocalDate.of(2026, 9, 1);
    private static final LocalDate HASTA = LocalDate.of(2026, 9, 18);

    private InventarioRepository inventarioRepository;
    private MovimientoInventarioRepository movimientoRepository;
    private InventarioConsultaRepository consultaRepository;
    private KardexConsultaRepository kardexRepository;
    private InventarioService servicio;

    @BeforeEach
    void setUp() {
        inventarioRepository = mock(InventarioRepository.class);
        movimientoRepository = mock(MovimientoInventarioRepository.class);
        consultaRepository = mock(InventarioConsultaRepository.class);
        kardexRepository = mock(KardexConsultaRepository.class);
        servicio = new InventarioService(inventarioRepository, movimientoRepository,
                consultaRepository, kardexRepository);
    }

    @Test
    @DisplayName("CP-15: una materia prima sin movimientos devuelve una página vacía, no un error")
    void materiaPrimaSinMovimientosDevuelvePaginaVacia() {
        when(consultaRepository.buscarPorMateriaPrima(7)).thenReturn(Optional.of(fila(7)));
        when(kardexRepository.buscar(7, null, null, 20, 0L)).thenReturn(List.of());
        when(kardexRepository.contar(7, null, null)).thenReturn(0L);

        var r = servicio.movimientos(7, null, null, 0, 20);

        assertThat(r.content()).isEmpty();
        assertThat(r.page()).isZero();
        assertThat(r.size()).isEqualTo(20);
        assertThat(r.totalElements()).isZero();
        assertThat(r.totalPages()).isZero();
        verify(kardexRepository).buscar(7, null, null, 20, 0L);
        verify(kardexRepository).contar(7, null, null);
    }

    @Test
    @DisplayName("Una materia prima inexistente responde 404 RECURSO_NO_ENCONTRADO sin leer el kardex")
    void materiaPrimaInexistenteResponde404() {
        when(consultaRepository.buscarPorMateriaPrima(9999)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servicio.movimientos(9999, null, null, 0, 20))
                .isInstanceOf(RecursoNoEncontradoException.class)
                .hasFieldOrPropertyWithValue("codigo", "RECURSO_NO_ENCONTRADO");

        verifyNoInteractions(kardexRepository);
    }

    @Test
    @DisplayName("La página 1 con tamaño 3 calcula un offset de 3 y devuelve la página del kardex")
    void paginaUnoConTresFilasCalculaOffsetTres() {
        var movimiento = movimiento();
        when(consultaRepository.buscarPorMateriaPrima(5)).thenReturn(Optional.of(fila(5)));
        when(kardexRepository.buscar(5, null, null, 3, 3L)).thenReturn(List.of(movimiento));
        when(kardexRepository.contar(5, null, null)).thenReturn(7L);

        var r = servicio.movimientos(5, null, null, 1, 3);

        assertThat(r.content()).containsExactly(movimiento);
        assertThat(r.page()).isEqualTo(1);
        assertThat(r.size()).isEqualTo(3);
        assertThat(r.totalElements()).isEqualTo(7);
        verify(kardexRepository).buscar(5, null, null, 3, 3L);
    }

    @Test
    @DisplayName("El rango de fechas se traspasa tal cual al repositorio del kardex")
    void rangoDeFechasSeTraspasaAlRepositorio() {
        when(consultaRepository.buscarPorMateriaPrima(5)).thenReturn(Optional.of(fila(5)));
        when(kardexRepository.buscar(5, DESDE, HASTA, 20, 0L)).thenReturn(List.of());
        when(kardexRepository.contar(5, DESDE, HASTA)).thenReturn(0L);

        servicio.movimientos(5, DESDE, HASTA, 0, 20);

        verify(kardexRepository).buscar(5, DESDE, HASTA, 20, 0L);
        verify(kardexRepository).contar(5, DESDE, HASTA);
    }

    @Test
    @DisplayName("Un rango con la fecha de inicio posterior a la de fin responde 400 RANGO_FECHAS_INVALIDO")
    void rangoDeFechasInvertidoResponde400() {
        assertThatThrownBy(() -> servicio.movimientos(5, HASTA, DESDE, 0, 20))
                .isInstanceOf(ParametroInvalidoException.class)
                .hasFieldOrPropertyWithValue("codigo", "RANGO_FECHAS_INVALIDO");

        verifyNoInteractions(consultaRepository, kardexRepository);
    }

    @Test
    @DisplayName("Una página negativa o un tamaño fuera de 1..100 responden 400 PARAMETRO_INVALIDO")
    void paginacionInvalidaResponde400() {
        assertThatThrownBy(() -> servicio.movimientos(5, null, null, -1, 20))
                .isInstanceOf(ParametroInvalidoException.class)
                .hasFieldOrPropertyWithValue("codigo", "PARAMETRO_INVALIDO");
        assertThatThrownBy(() -> servicio.movimientos(5, null, null, 0, 101))
                .isInstanceOf(ParametroInvalidoException.class)
                .hasFieldOrPropertyWithValue("codigo", "PARAMETRO_INVALIDO");

        verifyNoInteractions(consultaRepository, kardexRepository);
    }

    @Test
    @DisplayName("Un tamaño de 100 es válido y llega al repositorio")
    void tamanoCienEsValido() {
        when(consultaRepository.buscarPorMateriaPrima(5)).thenReturn(Optional.of(fila(5)));
        when(kardexRepository.buscar(5, null, null, 100, 0L)).thenReturn(List.of());
        when(kardexRepository.contar(5, null, null)).thenReturn(0L);

        var r = servicio.movimientos(5, null, null, 0, 100);

        assertThat(r.size()).isEqualTo(100);
        verify(kardexRepository).buscar(5, null, null, 100, 0L);
    }

    private static InventarioResponse fila(int idMateriaPrima) {
        return new InventarioResponse(
                idMateriaPrima,
                "Harina de trigo",
                "kg",
                new BigDecimal("12.50"),
                new BigDecimal("50.00"),
                new BigDecimal("37.50"),
                "BAJO",
                true,
                LocalDateTime.of(2026, 9, 18, 9, 12, 44));
    }

    private static MovimientoResponse movimiento() {
        return new MovimientoResponse(
                LocalDateTime.of(2026, 9, 18, 9, 12, 44),
                "SALIDA_PRODUCCION",
                new BigDecimal("100.000"),
                new BigDecimal("112.50"),
                new BigDecimal("12.50"),
                new OrigenResponse("consumo_materia_prima", 31),
                "Ismael Batalla");
    }
}
