package co.edu.corposucre.productionfood.unidadmedida.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;

class ActualizarUnidadRequestTest {

    private Validator validator;

    @BeforeEach
    void setUp() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    private Set<String> mensajesDe(String campo, ActualizarUnidadRequest req) {
        return validator.validate(req).stream()
                .filter(v -> v.getPropertyPath().toString().equals(campo))
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.toSet());
    }

    @Test
    @DisplayName("Una solicitud con nombre y abreviatura válidos no produce violaciones")
    void solicitudValidaNoProduceViolaciones() {
        var req = new ActualizarUnidadRequest("Kilogramo", "kg");

        assertThat(validator.validate(req)).isEmpty();
    }

    @Test
    @DisplayName("El nombre vacío, en blanco o nulo reporta que es obligatorio")
    void nombreObligatorio() {
        for (String nombre : new String[] {"", "   ", null}) {
            var req = new ActualizarUnidadRequest(nombre, "kg");

            assertThat(mensajesDe("nombre", req)).containsExactly("El nombre es obligatorio");
        }
    }

    @Test
    @DisplayName("La abreviatura vacía, en blanco o nula reporta que es obligatoria")
    void abreviaturaObligatoria() {
        for (String abreviatura : new String[] {"", "   ", null}) {
            var req = new ActualizarUnidadRequest("Kilogramo", abreviatura);

            assertThat(mensajesDe("abreviatura", req))
                    .containsExactly("La abreviatura es obligatoria");
        }
    }

    @Test
    @DisplayName("El nombre admite 50 caracteres y rechaza 51")
    void nombreLimiteDeLongitud() {
        var enLimite = new ActualizarUnidadRequest("a".repeat(50), "kg");
        var excedido = new ActualizarUnidadRequest("a".repeat(51), "kg");

        assertThat(mensajesDe("nombre", enLimite)).isEmpty();
        assertThat(mensajesDe("nombre", excedido))
                .containsExactly("El nombre no puede superar 50 caracteres");
    }

    @Test
    @DisplayName("La abreviatura admite 50 caracteres y rechaza 51")
    void abreviaturaLimiteDeLongitud() {
        var enLimite = new ActualizarUnidadRequest("Kilogramo", "a".repeat(50));
        var excedido = new ActualizarUnidadRequest("Kilogramo", "a".repeat(51));

        assertThat(mensajesDe("abreviatura", enLimite)).isEmpty();
        assertThat(mensajesDe("abreviatura", excedido))
                .containsExactly("La abreviatura no puede superar 50 caracteres");
    }
}
