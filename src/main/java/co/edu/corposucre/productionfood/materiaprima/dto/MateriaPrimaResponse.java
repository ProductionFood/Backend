package co.edu.corposucre.productionfood.materiaprima.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import co.edu.corposucre.productionfood.common.dto.UnidadRefResponse;
import java.math.BigDecimal;

/**
 * Respuesta de materia prima del módulo de gestión de materias primas (HU-08).
 *
 * <p>La unidad de medida se embebe como objeto {@link UnidadRefResponse} y no solo como
 * {@code idUnidad}, de modo que el consumidor no necesita una llamada extra para saber la
 * abreviatura con la que se muestran las cantidades.
 *
 * <p>La anotación {@code @JsonInclude(ALWAYS)} es un override por DTO: la configuración global
 * {@code spring.jackson.default-property-inclusion: non_null} hace que un campo nulo desaparezca
 * del JSON, y el contrato de HU-08 exige que {@code cantidadDisponible} se vea siempre —una
 * materia prima sin fila de inventario se reporta como {@code null} o como 0 según el camino de
 * la consulta— en lugar de perderse del payload.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record MateriaPrimaResponse(
    Integer idMateriaPrima,
    String nombre,
    UnidadRefResponse unidad,
    BigDecimal stockMinimo,
    BigDecimal costoUnitario,
    boolean activo,
    BigDecimal cantidadDisponible) {}
