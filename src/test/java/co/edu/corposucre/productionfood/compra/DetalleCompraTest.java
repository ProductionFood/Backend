package co.edu.corposucre.productionfood.compra;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DetalleCompraTest {

    @Test
    @DisplayName("subtotal es cantidad por precio, a escala 2")
    void subtotalEsCantidadPorPrecio() {
        var linea = new DetalleCompra(1, 12, 5, new BigDecimal("100.00"), new BigDecimal("3200.00"));

        var subtotal = linea.subtotal();

        assertThat(subtotal).isEqualByComparingTo("320000.00");
        assertThat(subtotal.scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("subtotal redondea el producto a HALF_UP: 33.33 × 3333.33 no deja centavos fantasma")
    void subtotalRedondeaAHalfUp() {
        var linea = new DetalleCompra(1, 12, 5, new BigDecimal("33.33"), new BigDecimal("3333.33"));

        // El producto exacto es 111099.8889; el subtotal se fija a escala 2 con HALF_UP.
        assertThat(linea.subtotal()).isEqualTo(new BigDecimal("111099.89"));
        assertThat(linea.subtotal().scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("el constructor fija cantidad y precio a escala 2, la de las columnas")
    void elConstructorFijaEscalaDos() {
        var linea = new DetalleCompra(1, 12, 5, new BigDecimal("33.3"), new BigDecimal("1.5"));

        assertThat(linea.cantidad()).isEqualTo(new BigDecimal("33.30"));
        assertThat(linea.precioUnitario()).isEqualTo(new BigDecimal("1.50"));
        assertThat(linea.cantidad().scale()).isEqualTo(2);
        assertThat(linea.precioUnitario().scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("los nulos de cantidad y precio se conservan, sin romper la construcción")
    void losNulosSeConservan() {
        var linea = new DetalleCompra(1, 12, 5, null, null);

        assertThat(linea.cantidad()).isNull();
        assertThat(linea.precioUnitario()).isNull();
    }

    @Test
    @DisplayName("el constructor sin JOIN deja nombre y unidad de la materia prima en null")
    void elConstructorSinJoinDejaLasProyeccionesEnNulo() {
        var linea = new DetalleCompra(1, 12, 5, new BigDecimal("1.00"), new BigDecimal("2.00"));

        assertThat(linea.nombreMateriaPrima()).isNull();
        assertThat(linea.abreviaturaUnidad()).isNull();
    }

    @Test
    @DisplayName("el constructor completo conserva el nombre y la abreviatura del JOIN")
    void elConstructorCompletoConservaLasProyecciones() {
        var linea = new DetalleCompra(1, 12, 5, new BigDecimal("1.00"), new BigDecimal("2.00"),
                "Harina de trigo", "kg");

        assertThat(linea.idDetalle()).isEqualTo(1);
        assertThat(linea.idCompra()).isEqualTo(12);
        assertThat(linea.idMateriaPrima()).isEqualTo(5);
        assertThat(linea.nombreMateriaPrima()).isEqualTo("Harina de trigo");
        assertThat(linea.abreviaturaUnidad()).isEqualTo("kg");
    }
}
