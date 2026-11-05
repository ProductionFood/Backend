package co.edu.corposucre.productionfood.produccion;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import co.edu.corposucre.productionfood.receta.DetalleReceta;
import co.edu.corposucre.productionfood.receta.Receta;

/**
 * Cálculo del consumo teórico de materia prima de una cantidad planificada (HU-21).
 *
 * <p>Es cálculo puro: no depende de repositorios y no escribe nada, para que HU-22 pueda
 * reutilizarlo al generar el consumo real de un plan. La fórmula es la de R-02 y R-06:
 *
 * <pre>
 * consumoTeorico = (cantidadPlanificada / receta.cantidadProducir) * detalleReceta.cantidad
 * </pre>
 *
 * <p>{@code receta.cantidadProducir} es el rendimiento de <strong>una tanda completa</strong>, no
 * de una unidad. Por ejemplo, CP-18: planificar 90 unidades de una receta que rinde 30 y lleva
 * 5 kg de harina exige {@code (90 / 30) * 5 = 15.000 kg}, no 450 kg.
 *
 * <p>El resultado se redondea <strong>una sola vez</strong>, al final y con escala 3
 * ({@link RoundingMode#HALF_UP}), igual que la columna {@code cantidad} de
 * {@code detalle_receta}. Redondear en pasos intermedios acumularía error.
 */
@Service
public class CalculadoraConsumoService {

    /**
     * Calcula el consumo teórico de cada ingrediente de la receta para una cantidad planificada.
     *
     * <p>Un producto sin receta se puede planificar, pero no tiene consumo teórico (R-06): si no
     * hay receta o no hay ingredientes, la respuesta es una lista vacía. El orden de los
     * consumos es el mismo en que llegan los detalles.
     *
     * @param cantidadPlanificada cantidad a producir; obligatoria cuando hay receta con detalle
     * @param receta               receta del producto, o {@code null} si no tiene
     * @param detalles             ingredientes de la receta; vacío si no tiene
     * @return un consumo teórico por ingrediente, en el orden recibido; vacío si no hay receta ni
     *         ingredientes
     * @throws IllegalArgumentException si {@code cantidadPlanificada} es {@code null}
     * @throws IllegalStateException    si el rendimiento de la receta es {@code null} o no es
     *                                  mayor que cero, una invariante que
     *                                  {@code RecetaService} debería haber impedido
     */
    public List<ConsumoTeorico> calcular(BigDecimal cantidadPlanificada, Receta receta,
                                         List<DetalleReceta> detalles) {
        if (receta == null || detalles == null || detalles.isEmpty()) {
            return List.of();
        }

        BigDecimal cantidadProducir = receta.getCantidadProducir();
        if (cantidadProducir == null || cantidadProducir.signum() <= 0) {
            throw new IllegalStateException(
                    "La receta " + receta.getIdReceta() + " no tiene una cantidad a producir "
                            + "mayor que cero; no se puede calcular el consumo teórico.");
        }
        if (cantidadPlanificada == null) {
            throw new IllegalArgumentException(
                    "La cantidad planificada es obligatoria para calcular el consumo teórico.");
        }

        List<ConsumoTeorico> consumos = new ArrayList<>(detalles.size());
        for (DetalleReceta detalle : detalles) {
            BigDecimal cantidad = cantidadPlanificada
                    .multiply(detalle.getCantidad())
                    .divide(cantidadProducir, 3, RoundingMode.HALF_UP);
            consumos.add(new ConsumoTeorico(
                    detalle.getNombreMateriaPrima(), cantidad, detalle.getAbreviaturaUnidad()));
        }
        return consumos;
    }
}
