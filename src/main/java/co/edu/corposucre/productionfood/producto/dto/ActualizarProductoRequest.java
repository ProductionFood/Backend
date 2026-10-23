package co.edu.corposucre.productionfood.producto.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Petición de actualización de los datos editables de un producto (HU-11).
 *
 * <p>No incluye {@code estado}: el activo/inactivo se cambia por el endpoint dedicado con
 * {@code CambiarEstadoRequest}, igual que en materias primas y clientes.
 *
 * <p>Cambiar el precio aquí solo afecta a las ventas futuras (R-01): las líneas de pedido ya
 * registradas conservan el precio con el que se vendieron en {@code detalle_pedido.precio_unitario}
 * y no se recalculan.
 */
public record ActualizarProductoRequest(
    @NotBlank(message = "El nombre es obligatorio")
    @Size(max = 100, message = "El nombre no puede superar 100 caracteres")
    String nombre,

    @Size(max = 150, message = "La descripción no puede superar 150 caracteres")
    String descripcion,

    @NotNull(message = "El precio es obligatorio")
    @DecimalMin(value = "0.00", message = "El precio debe ser 0 o mayor")
    @DecimalMax(value = "99999999.99", message = "El precio no puede superar 99999999.99")
    BigDecimal precio,

    @NotNull(message = "La unidad de venta es obligatoria")
    Integer idUnidad) {}
