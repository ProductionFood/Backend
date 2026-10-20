package co.edu.corposucre.productionfood.cliente.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Respuesta de cliente del módulo de gestión de clientes (HU-05).
 *
 * <p>La anotación {@code @JsonInclude(ALWAYS)} es un override por DTO: la configuración global
 * {@code spring.jackson.default-property-inclusion: non_null} hace que un campo nulo
 * desaparezca del JSON en lugar de aparecer como {@code null}, y el contrato de HU-05 exige que
 * {@code contacto} y {@code telefono} se vean como {@code null} cuando el cliente no los tiene.
 * El ajuste global se conserva para el resto de módulos.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ClienteResponse(
    Integer idCliente,
    String nombre,
    String contacto,
    String telefono,
    boolean activo
) {}
