package co.edu.corposucre.productionfood.proveedor.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;

class ActualizarProveedorRequestTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private Set<String> mensajes(ActualizarProveedorRequest req) {
        return validator.validate(req).stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.toSet());
    }

    @Test
    @DisplayName("Aplica las mismas reglas de nombre que la creación")
    void reglasDeNombre() {
        assertThat(mensajes(new ActualizarProveedorRequest(" ", null, null, null)))
                .containsExactly("El nombre es obligatorio");
        assertThat(mensajes(new ActualizarProveedorRequest("a".repeat(101), null, null, null)))
                .containsExactly("El nombre no puede superar 100 caracteres");
    }

    @Test
    @DisplayName("Aplica las mismas reglas de correo opcional que la creación")
    void reglasDeCorreo() {
        assertThat(mensajes(new ActualizarProveedorRequest("Proveedor", null, null, "no-es-correo")))
                .containsExactly("Ingrese un correo válido");
        assertThat(mensajes(new ActualizarProveedorRequest("Proveedor", null, null, ""))).isEmpty();
    }
}
