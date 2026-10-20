package co.edu.corposucre.productionfood.proveedor.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;

class CrearProveedorRequestTest {

    private Validator validator;

    @BeforeEach
    void setUp() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    private Set<String> mensajesDe(String campo, CrearProveedorRequest req) {
        return validator.validate(req).stream()
                .filter(v -> v.getPropertyPath().toString().equals(campo))
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.toSet());
    }

    @Test
    @DisplayName("Una solicitud con los cuatro campos válidos no produce violaciones")
    void solicitudValidaNoProduceViolaciones() {
        var req = new CrearProveedorRequest(
                "Harinas del Caribe", "Ana Pérez", "3001234567", "ventas@harinascaribe.com");

        assertThat(validator.validate(req)).isEmpty();
    }

    @Test
    @DisplayName("Solo el nombre es suficiente: los datos de contacto son opcionales")
    void soloNombreEsValido() {
        var req = new CrearProveedorRequest("Harinas del Caribe", null, null, null);

        assertThat(validator.validate(req)).isEmpty();
    }

    @Test
    @DisplayName("Un nombre vacío, en blanco o nulo reporta que es obligatorio")
    void nombreObligatorio() {
        for (String nombre : new String[] {"", "   ", null}) {
            var req = new CrearProveedorRequest(nombre, null, null, null);

            assertThat(mensajesDe("nombre", req)).contains("El nombre es obligatorio");
        }
    }

    @Test
    @DisplayName("El nombre admite 100 caracteres y rechaza 101")
    void nombreLimiteDeLongitud() {
        var enLimite = new CrearProveedorRequest("a".repeat(100), null, null, null);
        var excedido = new CrearProveedorRequest("a".repeat(101), null, null, null);

        assertThat(mensajesDe("nombre", enLimite)).isEmpty();
        assertThat(mensajesDe("nombre", excedido))
                .containsExactly("El nombre no puede superar 100 caracteres");
    }

    @Test
    @DisplayName("Contacto y teléfono rechazan 101 y 21 caracteres y admiten 100 y 20")
    void contactoYTelefonoLimiteDeLongitud() {
        var excedido = new CrearProveedorRequest("Proveedor", "c".repeat(101), "1".repeat(21), null);
        var enLimite = new CrearProveedorRequest("Proveedor", "c".repeat(100), "1".repeat(20), null);

        assertThat(mensajesDe("contacto", excedido))
                .containsExactly("El contacto no puede superar 100 caracteres");
        assertThat(mensajesDe("telefono", excedido))
                .containsExactly("El teléfono no puede superar 20 caracteres");
        assertThat(validator.validate(enLimite)).isEmpty();
    }

    @Test
    @DisplayName("Un correo con formato inválido reporta violación")
    void correoInvalidoReportaViolacion() {
        var req = new CrearProveedorRequest("Proveedor", null, null, "no-es-correo");

        assertThat(mensajesDe("correo", req)).containsExactly("Ingrese un correo válido");
    }

    @Test
    @DisplayName("El correo es opcional: vacío, nulo o válido no produce violación")
    void correoOpcional() {
        for (String correo : new String[] {"", null, "ventas@harinascaribe.com"}) {
            var req = new CrearProveedorRequest("Proveedor", null, null, correo);

            assertThat(mensajesDe("correo", req)).isEmpty();
        }
    }

    @Test
    @DisplayName("Un correo de formato válido que supera 100 caracteres reporta violación de tamaño")
    void correoDemasiadoLargo() {
        var correo = "ventas@" + "d".repeat(60) + "." + "e".repeat(30) + ".com";
        var req = new CrearProveedorRequest("Proveedor", null, null, correo);

        assertThat(mensajesDe("correo", req))
                .containsExactly("El correo no puede superar 100 caracteres");
    }
}
