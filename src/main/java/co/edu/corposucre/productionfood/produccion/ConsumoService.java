package co.edu.corposucre.productionfood.produccion;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.corposucre.productionfood.common.audit.Auditable;
import co.edu.corposucre.productionfood.common.error.ConflictoNegocioException;
import co.edu.corposucre.productionfood.common.error.ParametroInvalidoException;
import co.edu.corposucre.productionfood.common.error.RecursoNoEncontradoException;
import co.edu.corposucre.productionfood.inventario.InventarioService;
import co.edu.corposucre.productionfood.inventario.SaldosInventario;
import co.edu.corposucre.productionfood.inventario.TipoMovimiento;
import co.edu.corposucre.productionfood.materiaprima.MateriaPrimaConDetalle;
import co.edu.corposucre.productionfood.materiaprima.MateriaPrimaRepository;
import co.edu.corposucre.productionfood.produccion.dto.ConsumoResponse;
import co.edu.corposucre.productionfood.produccion.dto.ConsumoTeoricoLineaResponse;
import co.edu.corposucre.productionfood.produccion.dto.IngredienteTeoricoResponse;
import co.edu.corposucre.productionfood.produccion.dto.InventarioConsumoResponse;
import co.edu.corposucre.productionfood.produccion.dto.MateriaPrimaRefResponse;
import co.edu.corposucre.productionfood.produccion.dto.RegistrarConsumoRequest;
import co.edu.corposucre.productionfood.receta.DetalleReceta;
import co.edu.corposucre.productionfood.receta.DetalleRecetaRepository;
import co.edu.corposucre.productionfood.receta.Receta;
import co.edu.corposucre.productionfood.receta.RecetaRepository;

/**
 * Consumo de materia prima de una línea de un plan de producción (HU-22).
 *
 * <p>Reúne las dos operaciones de la historia: la <strong>consulta</strong> del consumo teórico
 * —solo lectura— y el <strong>registro</strong> del consumo real, que persiste el consumo y
 * descuenta inventario en la misma transacción (R-05, R-09).
 *
 * <p>El teórico se calcula contra la <strong>cantidad planificada</strong> de la línea (R-02), no
 * contra lo producido: el objetivo es saber cuánto se necesita antes de producir. La fórmula de
 * cada ingrediente es la de {@link CalculadoraConsumoService}, que usa el rendimiento de una
 * tanda completa de la receta y redondea una sola vez al final.
 *
 * <p>Un producto sin receta se puede planificar pero no tiene consumo teórico: la consulta
 * devuelve la lista de ingredientes vacía —nunca {@code null}— y los datos de la receta en
 * {@code null} explícito. Al registrar, una materia prima fuera de la receta se acepta con
 * teórico cero y se marca {@code fueraDeReceta} (R-08).
 */
@Service
public class ConsumoService {

    private static final BigDecimal CERO_TEORICO = BigDecimal.ZERO.setScale(3, RoundingMode.HALF_UP);

    private final DetallePlanRepository detallePlanRepository;

    private final RecetaRepository recetaRepository;

    private final DetalleRecetaRepository detalleRecetaRepository;

    private final CalculadoraConsumoService calculadoraConsumoService;

    private final MateriaPrimaRepository materiaPrimaRepository;

    private final ConsumoRepository consumoRepository;

    private final PlanProduccionRepository planProduccionRepository;

    private final InventarioService inventarioService;

    private final CalculadoraEficienciaService calculadoraEficienciaService;

    public ConsumoService(DetallePlanRepository detallePlanRepository,
                          RecetaRepository recetaRepository,
                          DetalleRecetaRepository detalleRecetaRepository,
                          CalculadoraConsumoService calculadoraConsumoService,
                          MateriaPrimaRepository materiaPrimaRepository,
                          ConsumoRepository consumoRepository,
                          PlanProduccionRepository planProduccionRepository,
                          InventarioService inventarioService,
                          CalculadoraEficienciaService calculadoraEficienciaService) {
        this.detallePlanRepository = detallePlanRepository;
        this.recetaRepository = recetaRepository;
        this.detalleRecetaRepository = detalleRecetaRepository;
        this.calculadoraConsumoService = calculadoraConsumoService;
        this.materiaPrimaRepository = materiaPrimaRepository;
        this.consumoRepository = consumoRepository;
        this.planProduccionRepository = planProduccionRepository;
        this.inventarioService = inventarioService;
        this.calculadoraEficienciaService = calculadoraEficienciaService;
    }

    /**
     * Devuelve el consumo teórico de una línea del plan, con lo disponible de cada materia prima.
     *
     * <p>Orden de la consulta:
     *
     * <ol>
     *   <li>La línea existe; si no, 404 {@code DETALLE_PLAN_INEXISTENTE}.</li>
     *   <li>Si el producto no tiene receta, la respuesta trae la lista de ingredientes vacía y
     *       {@code recetaRinde} y {@code factor} en {@code null} (R-06).</li>
     *   <li>Si tiene receta, se calcula primero el teórico de cada ingrediente y solo después el
     *       factor: la calculadora es quien rechaza un rendimiento de receta no positivo.</li>
     *   <li>Por cada ingrediente se consulta su stock actual y se arma la fila con la cantidad por
     *       tanda, el teórico y lo disponible.</li>
     * </ol>
     *
     * <p>La calculadora devuelve un consumo por cada {@link DetalleReceta} en el mismo orden en
     * que llegan los detalles; si esa correspondencia se rompiera, el servicio falla con
     * {@link IllegalStateException} en lugar de mezclar cantidades con materias primas.
     *
     * @param idDetallePlan identificador de la línea del plan
     * @return el consumo teórico de la línea, con su producto, su cantidad planificada y sus
     *         ingredientes; ingredientes vacíos si el producto no tiene receta
     * @throws DetallePlanInexistenteException si la línea no existe (404)
     */
    @Transactional(readOnly = true)
    public ConsumoTeoricoLineaResponse consumoTeorico(Integer idDetallePlan) {
        var linea = detallePlanRepository.buscarPorId(idDetallePlan)
                .orElseThrow(() -> new DetallePlanInexistenteException(idDetallePlan));

        var receta = recetaRepository.buscarPorProducto(linea.getIdProducto()).orElse(null);
        if (receta == null) {
            return new ConsumoTeoricoLineaResponse(linea.getIdDetallePlan(),
                    linea.getNombreProducto(), linea.getCantidadPlanificada(), null, null,
                    List.of());
        }

        var detalles = detalleRecetaRepository.listarPorReceta(receta.getIdReceta());
        var calculos = calculadoraConsumoService.calcular(
                linea.getCantidadPlanificada(), receta, detalles);
        if (calculos.size() != detalles.size()) {
            throw new IllegalStateException(
                    "El cálculo del consumo teórico no corresponde con los ingredientes de la "
                            + "receta " + receta.getIdReceta() + ".");
        }

        var cantidadProducir = receta.getCantidadProducir();
        if (cantidadProducir == null || cantidadProducir.signum() <= 0) {
            throw new IllegalStateException(
                    "La receta " + receta.getIdReceta() + " no tiene una cantidad a producir "
                            + "mayor que cero; no se puede calcular el consumo teórico.");
        }
        var factor = linea.getCantidadPlanificada()
                .divide(cantidadProducir, 3, RoundingMode.HALF_UP);

        List<IngredienteTeoricoResponse> ingredientes = new ArrayList<>(detalles.size());
        for (int i = 0; i < detalles.size(); i++) {
            var detalle = detalles.get(i);
            var calculo = calculos.get(i);
            var disponible = materiaPrimaRepository
                    .buscarConDetalle(detalle.getIdMateriaPrima())
                    .map(MateriaPrimaConDetalle::getCantidadDisponible)
                    .orElse(BigDecimal.ZERO.setScale(2));

            ingredientes.add(new IngredienteTeoricoResponse(
                    detalle.getIdMateriaPrima(),
                    calculo.materiaPrima(),
                    calculo.unidad(),
                    detalle.getCantidad(),
                    calculo.cantidad(),
                    disponible));
        }

        return new ConsumoTeoricoLineaResponse(
                linea.getIdDetallePlan(),
                linea.getNombreProducto(),
                linea.getCantidadPlanificada(),
                receta.getCantidadProducir(),
                factor,
                ingredientes);
    }

    /**
     * Registra el consumo real de una materia prima en una línea del plan y descuenta inventario
     * (HU-22, R-05 y R-09).
     *
     * <p>El orden de las validaciones es el de la especificación §5:
     *
     * <ol>
     *   <li>La línea existe; si no, 404 {@code DETALLE_PLAN_INEXISTENTE}.</li>
     *   <li>El plan de la línea existe y está {@code EN_PROCESO}; si no, 409
     *       {@code PLAN_NO_EN_PROCESO}.</li>
     *   <li>La cantidad real no es nula ni negativa; si lo es, 400 {@code CANTIDAD_INVALIDA}. El
     *       cero es válido (CP-09) y se normaliza a escala 3.</li>
     *   <li>La materia prima existe y está activa; si no, 409
     *       {@code MATERIA_PRIMA_INEXISTENTE}.</li>
     *   <li>La línea no tiene un consumo previo de esa materia prima; si lo tiene, 409
     *       {@code CONSUMO_DUPLICADO} (R-06).</li>
     *   <li>La cantidad teórica la calcula el servidor con la receta (R-01). Si la materia prima no
     *       está en la receta, el teórico es {@code 0.000} y el consumo queda fuera de receta
     *       (R-08).</li>
     *   <li>Se inserta el consumo. El {@code UNIQUE KEY uk_consumo_mp} puede rechazar una carrera
     *       entre dos peticiones simultáneas y esa violación se traduce a
     *       {@code CONSUMO_DUPLICADO}.</li>
     *   <li>Se descuenta la cantidad real del inventario con {@link TipoMovimiento#SALIDA_PRODUCCION},
     *       redondeada a 2 decimales con {@code HALF_UP} (R-09). Un real de cero —o uno que
     *       redondea a {@code 0.00}— no toca el inventario. Si no existe fila de inventario se
     *       responde 409 {@code STOCK_INSUFICIENTE}.</li>
     *   <li>Se relee el consumo para tomar su fecha de registro y las proyecciones del
     *       {@code JOIN}.</li>
     * </ol>
     *
     * <p>El {@code INSERT} va antes del descuento porque el kardex necesita
     * {@code id_origen = idConsumo}; ambos pasos viven en la misma transacción, así que si el
     * descuento falla por {@code STOCK_INSUFICIENTE} la transacción revierte también el consumo
     * insertado y no quedan registros sin su movimiento de inventario.
     *
     * @param idDetallePlan identificador de la línea del plan
     * @param req           materia prima y cantidad real consumida
     * @return el consumo registrado con su eficiencia, su desviación y el efecto en inventario
     * @throws DetallePlanInexistenteException si la línea no existe (404)
     * @throws ConflictoNegocioException       si el plan no está en proceso, la materia prima no
     *                                          existe o está inactiva, el consumo ya existe o el
     *                                          stock no alcanza (409)
     * @throws ParametroInvalidoException      si la cantidad real es nula o negativa (400)
     */
    @Transactional
    @Auditable(accion = "REGISTRAR_CONSUMO", tabla = "consumo_materia_prima",
            detalle = "'Consumo de la materia prima ' + #req.idMateriaPrima"
                    + " + ' registrado en la línea ' + #idDetallePlan")
    public ConsumoResponse registrar(Integer idDetallePlan, RegistrarConsumoRequest req) {
        var linea = detallePlanRepository.buscarPorId(idDetallePlan)
                .orElseThrow(() -> new DetallePlanInexistenteException(idDetallePlan));

        planProduccionRepository.buscarPorId(linea.getIdPlan())
                .filter(plan -> plan.getEstado() == EstadoPlan.EN_PROCESO)
                .orElseThrow(() -> new ConflictoNegocioException("PLAN_NO_EN_PROCESO",
                        "El plan de producción " + linea.getIdPlan()
                                + " no existe o no está en proceso."));

        var cantidadReal = validarCantidadReal(req.cantidadReal());

        if (!materiaPrimaRepository.existeActiva(req.idMateriaPrima())) {
            throw new ConflictoNegocioException("MATERIA_PRIMA_INEXISTENTE",
                    "La materia prima " + req.idMateriaPrima() + " no existe o está inactiva.");
        }

        if (consumoRepository.existeConsumo(idDetallePlan, req.idMateriaPrima())) {
            throw consumoDuplicado(idDetallePlan, req.idMateriaPrima());
        }

        var cantidadTeorica = calcularTeorica(linea, req.idMateriaPrima());

        var consumo = new ConsumoMateriaPrima();
        consumo.setIdDetallePlan(idDetallePlan);
        consumo.setIdMateriaPrima(req.idMateriaPrima());
        consumo.setCantidadTeorica(cantidadTeorica);
        consumo.setCantidadReal(cantidadReal);

        Integer idConsumo;
        try {
            idConsumo = consumoRepository.insertar(consumo);
        } catch (DuplicateKeyException ex) {
            throw consumoDuplicado(idDetallePlan, req.idMateriaPrima());
        }

        var cantidadInventario = cantidadReal.setScale(2, RoundingMode.HALF_UP);
        SaldosInventario saldos = null;
        if (cantidadInventario.signum() > 0) {
            try {
                saldos = inventarioService.registrarSalida(req.idMateriaPrima(),
                        cantidadInventario, TipoMovimiento.SALIDA_PRODUCCION,
                        "consumo_materia_prima", idConsumo,
                        "Consumo real registrado en la línea " + idDetallePlan);
            } catch (RecursoNoEncontradoException ex) {
                throw new ConflictoNegocioException("STOCK_INSUFICIENTE",
                        "No hay inventario suficiente de la materia prima "
                                + req.idMateriaPrima() + " para registrar el consumo.");
            }
        }

        var guardado = consumoRepository.buscarPorId(idConsumo)
                .orElseThrow(() -> new IllegalStateException(
                        "No se encontró el consumo " + idConsumo + " recién insertado."));

        var inventario = saldos == null ? null
                : new InventarioConsumoResponse(saldos.anterior(), saldos.posterior());

        return new ConsumoResponse(
                idConsumo,
                idDetallePlan,
                guardado.getNombreProducto(),
                new MateriaPrimaRefResponse(guardado.getIdMateriaPrima(),
                        guardado.getNombreMateriaPrima(), guardado.getUnidad()),
                cantidadTeorica,
                cantidadReal,
                calculadoraEficienciaService.eficiencia(cantidadTeorica, cantidadReal),
                calculadoraEficienciaService.desviacion(cantidadTeorica, cantidadReal),
                cantidadTeorica.signum() == 0,
                guardado.getFechaRegistro(),
                inventario);
    }

    /**
     * Valida y normaliza la cantidad real a la escala 3 de {@code consumo_materia_prima} (R-09).
     *
     * @param cantidadReal cantidad enviada por el cliente
     * @return la cantidad con escala 3
     * @throws ParametroInvalidoException si es nula o negativa
     */
    private static BigDecimal validarCantidadReal(BigDecimal cantidadReal) {
        if (cantidadReal == null || cantidadReal.signum() < 0) {
            throw new ParametroInvalidoException("CANTIDAD_INVALIDA",
                    "La cantidad real no puede ser negativa.");
        }
        return cantidadReal.setScale(3, RoundingMode.HALF_UP);
    }

    /**
     * Calcula la cantidad teórica de una materia prima con la receta del producto (R-01).
     *
     * <p>Si el producto no tiene receta o la materia prima no es uno de sus ingredientes, no hay
     * teórico que calcular: devuelve {@code 0.000} para que el consumo quede fuera de receta
     * (R-08). Cuando sí está en la receta, delega en {@link CalculadoraConsumoService} con la
     * cantidad planificada de la línea (R-02) y conserva la escala 3 de su resultado.
     *
     * @param linea          línea del plan que se está consumiendo
     * @param idMateriaPrima materia prima consumida
     * @return cantidad teórica con escala 3
     */
    private BigDecimal calcularTeorica(DetallePlanProduccion linea, Integer idMateriaPrima) {
        var receta = recetaRepository.buscarPorProducto(linea.getIdProducto()).orElse(null);
        if (receta == null) {
            return CERO_TEORICO;
        }

        var detalle = detalleRecetaRepository.listarPorReceta(receta.getIdReceta()).stream()
                .filter(ingrediente -> idMateriaPrima.equals(ingrediente.getIdMateriaPrima()))
                .findFirst()
                .orElse(null);
        if (detalle == null) {
            return CERO_TEORICO;
        }

        var calculos = calculadoraConsumoService.calcular(linea.getCantidadPlanificada(), receta,
                List.of(detalle));
        if (calculos.isEmpty()) {
            return CERO_TEORICO;
        }
        return calculos.getFirst().cantidad().setScale(3, RoundingMode.HALF_UP);
    }

    /**
     * Arma el 409 de consumo duplicado con el mismo mensaje para la comprobación previa y para la
     * violación del {@code UNIQUE KEY uk_consumo_mp} (R-06).
     *
     * @param idDetallePlan  línea del plan
     * @param idMateriaPrima materia prima ya registrada
     * @return la excepción lista para lanzar
     */
    private static ConflictoNegocioException consumoDuplicado(Integer idDetallePlan,
                                                               Integer idMateriaPrima) {
        return new ConflictoNegocioException("CONSUMO_DUPLICADO",
                "La materia prima " + idMateriaPrima
                        + " ya tiene un consumo registrado en la línea " + idDetallePlan + ".");
    }
}
