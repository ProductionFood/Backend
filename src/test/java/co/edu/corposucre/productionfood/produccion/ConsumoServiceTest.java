package co.edu.corposucre.productionfood.produccion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import co.edu.corposucre.productionfood.inventario.InventarioService;
import co.edu.corposucre.productionfood.materiaprima.MateriaPrimaConDetalle;
import co.edu.corposucre.productionfood.materiaprima.MateriaPrimaRepository;
import co.edu.corposucre.productionfood.receta.DetalleReceta;
import co.edu.corposucre.productionfood.receta.DetalleRecetaRepository;
import co.edu.corposucre.productionfood.receta.Receta;
import co.edu.corposucre.productionfood.receta.RecetaRepository;

/**
 * Pruebas unitarias de {@link ConsumoService} con Mockito (HU-22 PR2).
 *
 * <p>No levantan Spring ni tocan la base: los repositorios se sustituyen por mocks y la
 * calculadora es la instancia real, porque es cálculo puro. Cubren el consumo teórico con receta,
 * el producto sin receta, la línea inexistente y la garantía de que la consulta no escribe en
 * ninguna de sus fuentes.
 */
class ConsumoServiceTest {

    private static final Integer ID_DETALLE = 21;
    private static final Integer ID_PRODUCTO = 3;
    private static final Integer ID_RECETA = 8;

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
    @DisplayName("con receta calcula el factor, los teóricos y lo disponible de cada ingrediente")
    void conRecetaCalculaTeoricosYDisponible() {
        when(detallePlanRepository.buscarPorId(ID_DETALLE))
                .thenReturn(Optional.of(linea("Pan francés", "90.00")));
        when(recetaRepository.buscarPorProducto(ID_PRODUCTO))
                .thenReturn(Optional.of(receta("30.000")));
        when(detalleRecetaRepository.listarPorReceta(ID_RECETA)).thenReturn(List.of(
                ingrediente(1, "Harina de trigo", "5.000", "kg"),
                ingrediente(2, "Sal", "0.050", "kg")));
        var harinaDisponible = materiaPrima("112.50");
        var salDisponible = materiaPrima("0.00");
        when(materiaPrimaRepository.buscarConDetalle(1))
                .thenReturn(Optional.of(harinaDisponible));
        when(materiaPrimaRepository.buscarConDetalle(2))
                .thenReturn(Optional.of(salDisponible));

        var r = service.consumoTeorico(ID_DETALLE);

        assertThat(r.idDetallePlan()).isEqualTo(ID_DETALLE);
        assertThat(r.producto()).isEqualTo("Pan francés");
        assertThat(r.cantidadPlanificada()).isEqualByComparingTo("90.00");
        assertThat(r.recetaRinde()).isEqualByComparingTo("30.000");
        assertThat(r.recetaRinde().scale()).isEqualTo(3);
        assertThat(r.factor()).isEqualByComparingTo("3.000");
        assertThat(r.factor().scale()).isEqualTo(3);
        assertThat(r.ingredientes()).hasSize(2);

        var harina = r.ingredientes().getFirst();
        assertThat(harina.idMateriaPrima()).isEqualTo(1);
        assertThat(harina.nombre()).isEqualTo("Harina de trigo");
        assertThat(harina.unidad()).isEqualTo("kg");
        assertThat(harina.porTanda()).isEqualByComparingTo("5.000");
        assertThat(harina.porTanda().scale()).isEqualTo(3);
        assertThat(harina.teorico()).isEqualByComparingTo("15.000");
        assertThat(harina.teorico().scale()).isEqualTo(3);
        assertThat(harina.disponible()).isEqualByComparingTo("112.50");
        assertThat(harina.disponible().scale()).isEqualTo(2);

        var sal = r.ingredientes().get(1);
        assertThat(sal.idMateriaPrima()).isEqualTo(2);
        assertThat(sal.nombre()).isEqualTo("Sal");
        assertThat(sal.unidad()).isEqualTo("kg");
        assertThat(sal.porTanda()).isEqualByComparingTo("0.050");
        assertThat(sal.teorico()).isEqualByComparingTo("0.150");
        assertThat(sal.disponible()).isEqualByComparingTo("0.00");
        assertThat(sal.disponible().scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("un producto sin receta devuelve ingredientes vacíos y los datos de receta nulos")
    void productoSinRecetaDevuelveIngredientesVacios() {
        when(detallePlanRepository.buscarPorId(ID_DETALLE))
                .thenReturn(Optional.of(linea("Pan artesanal", "90.00")));
        when(recetaRepository.buscarPorProducto(ID_PRODUCTO)).thenReturn(Optional.empty());

        var r = service.consumoTeorico(ID_DETALLE);

        assertThat(r.idDetallePlan()).isEqualTo(ID_DETALLE);
        assertThat(r.producto()).isEqualTo("Pan artesanal");
        assertThat(r.cantidadPlanificada()).isEqualByComparingTo("90.00");
        assertThat(r.recetaRinde()).isNull();
        assertThat(r.factor()).isNull();
        assertThat(r.ingredientes()).isEmpty();

        verifyNoInteractions(detalleRecetaRepository, materiaPrimaRepository);
    }

    @Test
    @DisplayName("una línea inexistente responde 404 DETALLE_PLAN_INEXISTENTE")
    void detalleInexistenteResponde404() {
        when(detallePlanRepository.buscarPorId(999)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.consumoTeorico(999))
                .isInstanceOf(DetallePlanInexistenteException.class)
                .hasFieldOrPropertyWithValue("codigo", "DETALLE_PLAN_INEXISTENTE")
                .hasFieldOrPropertyWithValue("status", HttpStatus.NOT_FOUND)
                .hasMessage("No se encontró la línea de detalle del plan de producción con id "
                        + "999.");

        verifyNoInteractions(recetaRepository, detalleRecetaRepository, materiaPrimaRepository);
    }

    @Test
    @DisplayName("una receta sin ingredientes y rendimiento no positivo falla sin dividir por cero")
    void recetaSinIngredientesYRendimientoCeroFalla() {
        when(detallePlanRepository.buscarPorId(ID_DETALLE))
                .thenReturn(Optional.of(linea("Pan francés", "90.00")));
        when(recetaRepository.buscarPorProducto(ID_PRODUCTO))
                .thenReturn(Optional.of(receta("0.000")));
        when(detalleRecetaRepository.listarPorReceta(ID_RECETA)).thenReturn(List.of());

        assertThatThrownBy(() -> service.consumoTeorico(ID_DETALLE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no tiene una cantidad a producir mayor que cero");
    }

    @Test
    @DisplayName("la consulta no invoca operaciones de escritura")
    void laConsultaNoEscribe() {
        when(detallePlanRepository.buscarPorId(ID_DETALLE))
                .thenReturn(Optional.of(linea("Pan francés", "90.00")));
        when(recetaRepository.buscarPorProducto(ID_PRODUCTO))
                .thenReturn(Optional.of(receta("30.000")));
        when(detalleRecetaRepository.listarPorReceta(ID_RECETA)).thenReturn(List.of(
                ingrediente(1, "Harina de trigo", "5.000", "kg")));
        var harinaDisponible = materiaPrima("112.50");
        when(materiaPrimaRepository.buscarConDetalle(1))
                .thenReturn(Optional.of(harinaDisponible));

        service.consumoTeorico(ID_DETALLE);

        verify(detallePlanRepository, never()).insertar(any());
        verify(recetaRepository, never()).insertar(any());
        verify(detalleRecetaRepository, never()).insertar(any());
        verify(materiaPrimaRepository, never()).save(any());
    }

    private static DetallePlanProduccion linea(String nombreProducto, String cantidadPlanificada) {
        var linea = new DetallePlanProduccion();
        linea.setIdDetallePlan(ID_DETALLE);
        linea.setIdPlan(9);
        linea.setIdProducto(ID_PRODUCTO);
        linea.setNombreProducto(nombreProducto);
        linea.setUnidad("und");
        linea.setCantidadPlanificada(new BigDecimal(cantidadPlanificada));
        linea.setCantidadProducida(new BigDecimal("0.00"));
        linea.setTieneReceta(true);
        return linea;
    }

    private static Receta receta(String cantidadProducir) {
        var receta = new Receta();
        receta.setIdReceta(ID_RECETA);
        receta.setIdProducto(ID_PRODUCTO);
        receta.setCantidadProducir(new BigDecimal(cantidadProducir));
        return receta;
    }

    private static DetalleReceta ingrediente(Integer idMateriaPrima, String nombre,
                                             String cantidad, String unidad) {
        var ingrediente = new DetalleReceta();
        ingrediente.setIdReceta(ID_RECETA);
        ingrediente.setIdMateriaPrima(idMateriaPrima);
        ingrediente.setNombreMateriaPrima(nombre);
        ingrediente.setCantidad(new BigDecimal(cantidad));
        ingrediente.setAbreviaturaUnidad(unidad);
        return ingrediente;
    }

    private static MateriaPrimaConDetalle materiaPrima(String disponible) {
        var materiaPrima = mock(MateriaPrimaConDetalle.class);
        when(materiaPrima.getCantidadDisponible()).thenReturn(new BigDecimal(disponible));
        return materiaPrima;
    }
}
