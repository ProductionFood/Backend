package co.edu.corposucre.productionfood.inventario;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Fila de {@code v_inventario_materia_prima} que está en alerta, tal como sale de la consulta y
 * antes de calcular el costo de reposición, que es responsabilidad del servicio.
 *
 * <p>{@code nivel} es {@code estado_stock} de la vista ({@code AGOTADO} o {@code BAJO}); no se
 * recalcula aquí. {@code ultimaActualizacion} es nula si la materia prima no tiene fila de
 * inventario.
 */
record AlertaStockFila(
        Integer idMateriaPrima,
        String nombre,
        String unidad,
        BigDecimal cantidadDisponible,
        BigDecimal stockMinimo,
        BigDecimal faltante,
        String nivel,
        BigDecimal costoUnitario,
        LocalDateTime ultimaActualizacion
) {
}
