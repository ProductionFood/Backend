package co.edu.corposucre.productionfood.produccion;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.corposucre.productionfood.materiaprima.MateriaPrimaConDetalle;
import co.edu.corposucre.productionfood.materiaprima.MateriaPrimaRepository;
import co.edu.corposucre.productionfood.produccion.dto.ConsumoTeoricoLineaResponse;
import co.edu.corposucre.productionfood.produccion.dto.IngredienteTeoricoResponse;
import co.edu.corposucre.productionfood.receta.DetalleReceta;
import co.edu.corposucre.productionfood.receta.DetalleRecetaRepository;
import co.edu.corposucre.productionfood.receta.Receta;
import co.edu.corposucre.productionfood.receta.RecetaRepository;

/**
 * Consulta del consumo teórico de materia prima de una línea de un plan de producción (HU-22,
 * R-01).
 *
 * <p>Es una operación de <strong>solo lectura</strong>: estima el material que exige la cantidad
 * planificada de la línea a partir de la receta y de lo disponible en inventario, sin registrar
 * consumo real ni descontar stock. El registro del consumo real es de otra operación de HU-22.
 *
 * <p>El teórico se calcula contra la <strong>cantidad planificada</strong> de la línea (R-02), no
 * contra lo producido: el objetivo es saber cuánto se necesita antes de producir. La fórmula de
 * cada ingrediente es la de {@link CalculadoraConsumoService}, que usa el rendimiento de una
 * tanda completa de la receta y redondea una sola vez al final.
 *
 * <p>Un producto sin receta se puede planificar pero no tiene consumo teórico: la respuesta
 * devuelve la lista de ingredientes vacía —nunca {@code null}— y los datos de la receta en
 * {@code null} explícito.
 */
@Service
public class ConsumoService {

    private final DetallePlanRepository detallePlanRepository;

    private final RecetaRepository recetaRepository;

    private final DetalleRecetaRepository detalleRecetaRepository;

    private final CalculadoraConsumoService calculadoraConsumoService;

    private final MateriaPrimaRepository materiaPrimaRepository;

    public ConsumoService(DetallePlanRepository detallePlanRepository,
                          RecetaRepository recetaRepository,
                          DetalleRecetaRepository detalleRecetaRepository,
                          CalculadoraConsumoService calculadoraConsumoService,
                          MateriaPrimaRepository materiaPrimaRepository) {
        this.detallePlanRepository = detallePlanRepository;
        this.recetaRepository = recetaRepository;
        this.detalleRecetaRepository = detalleRecetaRepository;
        this.calculadoraConsumoService = calculadoraConsumoService;
        this.materiaPrimaRepository = materiaPrimaRepository;
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
}
