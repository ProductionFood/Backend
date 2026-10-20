package co.edu.corposucre.productionfood.proveedor.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Respuesta de proveedor del módulo de gestión de proveedores (HU-06).
 *
 * <p>La anotación {@code @JsonInclude(ALWAYS)} es un override por DTO: la configuración global
 * {@code spring.jackson.default-property-inclusion: non_null} hace que un campo nulo
 * desaparezca del JSON en lugar de aparecer como {@code null}, y el contrato de HU-06 exige que
 * {@code contacto}, {@code telefono} y {@code correo} se vean como {@code null} cuando el
 * proveedor no los tiene. El ajuste global se conserva para el resto de módulos.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ProveedorResponse(
    Integer idProveedor,
    String nombre,
    String contacto,
    String telefono,
    String correo,
    boolean activo
) {}
