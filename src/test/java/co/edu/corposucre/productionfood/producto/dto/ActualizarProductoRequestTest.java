package co.edu.corposucre.productionfood.producto.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;

class ActualizarProductoRequestTest {

    private Validator validator;

    @BeforeEach
    void setUp() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    private ActualizarProductoRequest solicitudValida() {
        return new ActualizarProductoRequest("Pan integral", "Con salvado, 500 g",
                new BigDecimal("4800.00"), 5);
    }

    private Set<String> mensajesDe(String campo, ActualizarProductoRequest req) {
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
    @DisplayName("El DTO de actualización no expone el estado: solo se cambia por el PATCH")
    void noExponeElEstado() {
        var campos = Arrays.stream(ActualizarProductoRequest.class.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(Collectors.toSet());

        assertThat(campos).containsExactlyInAnyOrder("nombre", "descripcion", "precio", "idUnidad");
    }

    @Test
    @DisplayName("El nombre nulo, vacío o en blanco reporta que es obligatorio")
    void nombreObligatorio() {
        for (String nombre : new String[] {"", "   ", null}) {
            var req = new ActualizarProductoRequest(nombre, null, new BigDecimal("1.00"), 5);

            assertThat(mensajesDe("nombre", req)).containsExactly("El nombre es obligatorio");
        }
    }

    @Test
    @DisplayName("El nombre admite 100 caracteres y rechaza 101")
    void nombreLimiteDeLongitud() {
        var cientoUno = new ActualizarProductoRequest(
                "A".repeat(101), null, new BigDecimal("1.00"), 5);
        var cien = new ActualizarProductoRequest("A".repeat(100), null, new BigDecimal("1.00"), 5);

        assertThat(mensajesDe("nombre", cien)).isEmpty();
        assertThat(mensajesDe("nombre", cientoUno))
                .containsExactly("El nombre no puede superar 100 caracteres");
    }

    @Test
    @DisplayName("La descripción es opcional y rechaza más de 150 caracteres")
    void descripcionOpcionalConLimite() {
        var sinDescripcion = new ActualizarProductoRequest("Pan", null, new BigDecimal("1.00"), 5);
        var excedida = new ActualizarProductoRequest(
                "Pan", "A".repeat(151), new BigDecimal("1.00"), 5);

        assertThat(mensajesDe("descripcion", sinDescripcion)).isEmpty();
        assertThat(mensajesDe("descripcion", excedida))
                .containsExactly("La descripción no puede superar 150 caracteres");
    }

    @Test
    @DisplayName("El precio es obligatorio, no admite negativos y el cero es válido (R-06)")
    void precioReglas() {
        assertThat(mensajesDe("precio", new ActualizarProductoRequest(
                "Pan", null, null, 5))).containsExactly("El precio es obligatorio");
        assertThat(mensajesDe("precio", new ActualizarProductoRequest(
                "Pan", null, new BigDecimal("-1.00"), 5)))
                .containsExactly("El precio debe ser 0 o mayor");
        assertThat(mensajesDe("precio", new ActualizarProductoRequest(
                "Pan", null, BigDecimal.ZERO, 5))).isEmpty();
    }

    @Test
    @DisplayName("La unidad de venta es obligatoria")
    void unidadObligatoria() {
        var req = new ActualizarProductoRequest("Pan", null, new BigDecimal("1.00"), null);

        assertThat(mensajesDe("idUnidad", req))
                .containsExactly("La unidad de venta es obligatoria");
    }
}
