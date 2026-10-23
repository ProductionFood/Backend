package co.edu.corposucre.productionfood.producto.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;

class CrearProductoRequestTest {

    private Validator validator;

    @BeforeEach
    void setUp() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    private CrearProductoRequest solicitudValida() {
        return new CrearProductoRequest("Pan francés", "Corteza crujiente, 80 g",
                new BigDecimal("1200.00"), 5);
    }

    private Set<String> mensajesDe(String campo, CrearProductoRequest req) {
        return validator.validate(req).stream()
                .filter(v -> v.getPropertyPath().toString().equals(campo))
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.toSet());
    }

    @Test
    @DisplayName("Una solicitud válida no produce violaciones")
    void solicitudValidaNoProduceViolaciones() {
        assertThat(validator.validate(solicitudValida())).isEmpty();
    }

    @Test
    @DisplayName("El nombre nulo, vacío o en blanco reporta que es obligatorio")
    void nombreObligatorio() {
        for (String nombre : new String[] {"", "   ", null}) {
            var req = new CrearProductoRequest(nombre, null, new BigDecimal("1200.00"), 5);

            assertThat(mensajesDe("nombre", req)).containsExactly("El nombre es obligatorio");
        }
    }

    @Test
    @DisplayName("El nombre admite 100 caracteres y rechaza 101")
    void nombreLimiteDeLongitud() {
        var cien = new CrearProductoRequest("A".repeat(100), null, new BigDecimal("1.00"), 5);
        var cientoUno = new CrearProductoRequest("A".repeat(101), null, new BigDecimal("1.00"), 5);

        assertThat(mensajesDe("nombre", cien)).isEmpty();
        assertThat(mensajesDe("nombre", cientoUno))
                .containsExactly("El nombre no puede superar 100 caracteres");
    }

    @Test
    @DisplayName("La descripción es opcional, admite 150 caracteres y rechaza 151")
    void descripcionOpcionalConLimite() {
        var sinDescripcion = new CrearProductoRequest("Pan", null, new BigDecimal("1.00"), 5);
        var cientoCincuenta = new CrearProductoRequest(
                "Pan", "A".repeat(150), new BigDecimal("1.00"), 5);
        var cientoCincuentaYUno = new CrearProductoRequest(
                "Pan", "A".repeat(151), new BigDecimal("1.00"), 5);

        assertThat(mensajesDe("descripcion", sinDescripcion)).isEmpty();
        assertThat(mensajesDe("descripcion", cientoCincuenta)).isEmpty();
        assertThat(mensajesDe("descripcion", cientoCincuentaYUno))
                .containsExactly("La descripción no puede superar 150 caracteres");
    }

    @Test
    @DisplayName("El precio es obligatorio")
    void precioObligatorio() {
        var req = new CrearProductoRequest("Pan", null, null, 5);

        assertThat(mensajesDe("precio", req)).containsExactly("El precio es obligatorio");
    }

    @Test
    @DisplayName("El precio negativo se rechaza y el cero es válido (R-06)")
    void precioNegativoSeRechazaYCeroEsValido() {
        var negativo = new CrearProductoRequest("Pan", null, new BigDecimal("-100.00"), 5);
        var cero = new CrearProductoRequest("Pan", null, BigDecimal.ZERO, 5);

        assertThat(mensajesDe("precio", negativo)).containsExactly("El precio debe ser 0 o mayor");
        assertThat(mensajesDe("precio", cero)).isEmpty();
    }

    @Test
    @DisplayName("El precio con tres decimales es válido: el servicio lo redondea (CP-10)")
    void precioConTresDecimalesEsValidoPorqueSeRedondea() {
        var req = new CrearProductoRequest("Pan", null, new BigDecimal("1200.555"), 5);

        assertThat(mensajesDe("precio", req)).isEmpty();
    }

    @Test
    @DisplayName("El precio por encima del máximo de la columna se rechaza")
    void precioPorEncimaDelMaximoSeRechaza() {
        var req = new CrearProductoRequest(
                "Pan", null, new BigDecimal("100000000.00"), 5);

        assertThat(mensajesDe("precio", req))
                .containsExactly("El precio no puede superar 99999999.99");
    }

    @Test
    @DisplayName("La unidad de venta es obligatoria")
    void unidadObligatoria() {
        var req = new CrearProductoRequest("Pan", null, new BigDecimal("1.00"), null);

        assertThat(mensajesDe("idUnidad", req))
                .containsExactly("La unidad de venta es obligatoria");
    }
}
