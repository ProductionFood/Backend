package co.edu.corposucre.productionfood.cliente.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;

class CrearClienteRequestTest {

    private Validator validator;

    @BeforeEach
    void setUp() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    private Set<String> mensajesDe(String campo, CrearClienteRequest req) {
        return validator.validate(req).stream()
                .filter(v -> v.getPropertyPath().toString().equals(campo))
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.toSet());
    }

    @Test
    @DisplayName("Una solicitud válida no produce violaciones")
    void solicitudValidaNoProduceViolaciones() {
        var req = new CrearClienteRequest("Tienda La Esquina", "Pedro Ruiz", "300 555 1234");

        assertThat(validator.validate(req)).isEmpty();
    }

    @Test
    @DisplayName("El nombre obligatorio reporta su mensaje")
    void nombreObligatorioReportaSuMensaje() {
        for (String nombre : new String[] {"", "   "}) {
            var req = new CrearClienteRequest(nombre, "Pedro Ruiz", "300 555 1234");

            assertThat(mensajesDe("nombre", req)).containsExactly("El nombre es obligatorio");
        }
    }

    @Test
    @DisplayName("El nombre admite 100 caracteres y rechaza 101")
    void limiteDeLongitudDelNombre() {
        var enLimite = new CrearClienteRequest("n".repeat(100), "Pedro Ruiz", "300 555 1234");
        var excedido = new CrearClienteRequest("n".repeat(101), "Pedro Ruiz", "300 555 1234");

        assertThat(mensajesDe("nombre", enLimite)).isEmpty();
        assertThat(mensajesDe("nombre", excedido))
                .containsExactly("El nombre no puede superar 100 caracteres");
    }

    @Test
    @DisplayName("El contacto admite 100 caracteres y rechaza 101")
    void limiteDeLongitudDelContacto() {
        var enLimite = new CrearClienteRequest("Tienda La Esquina", "c".repeat(100), "300 555 1234");
        var excedido = new CrearClienteRequest("Tienda La Esquina", "c".repeat(101), "300 555 1234");

        assertThat(mensajesDe("contacto", enLimite)).isEmpty();
        assertThat(mensajesDe("contacto", excedido))
                .containsExactly("El contacto no puede superar 100 caracteres");
    }

    @Test
    @DisplayName("El teléfono admite 20 caracteres y rechaza 21")
    void limiteDeLongitudDelTelefono() {
        var enLimite = new CrearClienteRequest("Tienda La Esquina", "Pedro Ruiz", "3".repeat(20));
        var excedido = new CrearClienteRequest("Tienda La Esquina", "Pedro Ruiz", "3".repeat(21));

        assertThat(mensajesDe("telefono", enLimite)).isEmpty();
        assertThat(mensajesDe("telefono", excedido))
                .containsExactly("El teléfono no puede superar 20 caracteres");
    }

    @Test
    @DisplayName("El contacto y el teléfono pueden venir nulos")
    void contactoYTelefonoPuedenSerNulos() {
        var req = new CrearClienteRequest("Tienda La Esquina", null, null);

        assertThat(validator.validate(req)).isEmpty();
    }

    @Test
    @DisplayName("El DTO de alta no recibe el estado")
    void elDtoDeAltaNoRecibeElEstado() {
        // El estado no viaja en la solicitud: lo fija el servicio, que nace activo (CA-03).
        // Si el DTO lo admitiera, un cliente podría nascerse desactivado desde el alta.
        var componentes = Arrays.stream(CrearClienteRequest.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        assertThat(componentes).containsExactly("nombre", "contacto", "telefono");
    }
}
