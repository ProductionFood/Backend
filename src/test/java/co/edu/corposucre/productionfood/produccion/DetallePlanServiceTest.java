package co.edu.corposucre.productionfood.produccion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import co.edu.corposucre.productionfood.common.error.ConflictoNegocioException;
import co.edu.corposucre.productionfood.common.error.ParametroInvalidoException;
import co.edu.corposucre.productionfood.common.pagination.PageRequest;
import co.edu.corposucre.productionfood.producto.ProductoRepository;
import co.edu.corposucre.productionfood.produccion.dto.AgregarDetallePlanRequest;
import co.edu.corposucre.productionfood.receta.DetalleReceta;
import co.edu.corposucre.productionfood.receta.DetalleRecetaRepository;
import co.edu.corposucre.productionfood.receta.Receta;
import co.edu.corposucre.productionfood.receta.RecetaRepository;

/**
 * Pruebas unitarias de {@link DetallePlanService} con Mockito (HU-21 PR3).
 *
 * <p>Cubren el listado paginado —con su avance y su consumo teórico— y el alta de una línea con
 * todas sus validaciones. La persistencia JDBC se prueba en {@link DetallePlanRepositoryTest};
 * aquí los repositorios se sustituyen por mocks y la calculadora es la instancia real, porque es
 * cálculo puro y así se verifica el resultado de CP-18 de extremo a extremo.
 *
 * <p>El test del listado comprueba que las recetas y sus ingredientes se cargan en lote: si el
 * servicio ejecutara una consulta por línea, {@code buscarPorProductos} y
 * {@code listarPorRecetas} se llamarían más de una vez.
 */
class DetallePlanServiceTest {

    private static final Integer ID_PLAN = 9;
    private static final Integer ID_PRODUCTO = 3;

    private PlanProduccionRepository planProduccionRepository;
    private DetallePlanRepository detallePlanRepository;
    private ProductoRepository productoRepository;
    private RecetaRepository recetaRepository;
    private DetalleRecetaRepository detalleRecetaRepository;
    private DetallePlanService service;

    @BeforeEach
    void setUp() {
        planProduccionRepository = mock(PlanProduccionRepository.class);
        detallePlanRepository = mock(DetallePlanRepository.class);
        productoRepository = mock(ProductoRepository.class);
        recetaRepository = mock(RecetaRepository.class);
        detalleRecetaRepository = mock(DetalleRecetaRepository.class);
        service = new DetallePlanService(planProduccionRepository, detallePlanRepository,
                productoRepository, recetaRepository, detalleRecetaRepository,
                new CalculadoraConsumoService());
    }

    private static PlanProduccion plan(Integer idPlan, EstadoPlan estado) {
        var plan = new PlanProduccion();
        plan.setIdPlan(idPlan);
        plan.setEstado(estado);
        return plan;
    }

    private static DetallePlanProduccion linea(Integer idDetalle, Integer idProducto, String nombre,
                                               String unidad, String planificada,
                                               String producida, boolean tieneReceta) {
        var linea = new DetallePlanProduccion();
        linea.setIdDetallePlan(idDetalle);
        linea.setIdPlan(ID_PLAN);
        linea.setIdProducto(idProducto);
        linea.setNombreProducto(nombre);
        linea.setUnidad(unidad);
        linea.setCantidadPlanificada(new BigDecimal(planificada));
        linea.setCantidadProducida(new BigDecimal(producida));
        linea.setTieneReceta(tieneReceta);
        return linea;
    }

    private static Receta receta(Integer idReceta, Integer idProducto, String cantidadProducir) {
        var receta = new Receta();
        receta.setIdReceta(idReceta);
        receta.setIdProducto(idProducto);
        receta.setCantidadProducir(new BigDecimal(cantidadProducir));
        return receta;
    }

    private static DetalleReceta ingrediente(Integer idReceta, String nombre, String cantidad,
                                             String unidad) {
        var ingrediente = new DetalleReceta();
        ingrediente.setIdReceta(idReceta);
        ingrediente.setNombreMateriaPrima(nombre);
        ingrediente.setCantidad(new BigDecimal(cantidad));
        ingrediente.setAbreviaturaUnidad(unidad);
        return ingrediente;
    }

    // ------------------------------------------------------------------ listar

    @Test
    @DisplayName("listar devuelve avance, producto y consumo teórico cargando recetas en lote")
    void listarDevuelveLineasConAvanceYConsumoTeorico() {
        when(planProduccionRepository.buscarPorId(ID_PLAN))
                .thenReturn(Optional.of(plan(ID_PLAN, EstadoPlan.PLANIFICADO)));
        var pan = linea(21, ID_PRODUCTO, "Pan francés", "und", "90.00", "60.00", true);
        var galleta = linea(22, 4, "Galleta", "und", "50.00", "0.00", true);
        when(detallePlanRepository.listarPorPlan(eq(ID_PLAN), any(PageRequest.class)))
                .thenReturn(List.of(pan, galleta));
        when(detallePlanRepository.contarPorPlan(ID_PLAN)).thenReturn(2L);

        when(recetaRepository.buscarPorProductos(anyList())).thenReturn(List.of(
                receta(8, ID_PRODUCTO, "30"),
                receta(9, 4, "10")));
        when(detalleRecetaRepository.listarPorRecetas(anyList())).thenReturn(List.of(
                ingrediente(8, "Harina de trigo", "5", "kg"),
                ingrediente(9, "Azúcar", "2", "kg")));

        var r = service.listar(ID_PLAN, 0, 10, null);

        assertThat(r.content()).hasSize(2);

        var primera = r.content().get(0);
        assertThat(primera.idDetallePlan()).isEqualTo(21);
        assertThat(primera.producto().idProducto()).isEqualTo(ID_PRODUCTO);
        assertThat(primera.producto().nombre()).isEqualTo("Pan francés");
        assertThat(primera.producto().unidad()).isEqualTo("und");
        assertThat(primera.cantidadPlanificada()).isEqualByComparingTo("90.00");
        assertThat(primera.cantidadProducida()).isEqualByComparingTo("60.00");
        assertThat(primera.avance()).isEqualByComparingTo("66.67");
        assertThat(primera.consumoTeoricoEstimado()).hasSize(1);
        assertThat(primera.consumoTeoricoEstimado().getFirst().materiaPrima())
                .isEqualTo("Harina de trigo");
        assertThat(primera.consumoTeoricoEstimado().getFirst().cantidad())
                .isEqualByComparingTo("15.000");
        assertThat(primera.consumoTeoricoEstimado().getFirst().unidad()).isEqualTo("kg");

        var segunda = r.content().get(1);
        assertThat(segunda.avance()).isEqualByComparingTo("0.00");
        assertThat(segunda.consumoTeoricoEstimado().getFirst().cantidad())
                .isEqualByComparingTo("10.000");

        assertThat(r.page()).isZero();
        assertThat(r.size()).isEqualTo(10);
        assertThat(r.totalElements()).isEqualTo(2L);
        assertThat(r.totalPages()).isEqualTo(1);

        // No N+1: una sola consulta por conjunto, no una por línea.
        verify(recetaRepository, times(1)).buscarPorProductos(anyList());
        verify(detalleRecetaRepository, times(1)).listarPorRecetas(anyList());
    }

    @Test
    @DisplayName("listar un plan inexistente responde 404 PLAN_INEXISTENTE")
    void listarPlanInexistenteResponde404() {
        when(planProduccionRepository.buscarPorId(9999)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.listar(9999, 0, 10, null))
                .isInstanceOf(PlanInexistenteException.class)
                .hasFieldOrPropertyWithValue("codigo", "PLAN_INEXISTENTE");

        // El plan se comprueba antes que la paginación: no se toca el detalle.
        verifyNoInteractions(detallePlanRepository);
    }

    @Test
    @DisplayName("listar con un sort fuera de la lista blanca responde CAMPO_ORDEN_INVALIDO")
    void listarConSortInvalidoRespondeCampoOrdenInvalido() {
        when(planProduccionRepository.buscarPorId(ID_PLAN))
                .thenReturn(Optional.of(plan(ID_PLAN, EstadoPlan.PLANIFICADO)));

        assertThatThrownBy(() -> service.listar(ID_PLAN, 0, 10, "desconocido"))
                .isInstanceOf(ParametroInvalidoException.class)
                .hasFieldOrPropertyWithValue("codigo", "CAMPO_ORDEN_INVALIDO");

        verify(detallePlanRepository, never()).listarPorPlan(any(), any());
        verify(detallePlanRepository, never()).contarPorPlan(any());
    }

    // ----------------------------------------------------------------- agregar

    @Test
    @DisplayName("agregar a un producto sin receta deja cantidadProducida y avance en cero")
    void agregarProductoSinRecetaIniciaEnCeroYSinConsumo() {
        when(planProduccionRepository.buscarPorId(ID_PLAN))
                .thenReturn(Optional.of(plan(ID_PLAN, EstadoPlan.PLANIFICADO)));
        when(productoRepository.existeActivo(ID_PRODUCTO)).thenReturn(true);
        when(detallePlanRepository.existeProducto(ID_PLAN, ID_PRODUCTO)).thenReturn(false);
        when(detallePlanRepository.insertar(any(DetallePlanProduccion.class))).thenReturn(21);
        when(detallePlanRepository.buscarPorId(21)).thenReturn(Optional.of(
                linea(21, ID_PRODUCTO, "Pan artesanal", "und", "90.00", "0.00", false)));
        when(recetaRepository.buscarPorProducto(ID_PRODUCTO)).thenReturn(Optional.empty());

        var r = service.agregar(ID_PLAN,
                new AgregarDetallePlanRequest(ID_PRODUCTO, new BigDecimal("90")));

        assertThat(r.idDetallePlan()).isEqualTo(21);
        assertThat(r.producto().nombre()).isEqualTo("Pan artesanal");
        assertThat(r.cantidadPlanificada()).isEqualByComparingTo("90.00");
        assertThat(r.cantidadProducida()).isEqualByComparingTo("0.00");
        assertThat(r.avance()).isEqualByComparingTo("0.00");
        assertThat(r.tieneReceta()).isFalse();
        assertThat(r.consumoTeoricoEstimado()).isEmpty();

        var captor = ArgumentCaptor.forClass(DetallePlanProduccion.class);
        verify(detallePlanRepository).insertar(captor.capture());
        assertThat(captor.getValue().getIdPlan()).isEqualTo(ID_PLAN);
        assertThat(captor.getValue().getIdProducto()).isEqualTo(ID_PRODUCTO);
        assertThat(captor.getValue().getCantidadPlanificada()).isEqualByComparingTo("90.00");
        verify(detalleRecetaRepository, never()).listarPorReceta(any());
    }

    @Test
    @DisplayName("CP-18: receta que rinde 30 con 5 kg y planificar 90 da un consumo de 15.000")
    void agregarConRecetaCalculaElConsumoTeoricoDeCp18() {
        when(planProduccionRepository.buscarPorId(ID_PLAN))
                .thenReturn(Optional.of(plan(ID_PLAN, EstadoPlan.PLANIFICADO)));
        when(productoRepository.existeActivo(ID_PRODUCTO)).thenReturn(true);
        when(detallePlanRepository.existeProducto(ID_PLAN, ID_PRODUCTO)).thenReturn(false);
        when(detallePlanRepository.insertar(any(DetallePlanProduccion.class))).thenReturn(21);
        when(detallePlanRepository.buscarPorId(21)).thenReturn(Optional.of(
                linea(21, ID_PRODUCTO, "Pan francés", "und", "90.00", "0.00", true)));
        when(recetaRepository.buscarPorProducto(ID_PRODUCTO))
                .thenReturn(Optional.of(receta(8, ID_PRODUCTO, "30")));
        when(detalleRecetaRepository.listarPorReceta(8)).thenReturn(List.of(
                ingrediente(8, "Harina de trigo", "5", "kg")));

        var r = service.agregar(ID_PLAN,
                new AgregarDetallePlanRequest(ID_PRODUCTO, new BigDecimal("90")));

        assertThat(r.tieneReceta()).isTrue();
        assertThat(r.consumoTeoricoEstimado()).hasSize(1);
        assertThat(r.consumoTeoricoEstimado().getFirst().materiaPrima())
                .isEqualTo("Harina de trigo");
        assertThat(r.consumoTeoricoEstimado().getFirst().cantidad())
                .isEqualByComparingTo("15.000");
        assertThat(r.consumoTeoricoEstimado().getFirst().unidad()).isEqualTo("kg");
    }

    @Test
    @DisplayName("agregar a un plan EN_PROCESO responde 409 PLAN_NO_EDITABLE")
    void agregarAPlanNoEditableRespondeConflicto() {
        when(planProduccionRepository.buscarPorId(ID_PLAN))
                .thenReturn(Optional.of(plan(ID_PLAN, EstadoPlan.EN_PROCESO)));

        assertThatThrownBy(() -> service.agregar(ID_PLAN,
                new AgregarDetallePlanRequest(ID_PRODUCTO, new BigDecimal("90"))))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "PLAN_NO_EDITABLE");

        verifyNoInteractions(productoRepository, detallePlanRepository, recetaRepository,
                detalleRecetaRepository);
    }

    @Test
    @DisplayName("agregar a un plan inexistente responde 404 PLAN_INEXISTENTE")
    void agregarAPlanInexistenteResponde404() {
        when(planProduccionRepository.buscarPorId(9999)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.agregar(9999,
                new AgregarDetallePlanRequest(ID_PRODUCTO, new BigDecimal("90"))))
                .isInstanceOf(PlanInexistenteException.class)
                .hasFieldOrPropertyWithValue("codigo", "PLAN_INEXISTENTE");

        verifyNoInteractions(productoRepository, detallePlanRepository);
    }

    @Test
    @DisplayName("agregar un producto inexistente o inactivo responde 409 PRODUCTO_INEXISTENTE")
    void agregarProductoInexistenteRespondeConflicto() {
        when(planProduccionRepository.buscarPorId(ID_PLAN))
                .thenReturn(Optional.of(plan(ID_PLAN, EstadoPlan.PLANIFICADO)));
        when(productoRepository.existeActivo(9999)).thenReturn(false);

        assertThatThrownBy(() -> service.agregar(ID_PLAN,
                new AgregarDetallePlanRequest(9999, new BigDecimal("90"))))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "PRODUCTO_INEXISTENTE");

        verify(detallePlanRepository, never()).insertar(any(DetallePlanProduccion.class));
    }

    @Test
    @DisplayName("agregar un producto que ya está en el plan responde 409 PRODUCTO_DUPLICADO")
    void agregarProductoDuplicadoRespondeConflicto() {
        when(planProduccionRepository.buscarPorId(ID_PLAN))
                .thenReturn(Optional.of(plan(ID_PLAN, EstadoPlan.PLANIFICADO)));
        when(productoRepository.existeActivo(ID_PRODUCTO)).thenReturn(true);
        when(detallePlanRepository.existeProducto(ID_PLAN, ID_PRODUCTO)).thenReturn(true);

        assertThatThrownBy(() -> service.agregar(ID_PLAN,
                new AgregarDetallePlanRequest(ID_PRODUCTO, new BigDecimal("90"))))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "PRODUCTO_DUPLICADO");

        verify(detallePlanRepository, never()).insertar(any(DetallePlanProduccion.class));
    }

    @Test
    @DisplayName("agregar con cantidad cero responde 400 CANTIDAD_INVALIDA")
    void agregarConCantidadCeroRespondeCantidadInvalida() {
        when(planProduccionRepository.buscarPorId(ID_PLAN))
                .thenReturn(Optional.of(plan(ID_PLAN, EstadoPlan.PLANIFICADO)));

        assertThatThrownBy(() -> service.agregar(ID_PLAN,
                new AgregarDetallePlanRequest(ID_PRODUCTO, BigDecimal.ZERO)))
                .isInstanceOf(ParametroInvalidoException.class)
                .hasFieldOrPropertyWithValue("codigo", "CANTIDAD_INVALIDA");

        verifyNoInteractions(productoRepository, detallePlanRepository);
    }

    @Test
    @DisplayName("agregar con cantidad negativa responde 400 CANTIDAD_INVALIDA")
    void agregarConCantidadNegativaRespondeCantidadInvalida() {
        when(planProduccionRepository.buscarPorId(ID_PLAN))
                .thenReturn(Optional.of(plan(ID_PLAN, EstadoPlan.PLANIFICADO)));

        assertThatThrownBy(() -> service.agregar(ID_PLAN,
                new AgregarDetallePlanRequest(ID_PRODUCTO, new BigDecimal("-10"))))
                .isInstanceOf(ParametroInvalidoException.class)
                .hasFieldOrPropertyWithValue("codigo", "CANTIDAD_INVALIDA");

        verifyNoInteractions(productoRepository, detallePlanRepository);
    }
}
