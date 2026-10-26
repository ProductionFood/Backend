package co.edu.corposucre.productionfood.compra.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import co.edu.corposucre.productionfood.compra.EstadoCompra;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;

class CrearCompraRequestTest {

    private Validator validator;

    @BeforeEach
    void setUp() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    private Set<String> mensajesDe(String campo, CrearCompraRequest req) {
        return validator.validate(req).stream()
                .filter(v -> v.getPropertyPath().toString().equals(campo))
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.toSet());
    }

    @Test
    @DisplayName("Una solicitud válida no produce violaciones")
    void solicitudValidaNoProduceViolaciones() {
        var req = new CrearCompraRequest(5, LocalDate.of(2026, 10, 20), EstadoCompra.PENDIENTE);

        assertThat(validator.validate(req)).isEmpty();
    }

    @Test
    @DisplayName("El proveedor es obligatorio")
    void proveedorObligatorio() {
        var req = new CrearCompraRequest(null, LocalDate.of(2026, 10, 20), null);

        assertThat(mensajesDe("idProveedor", req))
                .containsExactly("El proveedor es obligatorio");
    }

    @Test
    @DisplayName("La fecha es opcional: si se omite, el servicio usará hoy (CP-04)")
    void fechaOpcional() {
        var sinFecha = new CrearCompraRequest(5, null, EstadoCompra.PENDIENTE);
        var conFecha = new CrearCompraRequest(5, LocalDate.of(2026, 10, 20),
                EstadoCompra.PENDIENTE);

        assertThat(mensajesDe("fecha", sinFecha)).isEmpty();
        assertThat(validator.validate(sinFecha)).isEmpty();
        assertThat(validator.validate(conFecha)).isEmpty();
    }

    @Test
    @DisplayName("El estado es obligatorio: sin él hay una violación de campo")
    void estadoObligatorio() {
        var sinEstado = new CrearCompraRequest(5, LocalDate.of(2026, 10, 20), null);
        var conEstado = new CrearCompraRequest(5, LocalDate.of(2026, 10, 20),
                EstadoCompra.PENDIENTE);

        assertThat(mensajesDe("estado", sinEstado))
                .containsExactly("El estado es obligatorio");
        assertThat(validator.validate(conEstado)).isEmpty();
    }

    @Test
    @DisplayName("Un estado canónico no PENDIENTE pasa la validación de forma: lo rechaza el servicio")
    void estadoCanonicoDistintoDePendienteNoViolaelDto() {
        var req = new CrearCompraRequest(5, LocalDate.of(2026, 10, 20), EstadoCompra.RECIBIDA);

        assertThat(validator.validate(req)).isEmpty();
    }
}
