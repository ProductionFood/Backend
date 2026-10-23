package co.edu.corposucre.productionfood.lote.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;

class CrearLoteMateriaPrimaRequestTest {

    private Validator validator;

    @BeforeEach
    void setUp() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    private CrearLoteMateriaPrimaRequest solicitudValida() {
        return new CrearLoteMateriaPrimaRequest("H-2026-0912", 5, null,
                LocalDate.of(2026, 9, 10), LocalDate.of(2027, 3, 10),
                new BigDecimal("100.00"));
    }

    private Set<String> mensajesDe(String campo, CrearLoteMateriaPrimaRequest req) {
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
    @DisplayName("El código nulo, vacío o en blanco reporta que es obligatorio")
    void codigoObligatorio() {
        for (String codigo : new String[] {"", "   ", null}) {
            var req = new CrearLoteMateriaPrimaRequest(codigo, 5, null,
                    LocalDate.of(2026, 9, 10), LocalDate.of(2027, 3, 10),
                    new BigDecimal("100.00"));

            assertThat(mensajesDe("codigoLote", req))
                    .containsExactly("El código del lote es obligatorio");
        }
    }

    @Test
    @DisplayName("El código admite 30 caracteres y rechaza 31 (CP-07)")
    void codigoLimiteDeLongitud() {
        var treinta = new CrearLoteMateriaPrimaRequest("A".repeat(30), 5, null,
                LocalDate.of(2026, 9, 10), LocalDate.of(2027, 3, 10),
                new BigDecimal("100.00"));
        var treintaYUno = new CrearLoteMateriaPrimaRequest("A".repeat(31), 5, null,
                LocalDate.of(2026, 9, 10), LocalDate.of(2027, 3, 10),
                new BigDecimal("100.00"));

        assertThat(mensajesDe("codigoLote", treinta)).isEmpty();
        assertThat(mensajesDe("codigoLote", treintaYUno))
                .containsExactly("El código del lote no puede superar 30 caracteres");
    }

    @Test
    @DisplayName("La materia prima es obligatoria")
    void materiaPrimaObligatoria() {
        var req = new CrearLoteMateriaPrimaRequest("H-2026-0912", null, null,
                LocalDate.of(2026, 9, 10), LocalDate.of(2027, 3, 10),
                new BigDecimal("100.00"));

        assertThat(mensajesDe("idMateriaPrima", req))
                .containsExactly("La materia prima es obligatoria");
    }

    @Test
    @DisplayName("La fecha de producción es obligatoria")
    void fechaProduccionObligatoria() {
        var req = new CrearLoteMateriaPrimaRequest("H-2026-0912", 5, null,
                null, LocalDate.of(2027, 3, 10), new BigDecimal("100.00"));

        assertThat(mensajesDe("fechaProduccion", req))
                .containsExactly("La fecha de producción es obligatoria");
    }

    @Test
    @DisplayName("La fecha de vencimiento es obligatoria (CP-11)")
    void fechaVencimientoObligatoria() {
        var req = new CrearLoteMateriaPrimaRequest("H-2026-0912", 5, null,
                LocalDate.of(2026, 9, 10), null, new BigDecimal("100.00"));

        assertThat(mensajesDe("fechaVencimiento", req))
                .containsExactly("La fecha de vencimiento es obligatoria");
    }

    @Test
    @DisplayName("La cantidad es obligatoria")
    void cantidadObligatoria() {
        var req = new CrearLoteMateriaPrimaRequest("H-2026-0912", 5, null,
                LocalDate.of(2026, 9, 10), LocalDate.of(2027, 3, 10), null);

        assertThat(mensajesDe("cantidad", req)).containsExactly("La cantidad es obligatoria");
    }

    @Test
    @DisplayName("La cantidad con tres decimales se rechaza y con dos es válida")
    void cantidadLimiteDeDecimales() {
        var tresDecimales = new CrearLoteMateriaPrimaRequest("H-2026-0912", 5, null,
                LocalDate.of(2026, 9, 10), LocalDate.of(2027, 3, 10),
                new BigDecimal("100.123"));
        var dosDecimales = new CrearLoteMateriaPrimaRequest("H-2026-0912", 5, null,
                LocalDate.of(2026, 9, 10), LocalDate.of(2027, 3, 10),
                new BigDecimal("100.12"));

        assertThat(mensajesDe("cantidad", tresDecimales))
                .containsExactly("La cantidad admite máximo 8 dígitos enteros y 2 decimales");
        assertThat(mensajesDe("cantidad", dosDecimales)).isEmpty();
    }

    @Test
    @DisplayName("El detalle de compra es opcional")
    void detalleCompraOpcional() {
        var sinDetalle = new CrearLoteMateriaPrimaRequest("H-2026-0912", 5, null,
                LocalDate.of(2026, 9, 10), LocalDate.of(2027, 3, 10),
                new BigDecimal("100.00"));
        var conDetalle = new CrearLoteMateriaPrimaRequest("H-2026-0912", 5, 42,
                LocalDate.of(2026, 9, 10), LocalDate.of(2027, 3, 10),
                new BigDecimal("100.00"));

        assertThat(mensajesDe("idDetalleCompra", sinDetalle)).isEmpty();
        assertThat(mensajesDe("idDetalleCompra", conDetalle)).isEmpty();
        assertThat(validator.validate(sinDetalle)).isEmpty();
        assertThat(validator.validate(conDetalle)).isEmpty();
    }
}
