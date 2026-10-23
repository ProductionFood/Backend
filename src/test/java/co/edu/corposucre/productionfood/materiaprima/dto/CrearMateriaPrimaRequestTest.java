package co.edu.corposucre.productionfood.materiaprima.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;

class CrearMateriaPrimaRequestTest {

    private Validator validator;

    @BeforeEach
    void setUp() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    private CrearMateriaPrimaRequest solicitudValida() {
        return new CrearMateriaPrimaRequest(
                "Harina de trigo", 1, new BigDecimal("50.00"), new BigDecimal("3200.00"));
    }

    private Set<String> mensajesDe(String campo, CrearMateriaPrimaRequest req) {
        return validator.validate(req).stream()
                .filter(v -> v.getPropertyPath().toString().equals(campo))
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.toSet());
    }

    @Test
    @DisplayName("Una solicitud válida no produce violaciones")
    void solicitudValidaNoProduceViolaciones() {
        Set<ConstraintViolation<CrearMateriaPrimaRequest>> violaciones =
                validator.validate(solicitudValida());

        assertThat(violaciones).isEmpty();
    }

    @Test
    @DisplayName("El nombre nulo, vacío o en blanco reporta que es obligatorio")
    void nombreObligatorio() {
        for (String nombre : new String[] {"", "   ", null}) {
            var req = new CrearMateriaPrimaRequest(
                    nombre, 1, new BigDecimal("50.00"), new BigDecimal("3200.00"));

            assertThat(mensajesDe("nombre", req)).containsExactly("El nombre es obligatorio");
        }
    }

    @Test
    @DisplayName("El nombre admite 100 caracteres y rechaza 101")
    void nombreLimiteDeLongitud() {
        var enLimite = new CrearMateriaPrimaRequest(
                "a".repeat(100), 1, new BigDecimal("50.00"), new BigDecimal("3200.00"));
        var excedido = new CrearMateriaPrimaRequest(
                "a".repeat(101), 1, new BigDecimal("50.00"), new BigDecimal("3200.00"));

        assertThat(mensajesDe("nombre", enLimite)).isEmpty();
        assertThat(mensajesDe("nombre", excedido))
                .containsExactly("El nombre no puede superar 100 caracteres");
    }

    @Test
    @DisplayName("El nombre con espacios alrededor pasa la validación: el recorte lo hace el servicio")
    void nombreConEspaciosAlrededorPasaLaValidacion() {
        var req = new CrearMateriaPrimaRequest(
                "  Harina de trigo  ", 1, new BigDecimal("50.00"), new BigDecimal("3200.00"));

        assertThat(validator.validate(req)).isEmpty();
    }

    @Test
    @DisplayName("La unidad de medida nula reporta que es obligatoria")
    void idUnidadObligatorio() {
        var req = new CrearMateriaPrimaRequest(
                "Harina de trigo", null, new BigDecimal("50.00"), new BigDecimal("3200.00"));

        assertThat(mensajesDe("idUnidad", req))
                .containsExactly("La unidad de medida es obligatoria");
    }

    @Test
    @DisplayName("El stock mínimo nulo o negativo es inválido y el cero es válido (R-06)")
    void stockMinimoNuloONegativo() {
        var nulo = new CrearMateriaPrimaRequest(
                "Harina de trigo", 1, null, new BigDecimal("3200.00"));
        assertThat(mensajesDe("stockMinimo", nulo)).containsExactly("El stock mínimo es obligatorio");

        var negativo = new CrearMateriaPrimaRequest(
                "Harina de trigo", 1, new BigDecimal("-1"), new BigDecimal("3200.00"));
        assertThat(mensajesDe("stockMinimo", negativo))
                .containsExactly("El stock mínimo no puede ser negativo");

        var cero = new CrearMateriaPrimaRequest(
                "Harina de trigo", 1, BigDecimal.ZERO, new BigDecimal("3200.00"));
        assertThat(mensajesDe("stockMinimo", cero)).isEmpty();
    }

    @Test
    @DisplayName("El stock mínimo admite hasta 8 dígitos enteros y 2 decimales")
    void stockMinimoLimitesDeDecimalesYEnteros() {
        var enLimiteDeDecimales = new CrearMateriaPrimaRequest(
                "Harina de trigo", 1, new BigDecimal("3200.55"), new BigDecimal("3200.00"));
        assertThat(mensajesDe("stockMinimo", enLimiteDeDecimales)).isEmpty();

        var decimalesExcedidos = new CrearMateriaPrimaRequest(
                "Harina de trigo", 1, new BigDecimal("3200.555"), new BigDecimal("3200.00"));
        assertThat(mensajesDe("stockMinimo", decimalesExcedidos))
                .containsExactly("El stock mínimo admite máximo 8 dígitos enteros y 2 decimales");

        var enLimiteDeEnteros = new CrearMateriaPrimaRequest(
                "Harina de trigo", 1, new BigDecimal("99999999.99"), new BigDecimal("3200.00"));
        assertThat(mensajesDe("stockMinimo", enLimiteDeEnteros)).isEmpty();

        var enterosExcedidos = new CrearMateriaPrimaRequest(
                "Harina de trigo", 1, new BigDecimal("999999999.99"), new BigDecimal("3200.00"));
        assertThat(mensajesDe("stockMinimo", enterosExcedidos))
                .containsExactly("El stock mínimo admite máximo 8 dígitos enteros y 2 decimales");
    }

    @Test
    @DisplayName("El costo unitario nulo o negativo es inválido y el cero es válido (R-06)")
    void costoUnitarioNuloONegativo() {
        var nulo = new CrearMateriaPrimaRequest(
                "Harina de trigo", 1, new BigDecimal("50.00"), null);
        assertThat(mensajesDe("costoUnitario", nulo)).containsExactly("El costo unitario es obligatorio");

        var negativo = new CrearMateriaPrimaRequest(
                "Harina de trigo", 1, new BigDecimal("50.00"), new BigDecimal("-1"));
        assertThat(mensajesDe("costoUnitario", negativo))
                .containsExactly("El costo unitario no puede ser negativo");

        var cero = new CrearMateriaPrimaRequest(
                "Harina de trigo", 1, new BigDecimal("50.00"), BigDecimal.ZERO);
        assertThat(mensajesDe("costoUnitario", cero)).isEmpty();
    }

    @Test
    @DisplayName("El costo unitario admite hasta 8 dígitos enteros y 2 decimales")
    void costoUnitarioLimitesDeDecimalesYEnteros() {
        var enLimiteDeDecimales = new CrearMateriaPrimaRequest(
                "Harina de trigo", 1, new BigDecimal("50.00"), new BigDecimal("3200.55"));
        assertThat(mensajesDe("costoUnitario", enLimiteDeDecimales)).isEmpty();

        var decimalesExcedidos = new CrearMateriaPrimaRequest(
                "Harina de trigo", 1, new BigDecimal("50.00"), new BigDecimal("3200.555"));
        assertThat(mensajesDe("costoUnitario", decimalesExcedidos))
                .containsExactly("El costo unitario admite máximo 8 dígitos enteros y 2 decimales");

        var enLimiteDeEnteros = new CrearMateriaPrimaRequest(
                "Harina de trigo", 1, new BigDecimal("50.00"), new BigDecimal("99999999.99"));
        assertThat(mensajesDe("costoUnitario", enLimiteDeEnteros)).isEmpty();

        var enterosExcedidos = new CrearMateriaPrimaRequest(
                "Harina de trigo", 1, new BigDecimal("50.00"), new BigDecimal("999999999.99"));
        assertThat(mensajesDe("costoUnitario", enterosExcedidos))
                .containsExactly("El costo unitario admite máximo 8 dígitos enteros y 2 decimales");
    }
}
