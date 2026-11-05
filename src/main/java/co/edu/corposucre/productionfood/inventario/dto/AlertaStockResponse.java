package co.edu.corposucre.productionfood.inventario.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Materia prima en alerta de stock (HU-27).
 *
 * <p>{@code nivel} es {@code AGOTADO} (disponible 0) o {@code BAJO} (0 &lt; disponible &lt;
 * mínimo), tal como lo calcula la vista. {@code costoReposicion} es
 * {@code faltante × costoUnitario}, con escala 2.
 */
public record AlertaStockResponse(
        Integer idMateriaPrima,
        String nombre,
        String unidad,
        BigDecimal cantidadDisponible,
        BigDecimal stockMinimo,
        BigDecimal faltante,
        String nivel,
        BigDecimal costoUnitario,
        BigDecimal costoReposicion,
        LocalDateTime ultimaActualizacion
) {
}
