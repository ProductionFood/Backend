package co.edu.corposucre.productionfood.usuario.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;

class CrearUsuarioRequestTest {

    private Validator validator;

    @BeforeEach
    void setUp() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    private CrearUsuarioRequest solicitudValida() {
        return new CrearUsuarioRequest(
                "Usuario Nuevo", "nuevo@productionfood.local", "Clave2026*", 4);
    }

    private Set<String> mensajesDe(String campo, CrearUsuarioRequest req) {
        return validator.validate(req).stream()
                .filter(v -> v.getPropertyPath().toString().equals(campo))
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.toSet());
    }

    @Test
    @DisplayName("Una solicitud válida no produce violaciones")
    void solicitudValidaNoProduceViolaciones() {
        Set<ConstraintViolation<CrearUsuarioRequest>> violaciones =
                validator.validate(solicitudValida());

        assertThat(violaciones).isEmpty();
    }

    @Test
    @DisplayName("Los cuatro campos obligatorios reportan su mensaje")
    void camposObligatoriosReportanSuMensaje() {
        var req = new CrearUsuarioRequest("", "", "", null);

        assertThat(mensajesDe("nombre", req)).contains("El nombre es obligatorio");
        assertThat(mensajesDe("correo", req)).contains("El correo es obligatorio");
        assertThat(mensajesDe("password", req)).contains("La contraseña es obligatoria");
        assertThat(mensajesDe("idRol", req)).contains("El rol es obligatorio");
    }

    @Test
    @DisplayName("Formatos inválidos de correo y contraseña reportan su mensaje")
    void formatosInvalidosReportanSuMensaje() {
        var correoMalo = new CrearUsuarioRequest(
                "Usuario", "no-es-un-correo", "Clave2026*", 4);
        assertThat(mensajesDe("correo", correoMalo))
                .contains("Debe ser un correo electrónico válido");

        var passwordCorta = new CrearUsuarioRequest(
                "Usuario", "ok@productionfood.local", "1234567", 4);
        assertThat(mensajesDe("password", passwordCorta))
                .contains("La contraseña debe tener entre 8 y 72 caracteres");
    }

    @Test
    @DisplayName("Límites de longitud de nombre, correo y contraseña")
    void limitesDeLongitud() {
        var nombreLargo = new CrearUsuarioRequest(
                "n".repeat(101), "ok@productionfood.local", "Clave2026*", 4);
        assertThat(mensajesDe("nombre", nombreLargo))
                .contains("El nombre no puede superar 100 caracteres");

        var passwordLarga = new CrearUsuarioRequest(
                "Usuario", "ok@productionfood.local", "a".repeat(73), 4);
        assertThat(mensajesDe("password", passwordLarga))
                .contains("La contraseña debe tener entre 8 y 72 caracteres");

        var correoLargo = new CrearUsuarioRequest(
                "Usuario", "a".repeat(95) + "@x.com", "Clave2026*", 4);
        assertThat(mensajesDe("correo", correoLargo)).isNotEmpty();
    }
}
