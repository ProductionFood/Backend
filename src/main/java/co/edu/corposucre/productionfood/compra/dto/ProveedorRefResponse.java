package co.edu.corposucre.productionfood.compra.dto;

/**
 * Referencia embebida al proveedor de una compra (HU-13).
 *
 * <p>Se embebe como objeto para que el consumidor no necesite una llamada extra para mostrar el
 * nombre del proveedor junto a la compra.
 */
public record ProveedorRefResponse(
        Integer idProveedor,
        String nombre) {
}
