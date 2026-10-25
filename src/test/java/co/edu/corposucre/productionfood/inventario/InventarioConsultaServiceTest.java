package co.edu.corposucre.productionfood.inventario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import co.edu.corposucre.productionfood.common.error.ParametroInvalidoException;
import co.edu.corposucre.productionfood.common.error.RecursoNoEncontradoException;
import co.edu.corposucre.productionfood.inventario.dto.InventarioResponse;

class InventarioConsultaServiceTest {

    private InventarioRepository inventarioRepository;
    private MovimientoInventarioRepository movimientoRepository;
    private InventarioConsultaRepository consultaRepository;
    private InventarioService servicio;

    @BeforeEach
    void setUp() {
        inventarioRepository = mock(InventarioRepository.class);
        movimientoRepository = mock(MovimientoInventarioRepository.class);
        consultaRepository = mock(InventarioConsultaRepository.class);
        servicio = new InventarioService(inventarioRepository, movimientoRepository,
                consultaRepository);
    }

    @Test
    @DisplayName("Listar sin filtros incluye inactivas, no filtra por stock bajo y ordena por nombre asc")
    void listarSinFiltrosUsaValoresPorDefecto() {
        var fila = fila(5, "Harina de trigo", "kg", "12.50", "50.00", "37.50", "BAJO", true);
        when(consultaRepository.buscar(null, false, true, "nombre", true, 20, 0L))
                .thenReturn(List.of(fila));
        when(consultaRepository.contar(null, false, true)).thenReturn(1L);

        var r = servicio.listar(null, null, null, 0, 20, null);

        assertThat(r.content()).containsExactly(fila);
        assertThat(r.page()).isZero();
        assertThat(r.size()).isEqualTo(20);
        assertThat(r.totalElements()).isEqualTo(1);
        verify(consultaRepository).buscar(null, false, true, "nombre", true, 20, 0L);
        verify(consultaRepository).contar(null, false, true);
    }

    @Test
    @DisplayName("soloStockBajo=true se traspasa al repositorio para filtrar BAJO y AGOTADO")
    void soloStockBajoSeTraspasaAlRepositorio() {
        when(consultaRepository.buscar(null, true, true, "nombre", true, 20, 0L))
                .thenReturn(List.of());
        when(consultaRepository.contar(null, true, true)).thenReturn(0L);

        servicio.listar(null, true, null, 0, 20, null);

        verify(consultaRepository).buscar(null, true, true, "nombre", true, 20, 0L);
        verify(consultaRepository).contar(null, true, true);
    }

    @Test
    @DisplayName("incluirInactivas=false se traspasa al repositorio para excluir estado 0")
    void incluirInactivasFalseSeTraspasaAlRepositorio() {
        when(consultaRepository.buscar(null, false, false, "nombre", true, 20, 0L))
                .thenReturn(List.of());
        when(consultaRepository.contar(null, false, false)).thenReturn(0L);

        servicio.listar(null, null, false, 0, 20, null);

        verify(consultaRepository).buscar(null, false, false, "nombre", true, 20, 0L);
        verify(consultaRepository).contar(null, false, false);
    }

    @Test
    @DisplayName("La página 1 con tamaño 3 calcula un offset de 3")
    void paginaUnoConTresFilasCalculaOffsetTres() {
        when(consultaRepository.buscar(null, false, true, "nombre", true, 3, 3L))
                .thenReturn(List.of());
        when(consultaRepository.contar(null, false, true)).thenReturn(7L);

        var r = servicio.listar(null, null, null, 1, 3, null);

        assertThat(r.page()).isEqualTo(1);
        assertThat(r.size()).isEqualTo(3);
        assertThat(r.totalElements()).isEqualTo(7);
        verify(consultaRepository).buscar(null, false, true, "nombre", true, 3, 3L);
    }

    @Test
    @DisplayName("El orden cantidadDisponible,desc resuelve la columna cantidad_disponible descendente")
    void ordenPorCantidadDisponibleDesc() {
        when(consultaRepository.buscar(null, false, true, "cantidad_disponible", false, 20, 0L))
                .thenReturn(List.of());
        when(consultaRepository.contar(null, false, true)).thenReturn(0L);

        servicio.listar(null, null, null, 0, 20, "cantidadDisponible,desc");

        verify(consultaRepository)
                .buscar(null, false, true, "cantidad_disponible", false, 20, 0L);
    }

    @Test
    @DisplayName("Un campo de orden fuera de la lista blanca responde 400 CAMPO_ORDEN_INVALIDO")
    void ordenFueraDeLaListaBlancaResponde400() {
        assertThatThrownBy(() -> servicio.listar(null, null, null, 0, 20, "costo_unitario,asc"))
                .isInstanceOf(ParametroInvalidoException.class)
                .hasFieldOrPropertyWithValue("codigo", "CAMPO_ORDEN_INVALIDO");

        verifyNoInteractions(consultaRepository);
    }

    @Test
    @DisplayName("Una página negativa o un tamaño cero responden 400 PARAMETRO_INVALIDO")
    void paginacionInvalidaResponde400() {
        assertThatThrownBy(() -> servicio.listar(null, null, null, -1, 20, null))
                .isInstanceOf(ParametroInvalidoException.class)
                .hasFieldOrPropertyWithValue("codigo", "PARAMETRO_INVALIDO");
        assertThatThrownBy(() -> servicio.listar(null, null, null, 0, 0, null))
                .isInstanceOf(ParametroInvalidoException.class)
                .hasFieldOrPropertyWithValue("codigo", "PARAMETRO_INVALIDO");

        verifyNoInteractions(consultaRepository);
    }

    @Test
    @DisplayName("Obtener una materia prima existente devuelve la fila de la vista")
    void obtenerMateriaPrimaExistente() {
        var fila = fila(7, "Levadura", "g", "0.00", "500.00", "500.00", "AGOTADO", true);
        when(consultaRepository.buscarPorMateriaPrima(7)).thenReturn(Optional.of(fila));

        var r = servicio.obtener(7);

        assertThat(r).isEqualTo(fila);
        assertThat(r.estadoStock()).isEqualTo("AGOTADO");
        verify(consultaRepository).buscarPorMateriaPrima(7);
    }

    @Test
    @DisplayName("Obtener una materia prima inexistente responde 404 RECURSO_NO_ENCONTRADO")
    void obtenerMateriaPrimaInexistenteResponde404() {
        when(consultaRepository.buscarPorMateriaPrima(9999)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servicio.obtener(9999))
                .isInstanceOf(RecursoNoEncontradoException.class)
                .hasFieldOrPropertyWithValue("codigo", "RECURSO_NO_ENCONTRADO");
    }

    private static InventarioResponse fila(int idMateriaPrima, String nombre, String unidad,
                                           String cantidadDisponible, String stockMinimo,
                                           String faltante, String estadoStock, boolean activo) {
        return new InventarioResponse(
                idMateriaPrima,
                nombre,
                unidad,
                new BigDecimal(cantidadDisponible),
                new BigDecimal(stockMinimo),
                new BigDecimal(faltante),
                estadoStock,
                activo,
                LocalDateTime.of(2026, 9, 18, 9, 12, 44));
    }
}
