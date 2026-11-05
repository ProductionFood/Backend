package co.edu.corposucre.productionfood.produccion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;

import co.edu.corposucre.productionfood.common.error.ConflictoNegocioException;
import co.edu.corposucre.productionfood.common.error.ParametroInvalidoException;
import co.edu.corposucre.productionfood.common.error.RecursoNoEncontradoException;
import co.edu.corposucre.productionfood.inventario.InventarioService;
import co.edu.corposucre.productionfood.inventario.SaldosInventario;
import co.edu.corposucre.productionfood.inventario.TipoMovimiento;
import co.edu.corposucre.productionfood.materiaprima.MateriaPrimaRepository;
import co.edu.corposucre.productionfood.produccion.dto.RegistrarConsumoRequest;
import co.edu.corposucre.productionfood.receta.DetalleReceta;
import co.edu.corposucre.productionfood.receta.DetalleRecetaRepository;
import co.edu.corposucre.productionfood.receta.Receta;
import co.edu.corposucre.productionfood.receta.RecetaRepository;

/**
 * Pruebas unitarias del registro de consumo real de {@link ConsumoService} (HU-22 PR3).
 *
 * <p>No levantan Spring ni tocan la base: los repositorios y {@link InventarioService} se
 * sustituyen por mocks y las dos calculadoras son instancias reales, porque son cálculo puro.
 * Cubren el camino feliz con su descuento de inventario, los redondeos de R-09, el consumo de cero
 * y el fuera de receta, y cada uno de los rechazos de la especificación §5.
 */
class ConsumoRegistrarServiceTest {

    private static final Integer ID_DETALLE = 21;
    private static final Integer ID_PLAN = 9;
    private static final Integer ID_PRODUCTO = 3;
    private static final Integer ID_RECETA = 8;
    private static final Integer ID_MATERIA_PRIMA = 5;
    private static final Integer ID_CONSUMO = 31;

    private static final String OBSERVACION = "Consumo real registrado en la línea 21";

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
    @DisplayName("registra el consumo, descuenta inventario y devuelve eficiencia y desviación")
    void registroValidoDescuentaInventario() {
        prepararRegistroValido();
        when(inventarioService.registrarSalida(any(), any(), any(), any(), any(), any()))
                .thenReturn(new SaldosInventario(new BigDecimal("112.50"),
                        new BigDecimal("96.30")));

        var r = service.registrar(ID_DETALLE, new RegistrarConsumoRequest(ID_MATERIA_PRIMA,
                new BigDecimal("16.200")));

        assertThat(r.idConsumo()).isEqualTo(ID_CONSUMO);
        assertThat(r.idDetallePlan()).isEqualTo(ID_DETALLE);
        assertThat(r.producto()).isEqualTo("Pan francés");
        assertThat(r.materiaPrima().idMateriaPrima()).isEqualTo(ID_MATERIA_PRIMA);
        assertThat(r.materiaPrima().nombre()).isEqualTo("Harina de trigo");
        assertThat(r.materiaPrima().unidad()).isEqualTo("kg");
        assertThat(r.cantidadTeorica()).isEqualByComparingTo("15.000");
        assertThat(r.cantidadTeorica().scale()).isEqualTo(3);
        assertThat(r.cantidadReal()).isEqualByComparingTo("16.200");
        assertThat(r.cantidadReal().scale()).isEqualTo(3);
        assertThat(r.eficiencia()).isEqualByComparingTo("92.59");
        assertThat(r.eficiencia().scale()).isEqualTo(2);
        assertThat(r.desviacionPorcentaje()).isEqualByComparingTo("8.00");
        assertThat(r.fueraDeReceta()).isFalse();
        assertThat(r.fechaRegistro()).isEqualTo(LocalDateTime.of(2026, 9, 18, 10, 22, 41));
        assertThat(r.inventario()).isNotNull();
        assertThat(r.inventario().anterior()).isEqualByComparingTo("112.50");
        assertThat(r.inventario().posterior()).isEqualByComparingTo("96.30");

        verify(inventarioService).registrarSalida(eq(ID_MATERIA_PRIMA),
                argThat(cantidad -> cantidad.scale() == 2
                        && cantidad.compareTo(new BigDecimal("16.20")) == 0),
                eq(TipoMovimiento.SALIDA_PRODUCCION), eq("consumo_materia_prima"),
                eq(ID_CONSUMO), eq(OBSERVACION));
    }

    @Test
    @DisplayName("redondea la cantidad de inventario de 3 a 2 decimales con HALF_UP")
    void redondeaCantidadDeInventario() {
        prepararRegistroValido();
        when(inventarioService.registrarSalida(any(), any(), any(), any(), any(), any()))
                .thenReturn(new SaldosInventario(new BigDecimal("112.50"),
                        new BigDecimal("96.29")));

        service.registrar(ID_DETALLE, new RegistrarConsumoRequest(ID_MATERIA_PRIMA,
                new BigDecimal("16.205")));

        verify(inventarioService).registrarSalida(eq(ID_MATERIA_PRIMA),
                argThat(cantidad -> cantidad.scale() == 2
                        && cantidad.compareTo(new BigDecimal("16.21")) == 0),
                eq(TipoMovimiento.SALIDA_PRODUCCION), eq("consumo_materia_prima"),
                eq(ID_CONSUMO), eq(OBSERVACION));
    }

    @Test
    @DisplayName("un consumo real de cero no mueve inventario y deja la eficiencia nula")
    void consumoRealCeroNoMueveInventario() {
        prepararRegistroValido();

        var r = service.registrar(ID_DETALLE, new RegistrarConsumoRequest(ID_MATERIA_PRIMA,
                new BigDecimal("0.000")));

        assertThat(r.cantidadReal()).isEqualByComparingTo("0.000");
        assertThat(r.eficiencia()).isNull();
        assertThat(r.desviacionPorcentaje()).isEqualByComparingTo("-100.00");
        assertThat(r.fueraDeReceta()).isFalse();
        assertThat(r.inventario()).isNull();

        verify(inventarioService, never()).registrarSalida(any(), any(), any(), any(), any(),
                any());
    }

    @Test
    @DisplayName("un consumo de 0.001 redondea a 0.00 en inventario y no lo descuenta")
    void consumoQueRedondeaACeroNoMueveInventario() {
        prepararRegistroValido();

        service.registrar(ID_DETALLE, new RegistrarConsumoRequest(ID_MATERIA_PRIMA,
                new BigDecimal("0.001")));

        verify(inventarioService, never()).registrarSalida(any(), any(), any(), any(), any(),
                any());
    }

    @Test
    @DisplayName("una materia prima fuera de la receta queda con teórico cero y fuera de receta")
    void materiaPrimaFueraDeReceta() {
        prepararRegistroValido();
        when(detalleRecetaRepository.listarPorReceta(ID_RECETA))
                .thenReturn(List.of(ingrediente(7, "Sal", "0.050")));
        when(inventarioService.registrarSalida(any(), any(), any(), any(), any(), any()))
                .thenReturn(new SaldosInventario(new BigDecimal("5.00"),
                        new BigDecimal("3.00")));

        var r = service.registrar(ID_DETALLE, new RegistrarConsumoRequest(ID_MATERIA_PRIMA,
                new BigDecimal("2.000")));

        assertThat(r.cantidadTeorica()).isEqualByComparingTo("0.000");
        assertThat(r.cantidadTeorica().scale()).isEqualTo(3);
        assertThat(r.fueraDeReceta()).isTrue();
        assertThat(r.eficiencia()).isEqualByComparingTo("0.00");
        assertThat(r.desviacionPorcentaje()).isNull();
    }

    @Test
    @DisplayName("un plan que no está en proceso responde 409 y no escribe nada")
    void planNoEnProcesoResponde409() {
        when(detallePlanRepository.buscarPorId(ID_DETALLE))
                .thenReturn(Optional.of(linea()));
        when(planProduccionRepository.buscarPorId(ID_PLAN))
                .thenReturn(Optional.of(plan(EstadoPlan.PLANIFICADO)));

        assertThatThrownBy(() -> service.registrar(ID_DETALLE,
                new RegistrarConsumoRequest(ID_MATERIA_PRIMA, new BigDecimal("16.200"))))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "PLAN_NO_EN_PROCESO")
                .hasFieldOrPropertyWithValue("status", HttpStatus.CONFLICT);

        verify(consumoRepository, never()).insertar(any());
        verifyNoInteractions(inventarioService);
    }

    @Test
    @DisplayName("una línea inexistente responde 404 DETALLE_PLAN_INEXISTENTE")
    void lineaInexistenteResponde404() {
        when(detallePlanRepository.buscarPorId(ID_DETALLE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.registrar(ID_DETALLE,
                new RegistrarConsumoRequest(ID_MATERIA_PRIMA, new BigDecimal("16.200"))))
                .isInstanceOf(DetallePlanInexistenteException.class)
                .hasFieldOrPropertyWithValue("codigo", "DETALLE_PLAN_INEXISTENTE")
                .hasFieldOrPropertyWithValue("status", HttpStatus.NOT_FOUND)
                .hasMessage("No se encontró la línea de detalle del plan de producción con id "
                        + "21.");

        verifyNoInteractions(planProduccionRepository, inventarioService);
    }

    @Test
    @DisplayName("una materia prima inexistente o inactiva responde 409")
    void materiaPrimaInexistenteResponde409() {
        prepararLineaYPlan();
        when(materiaPrimaRepository.existeActiva(ID_MATERIA_PRIMA)).thenReturn(false);

        assertThatThrownBy(() -> service.registrar(ID_DETALLE,
                new RegistrarConsumoRequest(ID_MATERIA_PRIMA, new BigDecimal("16.200"))))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "MATERIA_PRIMA_INEXISTENTE")
                .hasFieldOrPropertyWithValue("status", HttpStatus.CONFLICT);

        verify(consumoRepository, never()).insertar(any());
        verifyNoInteractions(inventarioService);
    }

    @Test
    @DisplayName("un consumo ya registrado responde 409 CONSUMO_DUPLICADO")
    void consumoDuplicadoResponde409() {
        prepararLineaYPlan();
        when(materiaPrimaRepository.existeActiva(ID_MATERIA_PRIMA)).thenReturn(true);
        when(consumoRepository.existeConsumo(ID_DETALLE, ID_MATERIA_PRIMA)).thenReturn(true);

        assertThatThrownBy(() -> service.registrar(ID_DETALLE,
                new RegistrarConsumoRequest(ID_MATERIA_PRIMA, new BigDecimal("16.200"))))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "CONSUMO_DUPLICADO")
                .hasFieldOrPropertyWithValue("status", HttpStatus.CONFLICT);

        verify(consumoRepository, never()).insertar(any());
        verifyNoInteractions(inventarioService);
    }

    @Test
    @DisplayName("una cantidad real negativa responde 400 CANTIDAD_INVALIDA")
    void cantidadRealNegativaResponde400() {
        prepararLineaYPlan();

        assertThatThrownBy(() -> service.registrar(ID_DETALLE,
                new RegistrarConsumoRequest(ID_MATERIA_PRIMA, new BigDecimal("-1.000"))))
                .isInstanceOf(ParametroInvalidoException.class)
                .hasFieldOrPropertyWithValue("codigo", "CANTIDAD_INVALIDA")
                .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST)
                .hasMessage("La cantidad real no puede ser negativa.");

        verifyNoInteractions(materiaPrimaRepository, consumoRepository, inventarioService);
    }

    @Test
    @DisplayName("un stock insuficiente del servicio de inventario se propaga como 409")
    void stockInsuficienteSePropaga() {
        prepararRegistroValido();
        when(inventarioService.registrarSalida(any(), any(), any(), any(), any(), any()))
                .thenThrow(new ConflictoNegocioException("STOCK_INSUFICIENTE",
                        "El stock disponible no alcanza."));

        assertThatThrownBy(() -> service.registrar(ID_DETALLE,
                new RegistrarConsumoRequest(ID_MATERIA_PRIMA, new BigDecimal("16.200"))))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "STOCK_INSUFICIENTE")
                .hasFieldOrPropertyWithValue("status", HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("la falta de fila de inventario se traduce a STOCK_INSUFICIENTE")
    void faltaFilaDeInventarioSeTraduceAStockInsuficiente() {
        prepararRegistroValido();
        when(inventarioService.registrarSalida(any(), any(), any(), any(), any(), any()))
                .thenThrow(new RecursoNoEncontradoException("inventario de la materia prima",
                        ID_MATERIA_PRIMA));

        assertThatThrownBy(() -> service.registrar(ID_DETALLE,
                new RegistrarConsumoRequest(ID_MATERIA_PRIMA, new BigDecimal("16.200"))))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "STOCK_INSUFICIENTE")
                .hasFieldOrPropertyWithValue("status", HttpStatus.CONFLICT)
                .hasMessageContaining("No hay inventario suficiente");
    }

    @Test
    @DisplayName("una violación del UNIQUE de consumo se traduce a CONSUMO_DUPLICADO")
    void duplicateKeySeTraduceAConsumoDuplicado() {
        prepararLineaYPlan();
        when(materiaPrimaRepository.existeActiva(ID_MATERIA_PRIMA)).thenReturn(true);
        when(consumoRepository.existeConsumo(ID_DETALLE, ID_MATERIA_PRIMA)).thenReturn(false);
        when(recetaRepository.buscarPorProducto(ID_PRODUCTO)).thenReturn(Optional.of(receta()));
        when(detalleRecetaRepository.listarPorReceta(ID_RECETA))
                .thenReturn(List.of(ingrediente(ID_MATERIA_PRIMA, "Harina de trigo", "5.000")));
        when(consumoRepository.insertar(any()))
                .thenThrow(new DuplicateKeyException("uk_consumo_mp"));

        assertThatThrownBy(() -> service.registrar(ID_DETALLE,
                new RegistrarConsumoRequest(ID_MATERIA_PRIMA, new BigDecimal("16.200"))))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "CONSUMO_DUPLICADO")
                .hasFieldOrPropertyWithValue("status", HttpStatus.CONFLICT);

        verifyNoInteractions(inventarioService);
    }

    private void prepararLineaYPlan() {
        when(detallePlanRepository.buscarPorId(ID_DETALLE))
                .thenReturn(Optional.of(linea()));
        when(planProduccionRepository.buscarPorId(ID_PLAN))
                .thenReturn(Optional.of(plan(EstadoPlan.EN_PROCESO)));
    }

    private void prepararRegistroValido() {
        prepararLineaYPlan();
        when(materiaPrimaRepository.existeActiva(ID_MATERIA_PRIMA)).thenReturn(true);
        when(consumoRepository.existeConsumo(ID_DETALLE, ID_MATERIA_PRIMA)).thenReturn(false);
        when(recetaRepository.buscarPorProducto(ID_PRODUCTO)).thenReturn(Optional.of(receta()));
        when(detalleRecetaRepository.listarPorReceta(ID_RECETA))
                .thenReturn(List.of(ingrediente(ID_MATERIA_PRIMA, "Harina de trigo", "5.000")));
        when(consumoRepository.insertar(any())).thenReturn(ID_CONSUMO);
        when(consumoRepository.buscarPorId(ID_CONSUMO))
                .thenReturn(Optional.of(consumoGuardado()));
    }

    private static DetallePlanProduccion linea() {
        var linea = new DetallePlanProduccion();
        linea.setIdDetallePlan(ID_DETALLE);
        linea.setIdPlan(ID_PLAN);
        linea.setIdProducto(ID_PRODUCTO);
        linea.setNombreProducto("Pan francés");
        linea.setUnidad("und");
        linea.setCantidadPlanificada(new BigDecimal("90.00"));
        linea.setCantidadProducida(new BigDecimal("0.00"));
        linea.setTieneReceta(true);
        return linea;
    }

    private static PlanProduccion plan(EstadoPlan estado) {
        var plan = new PlanProduccion();
        plan.setIdPlan(ID_PLAN);
        plan.setEstado(estado);
        return plan;
    }

    private static Receta receta() {
        var receta = new Receta();
        receta.setIdReceta(ID_RECETA);
        receta.setIdProducto(ID_PRODUCTO);
        receta.setCantidadProducir(new BigDecimal("30.000"));
        return receta;
    }

    private static DetalleReceta ingrediente(Integer idMateriaPrima, String nombre,
                                             String cantidad) {
        var ingrediente = new DetalleReceta();
        ingrediente.setIdReceta(ID_RECETA);
        ingrediente.setIdMateriaPrima(idMateriaPrima);
        ingrediente.setNombreMateriaPrima(nombre);
        ingrediente.setCantidad(new BigDecimal(cantidad));
        ingrediente.setAbreviaturaUnidad("kg");
        return ingrediente;
    }

    private static ConsumoMateriaPrima consumoGuardado() {
        var consumo = new ConsumoMateriaPrima();
        consumo.setIdConsumo(ID_CONSUMO);
        consumo.setIdDetallePlan(ID_DETALLE);
        consumo.setIdPlan(ID_PLAN);
        consumo.setNombreProducto("Pan francés");
        consumo.setIdMateriaPrima(ID_MATERIA_PRIMA);
        consumo.setNombreMateriaPrima("Harina de trigo");
        consumo.setUnidad("kg");
        consumo.setCantidadTeorica(new BigDecimal("15.000"));
        consumo.setCantidadReal(new BigDecimal("16.200"));
        consumo.setFechaRegistro(LocalDateTime.of(2026, 9, 18, 10, 22, 41));
        consumo.setEstadoPlan(EstadoPlan.EN_PROCESO);
        return consumo;
    }
}
