package co.edu.corposucre.productionfood.compra.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import co.edu.corposucre.productionfood.compra.EstadoCompra;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;

class CambiarEstadoCompraRequestTest {

    private Validator validator;

    @BeforeEach
    void setUp() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    private Set<String> mensajesDe(String campo, CambiarEstadoCompraRequest req) {
        return validator.validate(req).stream()
                .filter(v -> v.getPropertyPath().toString().equals(campo))
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.toSet());
    }

    @Test
    @DisplayName("Cualquier estado canónico satisface la validación de forma")
    void estadosCanonicosValidos() {
        for (EstadoCompra estado : EstadoCompra.values()) {
            var req = new CambiarEstadoCompraRequest(estado);

            assertThat(validator.validate(req)).isEmpty();
        }
    }

    @Test
    @DisplayName("El estado es obligatorio")
    void estadoObligatorio() {
        var req = new CambiarEstadoCompraRequest(null);

        assertThat(mensajesDe("estado", req)).containsExactly("El estado es obligatorio");
    }
}
