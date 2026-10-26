package co.edu.corposucre.productionfood.compra.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;

class ActualizarCompraRequestTest {

    private Validator validator;

    @BeforeEach
    void setUp() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    private ActualizarCompraRequest solicitudValida() {
        return new ActualizarCompraRequest(5, LocalDate.of(2026, 10, 20));
    }

    private Set<String> mensajesDe(String campo, ActualizarCompraRequest req) {
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
    @DisplayName("El proveedor es obligatorio")
    void proveedorObligatorio() {
        var req = new ActualizarCompraRequest(null, LocalDate.of(2026, 10, 20));

        assertThat(mensajesDe("idProveedor", req))
                .containsExactly("El proveedor es obligatorio");
    }

    @Test
    @DisplayName("La fecha es obligatoria")
    void fechaObligatoria() {
        var req = new ActualizarCompraRequest(5, null);

        assertThat(mensajesDe("fecha", req)).containsExactly("La fecha es obligatoria");
    }
}
