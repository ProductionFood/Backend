package co.edu.corposucre.productionfood.reporte;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.corposucre.productionfood.common.error.ParametroInvalidoException;
import co.edu.corposucre.productionfood.produccion.CalculadoraEficienciaService;
import co.edu.corposucre.productionfood.produccion.EstadoPlan;
import co.edu.corposucre.productionfood.produccion.PlanInexistenteException;
import co.edu.corposucre.productionfood.reporte.dto.DesviacionMateriaPrimaResponse;
import co.edu.corposucre.productionfood.reporte.dto.EficienciaPlanResponse;
import co.edu.corposucre.productionfood.reporte.dto.EficienciaResponse;
import co.edu.corposucre.productionfood.reporte.dto.PeriodoResponse;
import co.edu.corposucre.productionfood.reporte.dto.ResumenEficienciaResponse;
import co.edu.corposucre.productionfood.reporte.dto.SinRecetaResponse;

/**
 * Reporte de eficiencia de producción (HU-26). Solo lee.
 *
 * <p>Mide tres cosas por separado y nunca las mezcla (R-01): el cumplimiento del plan
 * ({@code producido / planificado * 100}), el rendimiento de materia prima y el rendimiento
 * ajustado (R-02). Estos dos últimos y la desviación se calculan siempre con
 * {@link CalculadoraEficienciaService}; aquí no se repite ninguna de sus fórmulas. El ajustado es
 * el rendimiento de un teórico escalado a lo producido ({@code teórica * producida /
 * planificada}) frente al real.
 *
 * <p>Los {@code null} (rendimiento con real en cero, desviación con teórico en cero) nunca se
 * promedian como cero (R-03): las medias ignoran los valores ausentes y devuelven {@code null}
 * si no queda ninguno. Los planes cancelados se listan y se cuentan aparte, pero no entran en
 * ningún promedio ni en el acumulado por materia prima (R-05, R-06).
 */
@Service
@Transactional(readOnly = true)
public class EficienciaService {

    /**
     * Umbral de la tendencia de una materia prima, en puntos porcentuales de desviación. Por
     * encima de {@code +5.00} el consumo es excesivo, por debajo de {@code -5.00} es reducido y
     * entre ambos (extremos incluidos) se considera normal.
     */
    public static final BigDecimal UMBRAL_TENDENCIA = new BigDecimal("5.00");

    public static final String TENDENCIA_EXCESIVO = "CONSUMO_EXCESIVO";
    public static final String TENDENCIA_REDUCIDO = "CONSUMO_REDUCIDO";
    public static final String TENDENCIA_NORMAL = "NORMAL";
    public static final String TENDENCIA_SIN_BASE = "SIN_BASE";

    static final String NOTA_SIN_RECETA = "Sin receta: no se puede calcular consumo teórico";

    private static final BigDecimal CIEN = BigDecimal.valueOf(100);
    private static final int ESCALA_PORCENTAJE = 2;
    private static final int ESCALA_CANTIDAD = 3;
    private static final int ESCALA_UNIDADES = 2;
    private static final int ESCALA_INTERMEDIA = 4;
    /**
     * Escala del teórico ajustado a lo producido. Debe ser holgada: con cantidades pequeñas
     * (p. ej. 0.003 kg) cuatro decimales distorsionan el cociente contra el real.
     */
    private static final int ESCALA_AJUSTE = 10;

    private final EficienciaRepository repository;
    private final CalculadoraEficienciaService calculadora;

    public EficienciaService(EficienciaRepository repository,
                             CalculadoraEficienciaService calculadora) {
        this.repository = repository;
        this.calculadora = calculadora;
    }

    /**
     * Reporte del período (CA-01 a CA-04). Los extremos son opcionales e inclusivos; un rango
     * invertido responde 400 {@code RANGO_FECHAS_INVALIDO} antes de consultar la base. Un período
     * sin planes devuelve contadores en cero y promedios {@code null}, sin error.
     */
    public EficienciaResponse reporte(LocalDate fechaInicio, LocalDate fechaFin) {
        validarRango(fechaInicio, fechaFin);

        var filas = repository.planesConConsumo(fechaInicio, fechaFin);
        var ids = filas.stream().map(PlanEficienciaFila::idPlan).toList();
        var planes = construirPlanes(filas, repository.productosDePlanes(ids),
                repository.consumosDePlanes(ids));

        var vigentes = planes.stream().filter(p -> !esCancelado(p)).toList();
        var consumosVigentes = vigentes.stream().flatMap(p -> p.consumos().stream()).toList();
        var materias = acumuladoPorMateriaPrima(fechaInicio, fechaFin);

        var resumen = new ResumenEficienciaResponse(
                vigentes.size(),
                planes.size() - vigentes.size(),
                media(vigentes.stream().map(EficienciaPlanResponse::cumplimiento).toList()),
                media(consumosVigentes.stream()
                        .map(EficienciaPlanResponse.Consumo::eficiencia).toList()),
                media(consumosVigentes.stream()
                        .map(EficienciaPlanResponse.Consumo::rendimientoAjustado).toList()),
                consumosVigentes.stream().filter(c -> c.eficiencia() == null).count(),
                media(materias.stream().map(DesviacionMateriaPrimaResponse::desviacion).toList()));

        var sinReceta = repository.productosSinReceta(fechaInicio, fechaFin).stream()
                .map(f -> new SinRecetaResponse(f.producto(), f.planes(), NOTA_SIN_RECETA))
                .toList();

        return new EficienciaResponse(new PeriodoResponse(fechaInicio, fechaFin), resumen, planes,
                sinReceta);
    }

    /**
     * Detalle de un plan (CP-21). 404 {@code PLAN_INEXISTENTE} si no existe y 404
     * {@code PLAN_SIN_CONSUMOS} si existe sin consumos registrados (R-05).
     */
    public EficienciaPlanResponse detallePlan(Integer idPlan) {
        var fila = repository.plan(idPlan).orElseThrow(() -> new PlanInexistenteException(idPlan));
        var consumos = repository.consumosDePlanes(List.of(idPlan));
        if (consumos.isEmpty()) {
            throw new PlanSinConsumosException(idPlan);
        }
        return construirPlanes(List.of(fila), repository.productosDePlanes(List.of(idPlan)),
                consumos).get(0);
    }

    /**
     * Desviación acumulada por materia prima del período (R-06), sin los planes cancelados,
     * ordenada por desviación absoluta descendente (las {@code null} al final) y luego por
     * nombre. Valida el rango igual que el reporte.
     */
    public List<DesviacionMateriaPrimaResponse> materiasPrimas(LocalDate fechaInicio,
                                                              LocalDate fechaFin) {
        validarRango(fechaInicio, fechaFin);
        return acumuladoPorMateriaPrima(fechaInicio, fechaFin);
    }

    private void validarRango(LocalDate fechaInicio, LocalDate fechaFin) {
        if (fechaInicio != null && fechaFin != null && fechaInicio.isAfter(fechaFin)) {
            throw new ParametroInvalidoException("RANGO_FECHAS_INVALIDO",
                    "La fecha de inicio no puede ser posterior a la fecha de fin.");
        }
    }

    private List<DesviacionMateriaPrimaResponse> acumuladoPorMateriaPrima(LocalDate inicio,
                                                                         LocalDate fin) {
        var lista = new ArrayList<DesviacionMateriaPrimaResponse>();
        for (var f : repository.materiasPrimasAcumuladas(inicio, fin)) {
            var desviacion = calculadora.desviacion(f.teorica(), f.real());
            lista.add(new DesviacionMateriaPrimaResponse(f.materiaPrima(), f.unidad(),
                    cantidad(f.teorica()), cantidad(f.real()), desviacion, f.planes(),
                    tendencia(desviacion)));
        }
        lista.sort(Comparator
                .comparing((DesviacionMateriaPrimaResponse d) -> d.desviacion() == null)
                .thenComparing(d -> d.desviacion() == null ? BigDecimal.ZERO : d.desviacion().abs(),
                        Comparator.reverseOrder())
                .thenComparing(DesviacionMateriaPrimaResponse::materiaPrima));
        return lista;
    }

    private static String tendencia(BigDecimal desviacion) {
        if (desviacion == null) {
            return TENDENCIA_SIN_BASE;
        }
        if (desviacion.compareTo(UMBRAL_TENDENCIA) > 0) {
            return TENDENCIA_EXCESIVO;
        }
        if (desviacion.compareTo(UMBRAL_TENDENCIA.negate()) < 0) {
            return TENDENCIA_REDUCIDO;
        }
        return TENDENCIA_NORMAL;
    }

    private List<EficienciaPlanResponse> construirPlanes(List<PlanEficienciaFila> filas,
                                                        List<ProductoPlanFila> productos,
                                                        List<ConsumoEficienciaFila> consumos) {
        var productosPorPlan = agrupar(productos, ProductoPlanFila::idPlan);
        var consumosPorPlan = agrupar(consumos, ConsumoEficienciaFila::idPlan);
        var planes = new ArrayList<EficienciaPlanResponse>();
        for (var fila : filas) {
            var lineas = productosPorPlan.getOrDefault(fila.idPlan(), List.of()).stream()
                    .map(this::producto).toList();
            var detalles = consumosPorPlan.getOrDefault(fila.idPlan(), List.of()).stream()
                    .map(this::consumo).toList();
            planes.add(new EficienciaPlanResponse(fila.idPlan(), fila.fechaProduccion(),
                    fila.estado(), fila.responsable(),
                    media(lineas.stream().map(EficienciaPlanResponse.Producto::cumplimiento).toList()),
                    media(detalles.stream().map(EficienciaPlanResponse.Consumo::eficiencia).toList()),
                    media(detalles.stream()
                            .map(EficienciaPlanResponse.Consumo::rendimientoAjustado).toList()),
                    lineas, detalles));
        }
        return planes;
    }

    private EficienciaPlanResponse.Producto producto(ProductoPlanFila f) {
        // Cumplimiento: producida / planificada * 100. La planificada es siempre > 0 (CHECK en V2).
        var cumplimiento = f.producida()
                .divide(f.planificada(), ESCALA_INTERMEDIA, RoundingMode.HALF_UP)
                .multiply(CIEN).setScale(ESCALA_PORCENTAJE, RoundingMode.HALF_UP);
        return new EficienciaPlanResponse.Producto(f.producto(),
                f.planificada().setScale(ESCALA_UNIDADES, RoundingMode.HALF_UP),
                f.producida().setScale(ESCALA_UNIDADES, RoundingMode.HALF_UP), cumplimiento);
    }

    private EficienciaPlanResponse.Consumo consumo(ConsumoEficienciaFila f) {
        // R-02: el teórico se calculó para lo planificado; se escala a lo producido antes de
        // compararlo con el real, y la comparación la hace la calculadora.
        var teoricoAjustado = f.teorica() == null ? null
                : f.teorica().multiply(f.producida())
                        .divide(f.planificada(), ESCALA_AJUSTE, RoundingMode.HALF_UP);
        return new EficienciaPlanResponse.Consumo(f.materiaPrima(), f.unidad(),
                cantidad(f.teorica()), cantidad(f.real()),
                calculadora.desviacion(f.teorica(), f.real()),
                calculadora.eficiencia(f.teorica(), f.real()),
                calculadora.eficiencia(teoricoAjustado, f.real()));
    }

    /**
     * Media de los valores no nulos con escala 2. Los {@code null} se descartan, no cuentan como
     * cero (R-03); si no queda ninguno el resultado es {@code null}.
     */
    private static BigDecimal media(Collection<BigDecimal> valores) {
        var presentes = valores.stream().filter(Objects::nonNull).toList();
        if (presentes.isEmpty()) {
            return null;
        }
        var suma = presentes.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return suma.divide(BigDecimal.valueOf(presentes.size()), ESCALA_PORCENTAJE,
                RoundingMode.HALF_UP);
    }

    private static BigDecimal cantidad(BigDecimal valor) {
        return valor == null ? null : valor.setScale(ESCALA_CANTIDAD, RoundingMode.HALF_UP);
    }

    private static boolean esCancelado(EficienciaPlanResponse plan) {
        return EstadoPlan.CANCELADO.name().equals(plan.estado());
    }

    private static <T> Map<Integer, List<T>> agrupar(List<T> filas,
                                                    java.util.function.ToIntFunction<T> clave) {
        var mapa = new LinkedHashMap<Integer, List<T>>();
        for (var fila : filas) {
            mapa.computeIfAbsent(clave.applyAsInt(fila), k -> new ArrayList<>()).add(fila);
        }
        return mapa;
    }
}
