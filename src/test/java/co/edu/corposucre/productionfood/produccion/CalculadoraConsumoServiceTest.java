package co.edu.corposucre.productionfood.produccion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import co.edu.corposucre.productionfood.receta.DetalleReceta;
import co.edu.corposucre.productionfood.receta.Receta;

/**
 * Pruebas unitarias de {@link CalculadoraConsumoService} (HU-21).
 *
 * <p>No levantan Spring ni tocan la base: la calculadora es cálculo puro. Cubren el caso de
 * CP-18, el orden de los ingredientes, el redondeo HALF_UP a escala 3 y las defensas de entrada.
 */
class CalculadoraConsumoServiceTest {

    private final CalculadoraConsumoService calculadora = new CalculadoraConsumoService();

    @Test
    @DisplayName("CP-18: 90 planificados de una receta que rinde 30 con 5 kg dan 15.000 kg")
    void calculaElConsumoDeCp18() {
        var receta = receta("30");
        var harina = detalle("Harina de trigo", "5.000", "kg");

        var consumos = calculadora.calcular(new BigDecimal("90"), receta, List.of(harina));

        assertThat(consumos).singleElement().satisfies(consumo -> {
            assertThat(consumo.materiaPrima()).isEqualTo("Harina de trigo");
            assertThat(consumo.cantidad()).isEqualTo(new BigDecimal("15.000"));
            assertThat(consumo.cantidad().scale()).isEqualTo(3);
            assertThat(consumo.unidad()).isEqualTo("kg");
        });
    }

    @Test
    @DisplayName("devuelve un consumo por ingrediente y conserva el orden recibido")
    void devuelveUnConsumoPorIngredienteEnOrden() {
        var receta = receta("10");
        var detalles = List.of(
                detalle("Harina de trigo", "2.000", "kg"),
                detalle("Azúcar", "0.500", "kg"),
                detalle("Sal", "0.010", "kg"));

        var consumos = calculadora.calcular(new BigDecimal("20"), receta, detalles);

        assertThat(consumos).extracting(ConsumoTeorico::materiaPrima)
                .containsExactly("Harina de trigo", "Azúcar", "Sal");
        assertThat(consumos).extracting(ConsumoTeorico::cantidad)
                .containsExactly(
                        new BigDecimal("4.000"),
                        new BigDecimal("1.000"),
                        new BigDecimal("0.020"));
    }

    @Test
    @DisplayName("redondea una sola vez con HALF_UP y escala 3")
    void redondeaConHalfUpYEscala3() {
        var receta = receta("3");
        var detalle = detalle("Harina de trigo", "1", "kg");

        var unTercio = calculadora.calcular(new BigDecimal("1"), receta, List.of(detalle));
        var dosTercios = calculadora.calcular(new BigDecimal("2"), receta, List.of(detalle));

        assertThat(unTercio).singleElement()
                .extracting(ConsumoTeorico::cantidad)
                .isEqualTo(new BigDecimal("0.333"));
        assertThat(dosTercios).singleElement()
                .extracting(ConsumoTeorico::cantidad)
                .isEqualTo(new BigDecimal("0.667"));
    }

    @Test
    @DisplayName("sin receta devuelve una lista vacía (R-06)")
    void sinRecetaDevuelveListaVacia() {
        var consumos = calculadora.calcular(
                new BigDecimal("90"), null, List.of(detalle("Harina de trigo", "5.000", "kg")));

        assertThat(consumos).isEmpty();
    }

    @Test
    @DisplayName("sin detalles o con detalles null devuelve una lista vacía")
    void sinDetallesDevuelveListaVacia() {
        var receta = receta("30");

        assertThat(calculadora.calcular(new BigDecimal("90"), receta, List.of())).isEmpty();
        assertThat(calculadora.calcular(new BigDecimal("90"), receta, null)).isEmpty();
    }

    @Test
    @DisplayName("un rendimiento de receta en cero es una invariante rota")
    void rendimientoCeroFalla() {
        var receta = receta("0");

        assertThatThrownBy(() -> calculadora.calcular(
                new BigDecimal("90"), receta, List.of(detalle("Harina de trigo", "5.000", "kg"))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("la cantidad planificada null es un argumento inválido")
    void cantidadPlanificadaNulaFalla() {
        var receta = receta("30");

        assertThatThrownBy(() -> calculadora.calcular(
                null, receta, List.of(detalle("Harina de trigo", "5.000", "kg"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Receta receta(String cantidadProducir) {
        var receta = new Receta();
        receta.setIdReceta(1);
        receta.setCantidadProducir(new BigDecimal(cantidadProducir));
        return receta;
    }

    private static DetalleReceta detalle(String materiaPrima, String cantidad, String unidad) {
        var detalle = new DetalleReceta();
        detalle.setNombreMateriaPrima(materiaPrima);
        detalle.setCantidad(new BigDecimal(cantidad));
        detalle.setAbreviaturaUnidad(unidad);
        return detalle;
    }
}
