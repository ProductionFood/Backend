package co.edu.corposucre.productionfood.produccion;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.stereotype.Service;

/**
 * Cálculo de la eficiencia y de la desviación del consumo de materia prima (HU-22, R-03 y R-04).
 *
 * <p>Es cálculo puro: no depende de repositorios y no escribe nada, igual que
 * {@link CalculadoraConsumoService}. Compara el consumo teórico de una línea con el consumo real
 * registrado y expresa la diferencia como porcentaje.
 *
 * <p><strong>Eficiencia (R-03)</strong>: qué parte de lo real estaba previsto por el teórico,
 * con la fórmula {@code (teórico / real) * 100}. Un consumo real igual al teórico da
 * {@code 100.00}; consumir de menos lo supera y consumir de más lo baja. La cifra se redondea a
 * escala 2 con {@link RoundingMode#HALF_UP}, la de los porcentajes del proyecto.
 *
 * <p><strong>Desviación (R-04)</strong>: cuánto se apartó lo real del teórico, con signo, con la
 * fórmula {@code ((real - teórico) / teórico) * 100}. Un real mayor que el teórico da un valor
 * positivo —se gastó de más— y uno menor, negativo. También se redondea a escala 2 con
 * {@code HALF_UP}.
 *
 * <p>El cero no es un porcentaje calculable: si no hay consumo real no hay eficiencia, y si el
 * teórico es cero no hay base para medir una desviación. Ambos casos se responden como
 * {@code null} en lugar de inventar un valor; la capa que los consuma decide cómo mostrarlos.
 */
@Service
public class CalculadoraEficienciaService {

    private static final BigDecimal CIEN = BigDecimal.valueOf(100);

    /**
     * Calcula la eficiencia del consumo real frente al teórico (R-03).
     *
     * <p>Fórmula: {@code (teórico / real) * 100}, redondeada a escala 2 con
     * {@link RoundingMode#HALF_UP}. Si el consumo real falta o es cero no hay porcentaje posible
     * —no se gastó nada o no se registró— y la respuesta es {@code null}. En cambio, si el
     * teórico es {@code null} con un real válido, la respuesta es {@code 0.00} y no
     * {@code null}: CP-10 espera que la eficiencia exista aunque el cálculo teórico esté
     * ausente, y un real sin teórico equivale a un cumplimiento nulo.
     *
     * @param teorica consumo teórico, o {@code null} si no se pudo calcular
     * @param real    consumo real registrado, o {@code null} si no hay
     * @return la eficiencia en porcentaje con escala 2; {@code null} si el real falta o es cero
     */
    public BigDecimal eficiencia(BigDecimal teorica, BigDecimal real) {
        if (real == null || real.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        if (teorica == null) {
            return BigDecimal.ZERO.setScale(2);
        }
        return teorica.divide(real, 4, RoundingMode.HALF_UP)
                .multiply(CIEN)
                .setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Calcula la desviación del consumo real frente al teórico (R-04).
     *
     * <p>Fórmula: {@code ((real - teórico) / teórico) * 100}, redondeada a escala 2 con
     * {@link RoundingMode#HALF_UP}. El signo importa: positivo cuando se gastó más de lo previsto
     * y negativo cuando se gastó menos. Si el teórico falta o es cero no hay base para medir la
     * desviación y la respuesta es {@code null}; si el real falta tampoco hay comparación posible,
     * así que también responde {@code null}.
     *
     * @param teorica consumo teórico, o {@code null} si no se pudo calcular
     * @param real    consumo real registrado, o {@code null} si no hay
     * @return la desviación en porcentaje con escala 2; {@code null} si falta el teórico, el real
     *         o el teórico es cero
     */
    public BigDecimal desviacion(BigDecimal teorica, BigDecimal real) {
        if (teorica == null || teorica.compareTo(BigDecimal.ZERO) == 0 || real == null) {
            return null;
        }
        return real.subtract(teorica)
                .divide(teorica, 4, RoundingMode.HALF_UP)
                .multiply(CIEN)
                .setScale(2, RoundingMode.HALF_UP);
    }
}
