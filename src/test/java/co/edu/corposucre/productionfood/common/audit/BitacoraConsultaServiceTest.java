package co.edu.corposucre.productionfood.common.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import co.edu.corposucre.productionfood.common.audit.dto.BitacoraResponse;
import co.edu.corposucre.productionfood.common.error.ParametroInvalidoException;

/**
 * Pruebas de la ruta de lectura de la bitácora ({@code listar} y {@code listarAcciones}) aisladas
 * de Spring.
 *
 * <p>Se separan de {@code BitacoraServiceTest}: aquel fija el contrato de {@code registrar()} y lo
 * documenta como tal, mientras que aquí lo que se prueba es la validación del rango, la paginación
 * y la conversión a {@link BitacoraResponse}. Mezclar ambas cosas en el mismo fichero diluiría las
 * dos intenciones sin ganar aislamiento, porque el montaje (mock del repositorio) es idéntico.
 */
class BitacoraConsultaServiceTest {

    private BitacoraRepository bitacoraRepository;
    private BitacoraService bitacoraService;

    @BeforeEach
    void setUp() {
        bitacoraRepository = mock(BitacoraRepository.class);
        bitacoraService = new BitacoraService(bitacoraRepository);
        when(bitacoraRepository.contar(any())).thenReturn(0L);
    }

    private static BitacoraFiltros sinFiltros() {
        return new BitacoraFiltros(null, null, null, null, null);
    }

    private static Bitacora fila(Integer idUsuario, String usuarioNombre) {
        return new Bitacora(1, idUsuario, usuarioNombre, "CREAR", "usuarios", "detalle",
                LocalDateTime.of(2026, 9, 18, 14, 32, 11));
    }

    @Test
    @DisplayName("fechaInicio posterior a fechaFin responde RANGO_FECHAS_INVALIDO sin consultar")
    void rangoInvertidoNoConsultaElRepositorio() {
        var filtros = new BitacoraFiltros(null, null, null,
                LocalDate.of(2026, 9, 30), LocalDate.of(2026, 9, 1));

        assertThatThrownBy(() -> bitacoraService.listar(filtros, 0, 20, null))
                .isInstanceOf(ParametroInvalidoException.class)
                .hasFieldOrPropertyWithValue("codigo", "RANGO_FECHAS_INVALIDO");

        // El rango invertido no puede tener resultados: la validación corta antes de la base.
        verify(bitacoraRepository, never()).buscar(any(), any(), anyBoolean(), anyInt(), anyLong());
        verify(bitacoraRepository, never()).contar(any());
    }

    @Test
    @DisplayName("fechaInicio igual a fechaFin es un rango válido (frontera)")
    void rangoDeUnSoloDiaEsValido() {
        var filtros = new BitacoraFiltros(null, null, null,
                LocalDate.of(2026, 9, 18), LocalDate.of(2026, 9, 18));
        when(bitacoraRepository.buscar(any(), any(), anyBoolean(), anyInt(), anyLong()))
                .thenReturn(List.of());

        assertThatCode(() -> bitacoraService.listar(filtros, 0, 20, null))
                .doesNotThrowAnyException();
        verify(bitacoraRepository).buscar(any(), any(), anyBoolean(), anyInt(), anyLong());
    }

    @Test
    @DisplayName("Un rango con un extremo o ambos nulos no se valida ni lanza")
    void rangoOpcionalConExtremosNulosNoLanza() {
        when(bitacoraRepository.buscar(any(), any(), anyBoolean(), anyInt(), anyLong()))
                .thenReturn(List.of());

        assertThatCode(() -> bitacoraService.listar(
                new BitacoraFiltros(null, null, null, null, LocalDate.of(2026, 9, 30)),
                0, 20, null)).doesNotThrowAnyException();
        assertThatCode(() -> bitacoraService.listar(
                new BitacoraFiltros(null, null, null, LocalDate.of(2026, 9, 1), null),
                0, 20, null)).doesNotThrowAnyException();
        assertThatCode(() -> bitacoraService.listar(sinFiltros(), 0, 20, null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("totalElements sale de contar, no del tamaño de la página")
    void totalSaleDeContarYNoDeLaPagina() {
        when(bitacoraRepository.buscar(any(), any(), anyBoolean(), anyInt(), anyLong()))
                .thenReturn(List.of(fila(1, "Administrador Inicial"), fila(null, null)));
        when(bitacoraRepository.contar(any())).thenReturn(45L);

        var r = bitacoraService.listar(sinFiltros(), 0, 20, null);

        assertThat(r.content()).hasSize(2);
        assertThat(r.totalElements()).isEqualTo(45L);
        assertThat(r.totalPages()).isEqualTo(3);
    }

    @Test
    @DisplayName("idUsuario nulo deja usuario en null; con autor se anida id y nombre")
    void convierteFilaConYsinAutor() {
        when(bitacoraRepository.buscar(any(), any(), anyBoolean(), anyInt(), anyLong()))
                .thenReturn(List.of(fila(null, null), fila(1, "Administrador Inicial")));

        var r = bitacoraService.listar(sinFiltros(), 0, 20, null);

        assertThat(r.content().get(0).usuario()).isNull();
        assertThat(r.content().get(1).usuario())
                .isEqualTo(new BitacoraResponse.Usuario(1, "Administrador Inicial"));
        assertThat(r.content().get(1).accion()).isEqualTo("CREAR");
        assertThat(r.content().get(1).tablaAfectada()).isEqualTo("usuarios");
        assertThat(r.content().get(1).fecha())
                .isEqualTo(LocalDateTime.of(2026, 9, 18, 14, 32, 11));
    }

    @Test
    @DisplayName("Sin sort el orden por defecto es fecha descendente")
    void ordenPorDefectoEsFechaDesc() {
        when(bitacoraRepository.buscar(any(), any(), anyBoolean(), anyInt(), anyLong()))
                .thenReturn(List.of());

        bitacoraService.listar(sinFiltros(), 0, 20, null);

        verify(bitacoraRepository).buscar(any(), eq("fecha"), eq(false), anyInt(), anyLong());
    }

    @Test
    @DisplayName("Un campo de orden fuera de la lista blanca responde CAMPO_ORDEN_INVALIDO")
    void ordenFueraDeLaListaBlancaLanza() {
        assertThatThrownBy(() -> bitacoraService.listar(sinFiltros(), 0, 20, "correo,asc"))
                .isInstanceOf(ParametroInvalidoException.class)
                .hasFieldOrPropertyWithValue("codigo", "CAMPO_ORDEN_INVALIDO");

        verify(bitacoraRepository, never()).buscar(any(), any(), anyBoolean(), anyInt(), anyLong());
    }

    @Test
    @DisplayName("listarAcciones devuelve el catálogo del repositorio")
    void listarAccionesDelegaEnElRepositorio() {
        when(bitacoraRepository.listarAcciones()).thenReturn(List.of("CREAR", "LOGIN_FALLIDO"));

        assertThat(bitacoraService.listarAcciones()).containsExactly("CREAR", "LOGIN_FALLIDO");
    }

    @Test
    @DisplayName("Un page que desbordaría un int produce el offset long correcto")
    void offsetNoDesbordaConPageGrande() {
        when(bitacoraRepository.buscar(any(), any(), anyBoolean(), anyInt(), anyLong()))
                .thenReturn(List.of());
        var offset = ArgumentCaptor.forClass(Long.class);

        // 2_000_000_000 * 100 desborda int a un valor negativo; en long debe llegar el
        // producto exacto 200_000_000_000, positivo.
        bitacoraService.listar(sinFiltros(), 2_000_000_000, 100, null);

        verify(bitacoraRepository).buscar(any(), any(), anyBoolean(), eq(100), offset.capture());
        assertThat(offset.getValue()).isEqualTo(200_000_000_000L);
        assertThat(offset.getValue()).isPositive();
    }
}
