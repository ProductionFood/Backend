package co.edu.corposucre.productionfood.produccion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import co.edu.corposucre.productionfood.common.error.ParametroInvalidoException;
import co.edu.corposucre.productionfood.common.pagination.PageRequest;
import co.edu.corposucre.productionfood.inventario.InventarioService;
import co.edu.corposucre.productionfood.materiaprima.MateriaPrimaRepository;
import co.edu.corposucre.productionfood.receta.DetalleRecetaRepository;
import co.edu.corposucre.productionfood.receta.RecetaRepository;

/**
 * Pruebas unitarias del listado paginado de consumos de {@link ConsumoService} (HU-22 PR4).
 *
 * <p>No levantan Spring ni tocan la base: los repositorios y {@link InventarioService} se
 * sustituyen por mocks y la calculadora de eficiencia es la instancia real. Cubren el mapeo de la
 * página, el consumo fuera de receta, la página vacía, el plan inexistente, el orden por defecto
 * con la lista blanca y el rechazo de un campo de orden inválido.
 */
class ConsumoListarServiceTest {

    private static final Integer ID_PLAN = 9;
    private static final Integer ID_DETALLE = 21;
    private static final Integer ID_MATERIA_PRIMA = 5;
    private static final Integer ID_CONSUMO = 31;

    private static final LocalDateTime FECHA_REGISTRO =
            LocalDateTime.of(2026, 9, 18, 10, 22, 41);

    private DetallePlanRepository detallePlanRepository;
    private RecetaRepository recetaRepository;
    private DetalleRecetaRepository detalleRecetaRepository;
    private MateriaPrimaRepository materiaPrimaRepository;
    private ConsumoRepository consumoRepository;
    private PlanProduccionRepository planProduccionRepository;
    private InventarioService inventarioService;
    private ConsumoService service;

    @BeforeEach
    void setUp() {
        detallePlanRepository = mock(DetallePlanRepository.class);
        recetaRepository = mock(RecetaRepository.class);
        detalleRecetaRepository = mock(DetalleRecetaRepository.class);
        materiaPrimaRepository = mock(MateriaPrimaRepository.class);
        consumoRepository = mock(ConsumoRepository.class);
        planProduccionRepository = mock(PlanProduccionRepository.class);
        inventarioService = mock(InventarioService.class);
        service = new ConsumoService(detallePlanRepository, recetaRepository,
                detalleRecetaRepository, new CalculadoraConsumoService(), materiaPrimaRepository,
                consumoRepository, planProduccionRepository, inventarioService,
                new CalculadoraEficienciaService());
    }

    @Test
    @DisplayName("mapea la página de consumos con eficiencia, desviación y fuera de receta")
    void listarMapeaLaPagina() {
        when(planProduccionRepository.buscarPorId(ID_PLAN)).thenReturn(Optional.of(plan()));
        when(consumoRepository.listarPorPlan(eq(ID_PLAN), any())).thenReturn(List.of(
                consumo(ID_CONSUMO, ID_DETALLE, "Pan francés", ID_MATERIA_PRIMA,
                        "Harina de trigo", "15.000", "16.200"),
                consumo(32, 22, "Pan integral", 7, "Levadura", "0.150", "0.300")));
        when(consumoRepository.contarPorPlan(ID_PLAN)).thenReturn(2L);

        var r = service.listar(ID_PLAN, 0, 10, null);

        assertThat(r.content()).hasSize(2);
        assertThat(r.page()).isZero();
        assertThat(r.size()).isEqualTo(10);
        assertThat(r.totalElements()).isEqualTo(2L);
        assertThat(r.totalPages()).isEqualTo(1);
        assertThat(r.first()).isTrue();
        assertThat(r.last()).isTrue();

        var primero = r.content().getFirst();
        assertThat(primero.idConsumo()).isEqualTo(ID_CONSUMO);
        assertThat(primero.idDetallePlan()).isEqualTo(ID_DETALLE);
        assertThat(primero.producto()).isEqualTo("Pan francés");
        assertThat(primero.materiaPrima().idMateriaPrima()).isEqualTo(ID_MATERIA_PRIMA);
        assertThat(primero.materiaPrima().nombre()).isEqualTo("Harina de trigo");
        assertThat(primero.materiaPrima().unidad()).isEqualTo("kg");
        assertThat(primero.cantidadTeorica()).isEqualByComparingTo("15.000");
        assertThat(primero.cantidadReal()).isEqualByComparingTo("16.200");
        assertThat(primero.eficiencia()).isEqualByComparingTo("92.59");
        assertThat(primero.desviacionPorcentaje()).isEqualByComparingTo("8.00");
        assertThat(primero.fueraDeReceta()).isFalse();
        assertThat(primero.fechaRegistro()).isEqualTo(FECHA_REGISTRO);
        assertThat(primero.inventario()).isNull();

        var segundo = r.content().get(1);
        assertThat(segundo.idConsumo()).isEqualTo(32);
        assertThat(segundo.eficiencia()).isEqualByComparingTo("50.00");
        assertThat(segundo.desviacionPorcentaje()).isEqualByComparingTo("100.00");
        assertThat(segundo.inventario()).isNull();
    }

    @Test
    @DisplayName("un consumo fuera de receta marca la fila y deja la desviación nula")
    void consumoFueraDeReceta() {
        when(planProduccionRepository.buscarPorId(ID_PLAN)).thenReturn(Optional.of(plan()));
        when(consumoRepository.listarPorPlan(eq(ID_PLAN), any())).thenReturn(List.of(
                consumo(ID_CONSUMO, ID_DETALLE, "Pan francés", ID_MATERIA_PRIMA,
                        "Harina de trigo", "0.000", "2.000")));
        when(consumoRepository.contarPorPlan(ID_PLAN)).thenReturn(1L);

        var r = service.listar(ID_PLAN, 0, 10, null);

        var fila = r.content().getFirst();
        assertThat(fila.fueraDeReceta()).isTrue();
        assertThat(fila.eficiencia()).isEqualByComparingTo("0.00");
        assertThat(fila.desviacionPorcentaje()).isNull();
    }

    @Test
    @DisplayName("un plan inexistente responde 404 PLAN_INEXISTENTE sin consultar consumos")
    void planInexistenteResponde404() {
        when(planProduccionRepository.buscarPorId(ID_PLAN)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.listar(ID_PLAN, 0, 10, null))
                .isInstanceOf(PlanInexistenteException.class)
                .hasFieldOrPropertyWithValue("codigo", "PLAN_INEXISTENTE")
                .hasFieldOrPropertyWithValue("status", HttpStatus.NOT_FOUND);

        verifyNoInteractions(consumoRepository);
    }

    @Test
    @DisplayName("un plan sin consumos devuelve una página vacía con total cero")
    void planSinConsumosDevuelvePaginaVacia() {
        when(planProduccionRepository.buscarPorId(ID_PLAN)).thenReturn(Optional.of(plan()));
        when(consumoRepository.listarPorPlan(eq(ID_PLAN), any())).thenReturn(List.of());
        when(consumoRepository.contarPorPlan(ID_PLAN)).thenReturn(0L);

        var r = service.listar(ID_PLAN, 0, 10, null);

        assertThat(r.content()).isEmpty();
        assertThat(r.totalElements()).isZero();
        assertThat(r.totalPages()).isZero();
        assertThat(r.first()).isTrue();
        assertThat(r.last()).isTrue();
    }

    @Test
    @DisplayName("arma el PageRequest con la lista blanca y el orden por defecto producto,asc")
    void armaPageRequestConListaBlancaYOrdenPorDefecto() {
        when(planProduccionRepository.buscarPorId(ID_PLAN)).thenReturn(Optional.of(plan()));
        when(consumoRepository.listarPorPlan(eq(ID_PLAN), any())).thenReturn(List.of());
        when(consumoRepository.contarPorPlan(ID_PLAN)).thenReturn(0L);

        service.listar(ID_PLAN, 2, 20, null);

        var captor = ArgumentCaptor.forClass(PageRequest.class);
        verify(consumoRepository).listarPorPlan(eq(ID_PLAN), captor.capture());
        var orden = captor.getValue();
        assertThat(orden.page()).isEqualTo(2);
        assertThat(orden.size()).isEqualTo(20);
        assertThat(orden.propiedad()).isEqualTo("p.nombre");
        assertThat(orden.ascendente()).isTrue();
    }

    @Test
    @DisplayName("un campo de orden inválido responde 400 CAMPO_ORDEN_INVALIDO")
    void campoDeOrdenInvalidoResponde400() {
        when(planProduccionRepository.buscarPorId(ID_PLAN)).thenReturn(Optional.of(plan()));

        assertThatThrownBy(() -> service.listar(ID_PLAN, 0, 10, "precio,asc"))
                .isInstanceOf(ParametroInvalidoException.class)
                .hasFieldOrPropertyWithValue("codigo", "CAMPO_ORDEN_INVALIDO")
                .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST);

        verifyNoInteractions(consumoRepository);
    }

    private static PlanProduccion plan() {
        var plan = new PlanProduccion();
        plan.setIdPlan(ID_PLAN);
        plan.setEstado(EstadoPlan.EN_PROCESO);
        return plan;
    }

    private static ConsumoMateriaPrima consumo(Integer idConsumo, Integer idDetallePlan,
                                                String producto, Integer idMateriaPrima,
                                                String nombreMateriaPrima, String teorica,
                                                String real) {
        var consumo = new ConsumoMateriaPrima();
        consumo.setIdConsumo(idConsumo);
        consumo.setIdDetallePlan(idDetallePlan);
        consumo.setIdPlan(ID_PLAN);
        consumo.setNombreProducto(producto);
        consumo.setIdMateriaPrima(idMateriaPrima);
        consumo.setNombreMateriaPrima(nombreMateriaPrima);
        consumo.setUnidad("kg");
        consumo.setCantidadTeorica(new BigDecimal(teorica));
        consumo.setCantidadReal(new BigDecimal(real));
        consumo.setFechaRegistro(FECHA_REGISTRO);
        consumo.setEstadoPlan(EstadoPlan.EN_PROCESO);
        return consumo;
    }
}
