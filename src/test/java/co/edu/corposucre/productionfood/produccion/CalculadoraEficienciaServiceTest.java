package co.edu.corposucre.productionfood.produccion;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pruebas unitarias de {@link CalculadoraEficienciaService} (HU-22, R-03 y R-04).
 *
 * <p>No levantan Spring ni tocan la base: la calculadora es cálculo puro. Cubren los casos de
 * CP-08 a CP-13, los valores nulos y el redondeo {@code HALF_UP} a escala 2.
 */
class CalculadoraEficienciaServiceTest {

    private final CalculadoraEficienciaService calculadora = new CalculadoraEficienciaService();

    @Test
    @DisplayName("CP-08: 15 de teórico sobre 16.2 de real da una eficiencia de 92.59")
    void eficienciaDeCp08() {
        var eficiencia = calculadora.eficiencia(new BigDecimal("15"), new BigDecimal("16.2"));

        assertThat(eficiencia).isEqualByComparingTo("92.59");
        assertThat(eficiencia.scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("CP-09: sin consumo real no hay eficiencia")
    void eficienciaConRealCeroEsNula() {
        assertThat(calculadora.eficiencia(new BigDecimal("15"), BigDecimal.ZERO)).isNull();
        assertThat(calculadora.eficiencia(new BigDecimal("15"), new BigDecimal("0.000"))).isNull();
        assertThat(calculadora.eficiencia(new BigDecimal("15"), null)).isNull();
    }

    @Test
    @DisplayName("CP-10: teórico cero con real válido deja eficiencia 0.00 y desviación nula")
    void teoricoCeroConRealValido() {
        assertThat(calculadora.eficiencia(BigDecimal.ZERO, new BigDecimal("5")))
                .isEqualByComparingTo("0.00");
        assertThat(calculadora.desviacion(BigDecimal.ZERO, new BigDecimal("5"))).isNull();
    }

    @Test
    @DisplayName("CP-11: cero sobre cero no tiene eficiencia ni desviación")
    void ceroSobreCeroEsNulo() {
        assertThat(calculadora.eficiencia(BigDecimal.ZERO, BigDecimal.ZERO)).isNull();
        assertThat(calculadora.desviacion(BigDecimal.ZERO, BigDecimal.ZERO)).isNull();
    }

    @Test
    @DisplayName("CP-12: 15 de teórico sobre 7.5 de real da eficiencia 200.00 y desviación -50.00")
    void eficienciaYDesviacionDeCp12() {
        assertThat(calculadora.eficiencia(new BigDecimal("15"), new BigDecimal("7.5")))
                .isEqualByComparingTo("200.00");
        assertThat(calculadora.desviacion(new BigDecimal("15"), new BigDecimal("7.5")))
                .isEqualByComparingTo("-50.00");
    }

    @Test
    @DisplayName("CP-13: 12.5 de teórico sobre 13.5 de real da una desviación de 8.00")
    void desviacionDeCp13() {
        var desviacion = calculadora.desviacion(new BigDecimal("12.5"), new BigDecimal("13.5"));

        assertThat(desviacion).isEqualByComparingTo("8.00");
        assertThat(desviacion.scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("un teórico nulo con real válido da eficiencia 0.00, no null")
    void teoricoNuloConRealValidoDaEficienciaCero() {
        assertThat(calculadora.eficiencia(null, new BigDecimal("5")))
                .isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("sin teórico no hay desviación posible")
    void teoricoNuloNoTieneDesviacion() {
        assertThat(calculadora.desviacion(null, new BigDecimal("5"))).isNull();
        assertThat(calculadora.desviacion(new BigDecimal("5"), null)).isNull();
    }

    @Test
    @DisplayName("redondea con HALF_UP a escala 2")
    void redondeaConHalfUpAEscala2() {
        assertThat(calculadora.eficiencia(new BigDecimal("2"), new BigDecimal("3")))
                .isEqualByComparingTo("66.67");
        assertThat(calculadora.desviacion(new BigDecimal("3"), new BigDecimal("5")))
                .isEqualByComparingTo("66.67");
    }
}
