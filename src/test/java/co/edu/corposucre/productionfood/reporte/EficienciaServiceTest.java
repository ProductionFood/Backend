package co.edu.corposucre.productionfood.reporte;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import co.edu.corposucre.productionfood.common.error.ApiException;
import co.edu.corposucre.productionfood.common.error.ParametroInvalidoException;
import co.edu.corposucre.productionfood.produccion.CalculadoraEficienciaService;
import co.edu.corposucre.productionfood.produccion.PlanInexistenteException;
import co.edu.corposucre.productionfood.reporte.dto.EficienciaPlanResponse;

/**
 * Pruebas unitarias de {@link EficienciaService} (HU-26) con el repositorio simulado y la
 * calculadora real, para que las fórmulas verificadas sean las del registro de consumos.
 */
class EficienciaServiceTest {

    private static final LocalDate DESDE = LocalDate.of(2026, 9, 1);
    private static final LocalDate HASTA = LocalDate.of(2026, 9, 30);

    private EficienciaRepository repository;
    private EficienciaService service;

    @BeforeEach
    void preparar() {
        repository = mock(EficienciaRepository.class);
        service = new EficienciaService(repository, new CalculadoraEficienciaService());
    }

    private static BigDecimal bd(String valor) {
        return new BigDecimal(valor);
    }

    private static PlanEficienciaFila plan(int id, String estado) {
        return new PlanEficienciaFila(id, LocalDate.of(2026, 9, 18), estado, "Ismael Batalla");
    }

    private static ProductoPlanFila producto(int idPlan, String planificada, String producida) {
        return new ProductoPlanFila(idPlan, "Pan francés", bd(planificada), bd(producida));
    }

    private static ConsumoEficienciaFila consumo(int idPlan, String teorica, String real,
                                                 String planificada, String producida) {
        return new ConsumoEficienciaFila(idPlan, "Harina de trigo", "kg", bd(teorica), bd(real),
                bd(planificada), bd(producida));
    }

    private static ConsumoEficienciaFila consumo(int idPlan, String teorica, String real) {
        return consumo(idPlan, teorica, real, "90.00", "90.00");
    }

    private void siembra(List<PlanEficienciaFila> planes, List<ProductoPlanFila> productos,
                         List<ConsumoEficienciaFila> consumos) {
        when(repository.planesConConsumo(DESDE, HASTA)).thenReturn(planes);
        when(repository.productosDePlanes(any())).thenReturn(productos);
        when(repository.consumosDePlanes(any())).thenReturn(consumos);
    }

    private EficienciaPlanResponse unicoPlan(List<ProductoPlanFila> productos,
                                             List<ConsumoEficienciaFila> consumos) {
        siembra(List.of(plan(9, "COMPLETADO")), productos, consumos);
        var respuesta = service.reporte(DESDE, HASTA);
        assertThat(respuesta.planes()).hasSize(1);
        return respuesta.planes().get(0);
    }

    private static MateriaPrimaAcumuladaFila acumulada(String nombre, String teorica, String real) {
        return new MateriaPrimaAcumuladaFila(nombre, "kg", bd(teorica), bd(real), 12);
    }

    @Test
    @DisplayName("CP-01 cumplimiento 66.67 con 60 de 90")
    void cumplimientoParcial() {
        var p = unicoPlan(List.of(producto(9, "90.00", "60.00")), List.of());

        var linea = p.productos().get(0);
        assertThat(linea.planificada()).isEqualByComparingTo("90.00");
        assertThat(linea.producida()).isEqualByComparingTo("60.00");
        assertThat(linea.cumplimiento()).isEqualTo(bd("66.67"));
        assertThat(p.cumplimiento()).isEqualTo(bd("66.67"));
    }

    @Test
    @DisplayName("CP-02 cumplimiento 100.00")
    void cumplimientoCompleto() {
        var p = unicoPlan(List.of(producto(9, "30.00", "30.00")), List.of());
        assertThat(p.productos().get(0).cumplimiento()).isEqualTo(bd("100.00"));
    }

    @Test
    @DisplayName("CP-03 producción por encima del plan da 111.11")
    void cumplimientoSobreElPlan() {
        var p = unicoPlan(List.of(producto(9, "90.00", "100.00")), List.of());
        assertThat(p.productos().get(0).cumplimiento()).isEqualTo(bd("111.11"));
    }

    @Test
    @DisplayName("CP-04 sin producción el cumplimiento es 0.00, no null")
    void cumplimientoCero() {
        var p = unicoPlan(List.of(producto(9, "90.00", "0.00")), List.of());
        assertThat(p.productos().get(0).cumplimiento()).isEqualTo(bd("0.00"));
        assertThat(p.cumplimiento()).isEqualTo(bd("0.00"));
    }

    @Test
    @DisplayName("Ajustado no pierde precisión con cantidades pequeñas (planificado 3, producido 3.33)")
    void ajustadoConCantidadesPequenas() {
        var p = unicoPlan(List.of(producto(9, "3.00", "3.33")),
                List.of(consumo(9, "0.003", "0.003", "3.00", "3.33")));

        assertThat(p.consumos().get(0).rendimientoAjustado()).isEqualTo(bd("111.00"));
    }

    @Test
    @DisplayName("CP-05 teórica 15 y real 16,2 dan desviación 8.00 y eficiencia 92.59")
    void desviacionYEficiencia() {
        var p = unicoPlan(List.of(producto(9, "90.00", "90.00")),
                List.of(consumo(9, "15.000", "16.200")));

        var c = p.consumos().get(0);
        assertThat(c.materiaPrima()).isEqualTo("Harina de trigo");
        assertThat(c.unidad()).isEqualTo("kg");
        assertThat(c.teorica()).isEqualTo(bd("15.000"));
        assertThat(c.real()).isEqualTo(bd("16.200"));
        assertThat(c.desviacion()).isEqualTo(bd("8.00"));
        assertThat(c.eficiencia()).isEqualTo(bd("92.59"));
        assertThat(p.rendimiento()).isEqualTo(bd("92.59"));
    }

    @Test
    @DisplayName("CP-06 real 0: eficiencia y ajustado null, no 0 ni error")
    void realCero() {
        var p = unicoPlan(List.of(producto(9, "90.00", "60.00")),
                List.of(consumo(9, "15.000", "0.000", "90.00", "60.00")));

        var c = p.consumos().get(0);
        assertThat(c.eficiencia()).isNull();
        assertThat(c.rendimientoAjustado()).isNull();
        assertThat(c.desviacion()).isEqualTo(bd("-100.00"));
        assertThat(p.rendimiento()).isNull();
        assertThat(p.rendimientoAjustado()).isNull();
    }

    @Test
    @DisplayName("CP-07 teórica 0: desviación null")
    void teoricaCero() {
        var p = unicoPlan(List.of(producto(9, "90.00", "90.00")),
                List.of(consumo(9, "0.000", "2.000")));

        var c = p.consumos().get(0);
        assertThat(c.desviacion()).isNull();
        assertThat(c.eficiencia()).isEqualTo(bd("0.00"));
    }

    @Test
    @DisplayName("CP-08 92, 100 y null promedian 96.00 y cuentan 1 sin rendimiento (no 64)")
    void nulosNoSePromedianComoCero() {
        siembra(List.of(plan(9, "COMPLETADO")), List.of(producto(9, "90.00", "90.00")),
                List.of(consumo(9, "92.000", "100.000"), consumo(9, "10.000", "10.000"),
                        consumo(9, "15.000", "0.000")));

        var resumen = service.reporte(DESDE, HASTA).resumen();

        assertThat(resumen.rendimientoPromedio()).isEqualTo(bd("96.00"));
        assertThat(resumen.rendimientoAjustadoPromedio()).isEqualTo(bd("96.00"));
        assertThat(resumen.registrosSinRendimiento()).isEqualTo(1);
    }

    @Test
    @DisplayName("Un consumo con real 0 no baja el promedio general")
    void realCeroNoBajaElPromedio() {
        siembra(List.of(plan(9, "COMPLETADO")), List.of(producto(9, "90.00", "90.00")),
                List.of(consumo(9, "10.000", "10.000"), consumo(9, "10.000", "0.000")));
        var con = service.reporte(DESDE, HASTA);

        siembra(List.of(plan(9, "COMPLETADO")), List.of(producto(9, "90.00", "90.00")),
                List.of(consumo(9, "10.000", "10.000")));
        var sin = service.reporte(DESDE, HASTA);

        assertThat(con.resumen().rendimientoPromedio()).isEqualTo(bd("100.00"));
        assertThat(con.resumen().rendimientoPromedio()).isEqualTo(sin.resumen().rendimientoPromedio());
        assertThat(con.resumen().registrosSinRendimiento()).isEqualTo(1);
        assertThat(sin.resumen().registrosSinRendimiento()).isZero();
        assertThat(con.planes().get(0).rendimiento()).isEqualTo(bd("100.00"));
    }

    @Test
    @DisplayName("CP-09 y CP-10 teórica 15 para 90, producido 60, real 10: 150.00 y ajustado 100.00")
    void rendimientoAjustado() {
        var p = unicoPlan(List.of(producto(9, "90.00", "60.00")),
                List.of(consumo(9, "15.000", "10.000", "90.00", "60.00")));

        var c = p.consumos().get(0);
        assertThat(c.eficiencia()).isEqualTo(bd("150.00"));
        assertThat(c.rendimientoAjustado()).isEqualTo(bd("100.00"));
        assertThat(p.rendimiento()).isEqualTo(bd("150.00"));
        assertThat(p.rendimientoAjustado()).isEqualTo(bd("100.00"));
    }

    @Test
    @DisplayName("CP-11 cumplimiento y rendimiento son campos distintos y no se mezclan")
    void metricasSeparadas() {
        var p = unicoPlan(List.of(producto(9, "90.00", "60.00")),
                List.of(consumo(9, "15.000", "16.200", "90.00", "60.00")));

        assertThat(p.cumplimiento()).isEqualTo(bd("66.67"));
        assertThat(p.rendimiento()).isEqualTo(bd("92.59"));
        assertThat(p.rendimientoAjustado()).isEqualTo(bd("61.73"));
        var resumen = service.reporte(DESDE, HASTA).resumen();
        assertThat(resumen.cumplimientoPromedio()).isEqualTo(bd("66.67"));
        assertThat(resumen.rendimientoPromedio()).isEqualTo(bd("92.59"));
    }

    @Test
    @DisplayName("El período y el plan se informan tal como llegan")
    void periodoSeInforma() {
        siembra(List.of(), List.of(), List.of());
        var respuesta = service.reporte(DESDE, HASTA);
        assertThat(respuesta.periodo().desde()).isEqualTo(DESDE);
        assertThat(respuesta.periodo().hasta()).isEqualTo(HASTA);

        when(repository.planesConConsumo(null, null)).thenReturn(List.of());
        var abierto = service.reporte(null, null);
        assertThat(abierto.periodo().desde()).isNull();
        assertThat(abierto.periodo().hasta()).isNull();
    }

    @Test
    @DisplayName("CP-14 rango invertido responde RANGO_FECHAS_INVALIDO sin tocar el repositorio")
    void rangoInvertido() {
        assertThatThrownBy(() -> service.reporte(HASTA, DESDE))
                .isInstanceOf(ParametroInvalidoException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCodigo())
                        .isEqualTo("RANGO_FECHAS_INVALIDO"));
        assertThatThrownBy(() -> service.materiasPrimas(HASTA, DESDE))
                .isInstanceOf(ParametroInvalidoException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCodigo())
                        .isEqualTo("RANGO_FECHAS_INVALIDO"));
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("CP-16 los cancelados se cuentan y quedan fuera de los promedios")
    void canceladosFueraDelPromedio() {
        siembra(List.of(plan(1, "COMPLETADO"), plan(2, "CANCELADO"), plan(3, "CANCELADO")),
                List.of(producto(1, "30.00", "30.00"), producto(2, "30.00", "0.00"),
                        producto(3, "30.00", "0.00")),
                List.of(consumo(1, "10.000", "10.000"), consumo(2, "10.000", "20.000"),
                        consumo(3, "10.000", "0.000")));

        var respuesta = service.reporte(DESDE, HASTA);
        var resumen = respuesta.resumen();

        assertThat(resumen.planesAnalizados()).isEqualTo(1);
        assertThat(resumen.planesCancelados()).isEqualTo(2);
        assertThat(resumen.cumplimientoPromedio()).isEqualTo(bd("100.00"));
        assertThat(resumen.rendimientoPromedio()).isEqualTo(bd("100.00"));
        assertThat(resumen.registrosSinRendimiento()).isZero();
        assertThat(respuesta.planes()).hasSize(3);
        assertThat(respuesta.planes().get(1).estado()).isEqualTo("CANCELADO");
        assertThat(respuesta.planes().get(1).cumplimiento()).isEqualTo(bd("0.00"));
    }

    @Test
    @DisplayName("El resumen promedia el cumplimiento de los planes, no el de los productos")
    void cumplimientoPromedioPorPlan() {
        siembra(List.of(plan(1, "COMPLETADO"), plan(2, "EN_PROCESO")),
                List.of(producto(1, "30.00", "30.00"), producto(2, "90.00", "60.00"),
                        producto(2, "10.00", "10.00")),
                List.of());

        var respuesta = service.reporte(DESDE, HASTA);

        // plan 2 = (66.67 + 100.00) / 2 = 83.34 (media de productos); plan 1 = 100.00
        assertThat(respuesta.planes().get(1).cumplimiento()).isEqualTo(bd("83.34"));
        assertThat(respuesta.resumen().cumplimientoPromedio()).isEqualTo(bd("91.67"));
    }

    @Test
    @DisplayName("CP-17 harina 180 teórica y 192,6 real: desviación 7.00 y CONSUMO_EXCESIVO")
    void materiaPrimaExcesiva() {
        when(repository.materiasPrimasAcumuladas(DESDE, HASTA))
                .thenReturn(List.of(acumulada("Harina de trigo", "180.000", "192.600")));

        var lista = service.materiasPrimas(DESDE, HASTA);

        assertThat(lista).hasSize(1);
        var m = lista.get(0);
        assertThat(m.materiaPrima()).isEqualTo("Harina de trigo");
        assertThat(m.unidad()).isEqualTo("kg");
        assertThat(m.teoricaAcumulada()).isEqualTo(bd("180.000"));
        assertThat(m.realAcumulada()).isEqualTo(bd("192.600"));
        assertThat(m.desviacion()).isEqualTo(bd("7.00"));
        assertThat(m.planes()).isEqualTo(12);
        assertThat(m.tendencia()).isEqualTo("CONSUMO_EXCESIVO");
    }

    @Test
    @DisplayName("CP-18 teórica 1,8 y real 1,795: -0.28 y NORMAL")
    void materiaPrimaNormal() {
        when(repository.materiasPrimasAcumuladas(DESDE, HASTA))
                .thenReturn(List.of(acumulada("Levadura", "1.800", "1.795")));

        var m = service.materiasPrimas(DESDE, HASTA).get(0);

        assertThat(m.desviacion()).isEqualTo(bd("-0.28"));
        assertThat(m.tendencia()).isEqualTo("NORMAL");
    }

    @Test
    @DisplayName("CP-18 bordes: +-5.00 son NORMAL y +-5.01 no; teórica 0 es SIN_BASE")
    void bordesDeLaTendencia() {
        when(repository.materiasPrimasAcumuladas(DESDE, HASTA)).thenReturn(List.of(
                acumulada("A", "100.000", "105.000"), acumulada("B", "100.000", "105.010"),
                acumulada("C", "100.000", "95.000"), acumulada("D", "100.000", "94.990"),
                acumulada("E", "0.000", "3.000")));

        var lista = service.materiasPrimas(DESDE, HASTA);

        assertThat(lista).extracting("materiaPrima", "tendencia").containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple("A", "NORMAL"),
                org.assertj.core.groups.Tuple.tuple("B", "CONSUMO_EXCESIVO"),
                org.assertj.core.groups.Tuple.tuple("C", "NORMAL"),
                org.assertj.core.groups.Tuple.tuple("D", "CONSUMO_REDUCIDO"),
                org.assertj.core.groups.Tuple.tuple("E", "SIN_BASE"));
        assertThat(lista.stream().filter(m -> m.materiaPrima().equals("E")).findFirst().get()
                .desviacion()).isNull();
    }

    @Test
    @DisplayName("Las materias primas van por |desviación| descendente, sin base al final, luego nombre")
    void ordenDeMateriasPrimas() {
        when(repository.materiasPrimasAcumuladas(DESDE, HASTA)).thenReturn(List.of(
                acumulada("Azúcar", "60.000", "57.500"), acumulada("Sal", "0.000", "1.000"),
                acumulada("Harina", "100.000", "107.000"), acumulada("Cacao", "100.000", "93.000"),
                acumulada("Agua", "100.000", "100.000")));

        var nombres = service.materiasPrimas(DESDE, HASTA).stream()
                .map(m -> m.materiaPrima()).toList();

        // |7.00| harina y cacao empatan y se ordenan por nombre; luego |4.17|, 0.00 y sin base
        assertThat(nombres).containsExactly("Cacao", "Harina", "Azúcar", "Agua", "Sal");
    }

    @Test
    @DisplayName("desviacionMateriaPrima del resumen es la media de la misma agregación, ignorando null")
    void resumenUsaLaMismaAgregacion() {
        siembra(List.of(), List.of(), List.of());
        when(repository.materiasPrimasAcumuladas(DESDE, HASTA)).thenReturn(List.of(
                acumulada("Harina", "100.000", "108.000"), acumulada("Sal", "100.000", "104.000"),
                acumulada("Fuera de receta", "0.000", "1.000")));

        var resumen = service.reporte(DESDE, HASTA).resumen();
        var lista = service.materiasPrimas(DESDE, HASTA);

        assertThat(lista).extracting("desviacion").contains(bd("8.00"), bd("4.00"), null);
        assertThat(resumen.desviacionMateriaPrima()).isEqualTo(bd("6.00"));
    }

    @Test
    @DisplayName("CP-19 los productos sin receta se listan con su nota")
    void sinReceta() {
        siembra(List.of(), List.of(), List.of());
        when(repository.productosSinReceta(DESDE, HASTA))
                .thenReturn(List.of(new SinRecetaFila("Pan integral", 2)));

        var lista = service.reporte(DESDE, HASTA).sinReceta();

        assertThat(lista).hasSize(1);
        assertThat(lista.get(0).producto()).isEqualTo("Pan integral");
        assertThat(lista.get(0).planes()).isEqualTo(2);
        assertThat(lista.get(0).nota()).isEqualTo("Sin receta: no se puede calcular consumo teórico");
    }

    @Test
    @DisplayName("CP-20 período vacío: contadores en 0, promedios null y sin error")
    void periodoVacio() {
        siembra(List.of(), List.of(), List.of());

        var respuesta = service.reporte(DESDE, HASTA);
        var resumen = respuesta.resumen();

        assertThat(respuesta.planes()).isEmpty();
        assertThat(respuesta.sinReceta()).isEmpty();
        assertThat(resumen.planesAnalizados()).isZero();
        assertThat(resumen.planesCancelados()).isZero();
        assertThat(resumen.registrosSinRendimiento()).isZero();
        assertThat(resumen.cumplimientoPromedio()).isNull();
        assertThat(resumen.rendimientoPromedio()).isNull();
        assertThat(resumen.rendimientoAjustadoPromedio()).isNull();
        assertThat(resumen.desviacionMateriaPrima()).isNull();
        assertThat(service.materiasPrimas(DESDE, HASTA)).isEmpty();
    }

    @Test
    @DisplayName("CP-21 el detalle de un plan trae sus productos y consumos")
    void detalleDePlan() {
        when(repository.plan(9)).thenReturn(Optional.of(plan(9, "COMPLETADO")));
        when(repository.consumosDePlanes(List.of(9)))
                .thenReturn(List.of(consumo(9, "15.000", "16.200", "90.00", "60.00")));
        when(repository.productosDePlanes(List.of(9)))
                .thenReturn(List.of(producto(9, "90.00", "60.00")));

        var p = service.detallePlan(9);

        assertThat(p.idPlan()).isEqualTo(9);
        assertThat(p.estado()).isEqualTo("COMPLETADO");
        assertThat(p.responsable()).isEqualTo("Ismael Batalla");
        assertThat(p.cumplimiento()).isEqualTo(bd("66.67"));
        assertThat(p.rendimiento()).isEqualTo(bd("92.59"));
        assertThat(p.rendimientoAjustado()).isEqualTo(bd("61.73"));
        assertThat(p.productos()).hasSize(1);
        assertThat(p.consumos()).hasSize(1);
    }

    @Test
    @DisplayName("CP-22 plan sin consumos responde PLAN_SIN_CONSUMOS (404)")
    void planSinConsumos() {
        when(repository.plan(7)).thenReturn(Optional.of(plan(7, "PLANIFICADO")));
        when(repository.consumosDePlanes(List.of(7))).thenReturn(List.of());

        assertThatThrownBy(() -> service.detallePlan(7))
                .isInstanceOf(PlanSinConsumosException.class)
                .satisfies(e -> {
                    assertThat(((ApiException) e).getCodigo()).isEqualTo("PLAN_SIN_CONSUMOS");
                    assertThat(((ApiException) e).getStatus().value()).isEqualTo(404);
                });
    }

    @Test
    @DisplayName("CP-22 plan inexistente responde PLAN_INEXISTENTE (404)")
    void planInexistente() {
        when(repository.plan(99)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.detallePlan(99))
                .isInstanceOf(PlanInexistenteException.class)
                .satisfies(e -> {
                    assertThat(((ApiException) e).getCodigo()).isEqualTo("PLAN_INEXISTENTE");
                    assertThat(((ApiException) e).getStatus().value()).isEqualTo(404);
                });
    }
}
