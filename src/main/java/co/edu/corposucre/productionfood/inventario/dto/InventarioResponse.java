package co.edu.corposucre.productionfood.inventario.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Fila del inventario de materia prima tal como la ve el cliente (HU-10).
 *
 * <p>{@code estadoStock} no se calcula en la aplicación: lo trae la vista
 * {@code v_inventario_materia_prima} y toma uno de los valores {@code AGOTADO}, {@code BAJO} u
 * {@code OK} (R-05). Recalcularlo aquí duplicaría la regla y podría divergir de la base.
 *
 * <p>{@code faltante} también viene de la vista, que lo calcula como
 * {@code GREATEST(stock_minimo - cantidad_disponible, 0)}: cuánto falta para alcanzar el mínimo,
 * nunca un número negativo cuando el stock lo supera.
 *
 * <p>{@code fechaActualizacion} puede ser {@code null} únicamente si la materia prima no tuviera
 * fila de inventario, porque la vista la obtiene con un {@code LEFT JOIN}. No debería ocurrir por
 * la regla HU-08 R-01 (toda materia prima nace con su fila); se admite nulo como red de
 * seguridad y no se inventa una fecha que la consulta no aportó.
 *
 * <p>{@code activo} se deriva de la columna {@code estado} de la materia prima ({@code 1} es
 * activa) y es independiente del stock: una materia prima inactiva conserva sus existencias y se
 * muestra marcada como inactiva (R-04).
 *
 * <p>No se expone {@code costo_unitario}: la vista lo trae, pero el contrato de consulta de
 * inventario de HU-10 no lo incluye.
 */
public record InventarioResponse(
        Integer idMateriaPrima,
        String nombre,
        String unidad,
        BigDecimal cantidadDisponible,
        BigDecimal stockMinimo,
        BigDecimal faltante,
        String estadoStock,
        boolean activo,
        LocalDateTime fechaActualizacion
) {
}
