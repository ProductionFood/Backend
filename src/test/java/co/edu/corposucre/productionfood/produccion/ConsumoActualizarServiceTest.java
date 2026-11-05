package co.edu.corposucre.productionfood.produccion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import co.edu.corposucre.productionfood.common.error.ConflictoNegocioException;
import co.edu.corposucre.productionfood.common.error.ParametroInvalidoException;
import co.edu.corposucre.productionfood.common.error.RecursoNoEncontradoException;
import co.edu.corposucre.productionfood.inventario.AlertaService;
import co.edu.corposucre.productionfood.inventario.InventarioService;
import co.edu.corposucre.productionfood.inventario.SaldosInventario;
import co.edu.corposucre.productionfood.inventario.TipoMovimiento;
import co.edu.corposucre.productionfood.inventario.dto.AlertaStockResponse;
import co.edu.corposucre.productionfood.materiaprima.MateriaPrimaRepository;
import co.edu.corposucre.productionfood.produccion.dto.ActualizarConsumoRequest;
import co.edu.corposucre.productionfood.receta.DetalleRecetaRepository;
import co.edu.corposucre.productionfood.receta.RecetaRepository;

/**
 * Pruebas unitarias de la corrección de un consumo en {@link ConsumoService} (HU-22 PR4).
 *
 * <p>No levantan Spring ni tocan la base: los repositorios y {@link InventarioService} se
 * sustituyen por mocks y la calculadora de eficiencia es la instancia real, porque es cálculo
 * puro. Cubren el ajuste por diferencia al alza y a la baja (R-07), el redondeo de 3 a 2
 * decimales, la cantidad cero, el plan fuera de proceso y los rechazos de inventario.
 */
class ConsumoActualizarServiceTest {

    private static final Integer ID_CONSUMO = 31;
    private static final Integer ID_DETALLE = 21;
    private static final Integer ID_PLAN = 9;
    private static final Integer ID_MATERIA_PRIMA = 5;

    private static final LocalDateTime FECHA_REGISTRO =
            LocalDateTime.of(2026, 9, 18, 10, 22, 41);

    private DetallePlanRepository detallePlanRepository;
    private RecetaRepository recetaRepository;
    private DetalleRecetaRepository detalleRecetaRepository;
    private MateriaPrimaRepository materiaPrimaRepository;
    private ConsumoRepository consumoRepository;
    private PlanProduccionRepository planProduccionRepository;
    private InventarioService inventarioService;
    private AlertaService alertaService;
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
        alertaService = mock(AlertaService.class);
        service = new ConsumoService(detallePlanRepository, recetaRepository,
                detalleRecetaRepository, new CalculadoraConsumoService(), materiaPrimaRepository,
                consumoRepository, planProduccionRepository, inventarioService,
                new CalculadoraEficienciaService(), alertaService);
    }

    @Test
    @DisplayName("corregir al alza descuenta solo la diferencia y conserva la teórica guardada")
    void correccionAlAlzaAjustaPorDiferencia() {
        prepararCorreccion("15.000", "18.000", EstadoPlan.EN_PROCESO);
        when(inventarioService.registrarSalida(any(), any(), any(), any(), any(), any()))
                .thenReturn(new SaldosInventario(new BigDecimal("100.00"),
                        new BigDecimal("97.00")));

        var r = service.actualizar(ID_CONSUMO, new ActualizarConsumoRequest(
                new BigDecimal("18.000")));

        assertThat(r.idConsumo()).isEqualTo(ID_CONSUMO);
        assertThat(r.idDetallePlan()).isEqualTo(ID_DETALLE);
        assertThat(r.producto()).isEqualTo("Pan francés");
        assertThat(r.materiaPrima().idMateriaPrima()).isEqualTo(ID_MATERIA_PRIMA);
        assertThat(r.materiaPrima().nombre()).isEqualTo("Harina de trigo");
        assertThat(r.materiaPrima().unidad()).isEqualTo("kg");
        assertThat(r.cantidadTeorica()).isEqualByComparingTo("15.000");
        assertThat(r.cantidadTeorica().scale()).isEqualTo(3);
        assertThat(r.cantidadReal()).isEqualByComparingTo("18.000");
        assertThat(r.cantidadReal().scale()).isEqualTo(3);
        assertThat(r.eficiencia()).isEqualByComparingTo("83.33");
        assertThat(r.desviacionPorcentaje()).isEqualByComparingTo("20.00");
        assertThat(r.fueraDeReceta()).isFalse();
        assertThat(r.fechaRegistro()).isEqualTo(FECHA_REGISTRO);
        assertThat(r.inventario()).isNotNull();
        assertThat(r.inventario().anterior()).isEqualByComparingTo("100.00");
        assertThat(r.inventario().posterior()).isEqualByComparingTo("97.00");

        verify(inventarioService, times(1)).registrarSalida(eq(ID_MATERIA_PRIMA),
                argThat(cantidad -> cantidad.scale() == 2
                        && cantidad.compareTo(new BigDecimal("3.00")) == 0),
                eq(TipoMovimiento.SALIDA_PRODUCCION), eq("consumo_materia_prima"),
                eq(ID_CONSUMO), eq("Corrección al alza del consumo 31"));
        verify(inventarioService, never()).registrarEntrada(any(), any(), any(), any(), any(),
                any());
        verify(consumoRepository).actualizarReal(eq(ID_CONSUMO),
                argThat(cantidad -> cantidad.compareTo(new BigDecimal("18.000")) == 0));
    }

    @Test
    @DisplayName("corregir a la baja devuelve solo la diferencia al inventario")
    void correccionALaBajaAjustaPorDiferencia() {
        prepararCorreccion("15.000", "12.000", EstadoPlan.EN_PROCESO);
        when(inventarioService.registrarEntrada(any(), any(), any(), any(), any(), any()))
                .thenReturn(new SaldosInventario(new BigDecimal("100.00"),
                        new BigDecimal("103.00")));

        var r = service.actualizar(ID_CONSUMO, new ActualizarConsumoRequest(
                new BigDecimal("12.000")));

        assertThat(r.cantidadReal()).isEqualByComparingTo("12.000");
        assertThat(r.eficiencia()).isEqualByComparingTo("125.00");
        assertThat(r.desviacionPorcentaje()).isEqualByComparingTo("-20.00");
        assertThat(r.inventario().anterior()).isEqualByComparingTo("100.00");
        assertThat(r.inventario().posterior()).isEqualByComparingTo("103.00");

        verify(inventarioService, times(1)).registrarEntrada(eq(ID_MATERIA_PRIMA),
                argThat(cantidad -> cantidad.scale() == 2
                        && cantidad.compareTo(new BigDecimal("3.00")) == 0),
                eq(TipoMovimiento.AJUSTE_POSITIVO), eq("consumo_materia_prima"),
                eq(ID_CONSUMO), eq("Corrección a la baja del consumo 31"));
        verify(inventarioService, never()).registrarSalida(any(), any(), any(), any(), any(),
                any());
    }

    @Test
    @DisplayName("corregir al mismo valor no mueve inventario y actualiza igual")
    void correccionAlMismoValorNoMueveInventario() {
        prepararCorreccion("15.000", "15.000", EstadoPlan.EN_PROCESO);

        var r = service.actualizar(ID_CONSUMO, new ActualizarConsumoRequest(
                new BigDecimal("15.000")));

        assertThat(r.cantidadReal()).isEqualByComparingTo("15.000");
        assertThat(r.inventario()).isNull();

        verifyNoInteractions(inventarioService);
        verify(consumoRepository).actualizarReal(eq(ID_CONSUMO),
                argThat(cantidad -> cantidad.compareTo(new BigDecimal("15.000")) == 0));
    }

    @Test
    @DisplayName("una diferencia de 0.001 redondea a 0.00 y no mueve inventario")
    void diferenciaQueRedondeaACeroNoMueveInventario() {
        prepararCorreccion("15.000", "15.001", EstadoPlan.EN_PROCESO);

        service.actualizar(ID_CONSUMO, new ActualizarConsumoRequest(
                new BigDecimal("15.001")));

        verifyNoInteractions(inventarioService);
        verify(consumoRepository).actualizarReal(eq(ID_CONSUMO),
                argThat(cantidad -> cantidad.compareTo(new BigDecimal("15.001")) == 0));
    }

    @Test
    @DisplayName("una cantidad negativa responde 400 CANTIDAD_INVALIDA y no escribe nada")
    void cantidadNegativaResponde400() {
        when(consumoRepository.buscarPorIdParaActualizar(ID_CONSUMO))
                .thenReturn(Optional.of(consumo("15.000", EstadoPlan.EN_PROCESO)));

        assertThatThrownBy(() -> service.actualizar(ID_CONSUMO,
                new ActualizarConsumoRequest(new BigDecimal("-1.000"))))
                .isInstanceOf(ParametroInvalidoException.class)
                .hasFieldOrPropertyWithValue("codigo", "CANTIDAD_INVALIDA")
                .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST)
                .hasMessage("La cantidad real no puede ser negativa.");

        verifyNoInteractions(inventarioService);
        verify(consumoRepository, never()).actualizarReal(any(), any());
    }

    @Test
    @DisplayName("corregir de cero a cero deja la eficiencia nula y no mueve inventario")
    void cantidadCeroDejaEficienciaNula() {
        prepararCorreccion("0.000", "0.000", EstadoPlan.EN_PROCESO);

        var r = service.actualizar(ID_CONSUMO, new ActualizarConsumoRequest(
                new BigDecimal("0.000")));

        assertThat(r.cantidadReal()).isEqualByComparingTo("0.000");
        assertThat(r.eficiencia()).isNull();
        assertThat(r.desviacionPorcentaje()).isEqualByComparingTo("-100.00");
        assertThat(r.inventario()).isNull();

        verifyNoInteractions(inventarioService);
        verify(consumoRepository).actualizarReal(eq(ID_CONSUMO),
                argThat(cantidad -> cantidad.compareTo(new BigDecimal("0.000")) == 0));
    }

    @Test
    @DisplayName("corregir de 15 a cero devuelve toda la cantidad al inventario")
    void corregirACeroDevuelveTodo() {
        prepararCorreccion("15.000", "0.000", EstadoPlan.EN_PROCESO);
        when(inventarioService.registrarEntrada(any(), any(), any(), any(), any(), any()))
                .thenReturn(new SaldosInventario(new BigDecimal("96.30"),
                        new BigDecimal("111.30")));

        var r = service.actualizar(ID_CONSUMO, new ActualizarConsumoRequest(
                new BigDecimal("0.000")));

        assertThat(r.cantidadReal()).isEqualByComparingTo("0.000");
        assertThat(r.eficiencia()).isNull();
        assertThat(r.desviacionPorcentaje()).isEqualByComparingTo("-100.00");
        assertThat(r.inventario()).isNotNull();
        assertThat(r.inventario().anterior()).isEqualByComparingTo("96.30");
        assertThat(r.inventario().posterior()).isEqualByComparingTo("111.30");

        verify(inventarioService).registrarEntrada(eq(ID_MATERIA_PRIMA),
                argThat(cantidad -> cantidad.scale() == 2
                        && cantidad.compareTo(new BigDecimal("15.00")) == 0),
                eq(TipoMovimiento.AJUSTE_POSITIVO), eq("consumo_materia_prima"),
                eq(ID_CONSUMO), any());
        verify(inventarioService, never()).registrarSalida(any(), any(), any(), any(), any(),
                any());
    }

    @Test
    @DisplayName("un consumo inexistente responde 404 RECURSO_NO_ENCONTRADO")
    void consumoInexistenteResponde404() {
        when(consumoRepository.buscarPorIdParaActualizar(ID_CONSUMO))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.actualizar(ID_CONSUMO,
                new ActualizarConsumoRequest(new BigDecimal("18.000"))))
                .isInstanceOf(RecursoNoEncontradoException.class)
                .hasFieldOrPropertyWithValue("codigo", "RECURSO_NO_ENCONTRADO")
                .hasFieldOrPropertyWithValue("status", HttpStatus.NOT_FOUND);

        verifyNoInteractions(inventarioService);
        verify(consumoRepository, never()).actualizarReal(any(), any());
    }

    @Test
    @DisplayName("un plan completado responde 409 PLAN_NO_EN_PROCESO y no escribe nada")
    void planCompletadoResponde409() {
        verificarPlanNoEnProceso(EstadoPlan.COMPLETADO);
    }

    @Test
    @DisplayName("un plan planificado responde 409 PLAN_NO_EN_PROCESO y no escribe nada")
    void planPlanificadoResponde409() {
        verificarPlanNoEnProceso(EstadoPlan.PLANIFICADO);
    }

    @Test
    @DisplayName("un stock insuficiente al alza se propaga como 409 sin actualizar")
    void stockInsuficienteSePropaga() {
        prepararCorreccion("15.000", "18.000", EstadoPlan.EN_PROCESO);
        when(inventarioService.registrarSalida(any(), any(), any(), any(), any(), any()))
                .thenThrow(new ConflictoNegocioException("STOCK_INSUFICIENTE",
                        "El stock disponible no alcanza."));

        assertThatThrownBy(() -> service.actualizar(ID_CONSUMO,
                new ActualizarConsumoRequest(new BigDecimal("18.000"))))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "STOCK_INSUFICIENTE")
                .hasFieldOrPropertyWithValue("status", HttpStatus.CONFLICT);

        verify(consumoRepository, never()).actualizarReal(any(), any());
    }

    @Test
    @DisplayName("la falta de fila de inventario se traduce a STOCK_INSUFICIENTE")
    void faltaFilaDeInventarioSeTraduceAStockInsuficiente() {
        prepararCorreccion("15.000", "18.000", EstadoPlan.EN_PROCESO);
        when(inventarioService.registrarSalida(any(), any(), any(), any(), any(), any()))
                .thenThrow(new RecursoNoEncontradoException("inventario de la materia prima",
                        ID_MATERIA_PRIMA));

        assertThatThrownBy(() -> service.actualizar(ID_CONSUMO,
                new ActualizarConsumoRequest(new BigDecimal("18.000"))))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "STOCK_INSUFICIENTE")
                .hasFieldOrPropertyWithValue("status", HttpStatus.CONFLICT)
                .hasMessageContaining("No hay inventario disponible");

        verify(consumoRepository, never()).actualizarReal(any(), any());
    }

    @Test
    @DisplayName("el ajuste es por diferencia: una sola salida por el excedente, sin revertir")
    void ajustePorDiferenciaNoRevierteNiRedescuenta() {
        prepararCorreccion("15.000", "18.000", EstadoPlan.EN_PROCESO);
        when(inventarioService.registrarSalida(any(), any(), any(), any(), any(), any()))
                .thenReturn(new SaldosInventario(new BigDecimal("100.00"),
                        new BigDecimal("97.00")));

        service.actualizar(ID_CONSUMO, new ActualizarConsumoRequest(
                new BigDecimal("18.000")));

        verify(inventarioService, times(1)).registrarSalida(eq(ID_MATERIA_PRIMA),
                argThat(cantidad -> cantidad.compareTo(new BigDecimal("3.00")) == 0),
                eq(TipoMovimiento.SALIDA_PRODUCCION), eq("consumo_materia_prima"),
                eq(ID_CONSUMO), any());
        verify(inventarioService, never()).registrarEntrada(any(), any(), any(), any(), any(),
                any());
    }

    private void verificarPlanNoEnProceso(EstadoPlan estado) {
        when(consumoRepository.buscarPorIdParaActualizar(ID_CONSUMO))
                .thenReturn(Optional.of(consumo("15.000", estado)));

        assertThatThrownBy(() -> service.actualizar(ID_CONSUMO,
                new ActualizarConsumoRequest(new BigDecimal("18.000"))))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "PLAN_NO_EN_PROCESO")
                .hasFieldOrPropertyWithValue("status", HttpStatus.CONFLICT);

        verifyNoInteractions(inventarioService);
        verify(consumoRepository, never()).actualizarReal(any(), any());
    }

    @Test
    @DisplayName("CA-03: la corrección al alza que deja la materia prima BAJO trae la alerta")
    void correccionAlAlzaGeneraAlerta() {
        prepararCorreccion("15.000", "18.000", EstadoPlan.EN_PROCESO);
        when(inventarioService.registrarSalida(any(), any(), any(), any(), any(), any()))
                .thenReturn(new SaldosInventario(new BigDecimal("52.00"),
                        new BigDecimal("49.00")));
        when(alertaService.alertaDeMateriaPrima(ID_MATERIA_PRIMA))
                .thenReturn(Optional.of(alerta("BAJO", "49.00")));

        var r = service.actualizar(ID_CONSUMO, new ActualizarConsumoRequest(
                new BigDecimal("18.000")));

        assertThat(r.alertas()).singleElement().satisfies(a -> {
            assertThat(a.idMateriaPrima()).isEqualTo(ID_MATERIA_PRIMA);
            assertThat(a.nivel()).isEqualTo("BAJO");
        });
    }

    @Test
    @DisplayName("CA-03: la corrección al alza que agota la materia prima trae la alerta AGOTADO")
    void correccionAlAlzaQueAgotaTraeAgotado() {
        prepararCorreccion("15.000", "18.000", EstadoPlan.EN_PROCESO);
        when(inventarioService.registrarSalida(any(), any(), any(), any(), any(), any()))
                .thenReturn(new SaldosInventario(new BigDecimal("3.00"),
                        new BigDecimal("0.00")));
        when(alertaService.alertaDeMateriaPrima(ID_MATERIA_PRIMA))
                .thenReturn(Optional.of(alerta("AGOTADO", "0.00")));

        var r = service.actualizar(ID_CONSUMO, new ActualizarConsumoRequest(
                new BigDecimal("18.000")));

        assertThat(r.alertas()).singleElement()
                .satisfies(a -> assertThat(a.nivel()).isEqualTo("AGOTADO"));
    }

    @Test
    @DisplayName("CA-03: la corrección a la baja que sale de alerta devuelve alertas []")
    void correccionALaBajaQueSaleDeAlertaDevuelveVacio() {
        prepararCorreccion("15.000", "12.000", EstadoPlan.EN_PROCESO);
        when(inventarioService.registrarEntrada(any(), any(), any(), any(), any(), any()))
                .thenReturn(new SaldosInventario(new BigDecimal("47.00"),
                        new BigDecimal("50.00")));
        when(alertaService.alertaDeMateriaPrima(ID_MATERIA_PRIMA)).thenReturn(Optional.empty());

        var r = service.actualizar(ID_CONSUMO, new ActualizarConsumoRequest(
                new BigDecimal("12.000")));

        assertThat(r.alertas()).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("CA-03: sin diferencia (sin movimiento) no se consulta la alerta y alertas es []")
    void sinDiferenciaNoConsultaAlerta() {
        prepararCorreccion("15.000", "15.000", EstadoPlan.EN_PROCESO);

        var r = service.actualizar(ID_CONSUMO, new ActualizarConsumoRequest(
                new BigDecimal("15.000")));

        assertThat(r.alertas()).isNotNull().isEmpty();
        verifyNoInteractions(alertaService);
    }

    private static AlertaStockResponse alerta(String nivel, String disponible) {
        return new AlertaStockResponse(ID_MATERIA_PRIMA, "Harina de trigo", "kg",
                new BigDecimal(disponible), new BigDecimal("50.00"),
                new BigDecimal("50.00").subtract(new BigDecimal(disponible)), nivel,
                new BigDecimal("3200.00"), new BigDecimal("11840.00"), null);
    }

    private void prepararCorreccion(String cantidadAnterior, String cantidadNueva,
                                    EstadoPlan estado) {
        when(consumoRepository.buscarPorIdParaActualizar(ID_CONSUMO))
                .thenReturn(Optional.of(consumo(cantidadAnterior, estado)));
        when(consumoRepository.actualizarReal(eq(ID_CONSUMO), any())).thenReturn(1);
        when(consumoRepository.buscarPorId(ID_CONSUMO))
                .thenReturn(Optional.of(consumo(cantidadNueva, estado)));
    }

    private static ConsumoMateriaPrima consumo(String cantidadReal, EstadoPlan estado) {
        var consumo = new ConsumoMateriaPrima();
        consumo.setIdConsumo(ID_CONSUMO);
        consumo.setIdDetallePlan(ID_DETALLE);
        consumo.setIdPlan(ID_PLAN);
        consumo.setNombreProducto("Pan francés");
        consumo.setIdMateriaPrima(ID_MATERIA_PRIMA);
        consumo.setNombreMateriaPrima("Harina de trigo");
        consumo.setUnidad("kg");
        consumo.setCantidadTeorica(new BigDecimal("15.000"));
        consumo.setCantidadReal(new BigDecimal(cantidadReal));
        consumo.setFechaRegistro(FECHA_REGISTRO);
        consumo.setEstadoPlan(estado);
        return consumo;
    }
}
