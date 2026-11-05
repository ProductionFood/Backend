package co.edu.corposucre.productionfood.inventario.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Grupo de la sugerencia de compra (HU-27 R-06): lo que conviene pedir a un proveedor.
 *
 * <p>Decisión de diseño: las materias primas en alerta que nunca se han recibido en una compra
 * no tienen proveedor deducible; en lugar de omitirlas se devuelven en un grupo adicional con
 * {@code proveedor} {@code null}, para que la pantalla pueda avisar que se compran a criterio de
 * quien compra. Los demás grupos conservan el formato del contrato.
 *
 * <p>{@code totalEstimado} es la suma de los {@code costoEstimado} de los items del grupo.
 */
public record SugerenciaCompraResponse(
        ProveedorSugerido proveedor,
        List<ItemSugerido> items,
        BigDecimal totalEstimado
) {

    /** Proveedor de la última compra recibida de las materias primas del grupo. */
    public record ProveedorSugerido(Integer idProveedor, String nombre) {
    }

    /**
     * {@code cantidadSugerida} es el faltante más 20 %, redondeado hacia arriba a 2 decimales;
     * {@code costoEstimado} es {@code cantidadSugerida × costoUnitario}, con escala 2.
     */
    public record ItemSugerido(
            String materiaPrima,
            BigDecimal faltante,
            BigDecimal cantidadSugerida,
            BigDecimal costoEstimado
    ) {
    }
}
