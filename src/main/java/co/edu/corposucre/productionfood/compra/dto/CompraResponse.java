package co.edu.corposucre.productionfood.compra.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Respuesta con una compra y su proveedor (HU-13).
 *
 * <p>El proveedor se embebe como objeto {@link ProveedorRefResponse} para mostrarlo sin una
 * llamada extra, igual que las demás respuestas del proyecto.
 *
 * <p>{@code estado} viaja como {@code String} (el nombre canónico, por ejemplo
 * {@code "RECIBIDA"}): es lo que el contrato expone al consumidor y evita que un cambio de
 * empaquetado del enum se filtre al JSON.
 *
 * <p>{@code totalCompra} y {@code cantidadItems} son derivados de {@code detalle_compra}, no
 * columnas de la cabecera: una compra recién registrada responde {@code 0} y {@code 0} hasta que
 * HU-14 agrega sus líneas.
 *
 * <p>{@code editable} lo calcula el servicio a partir de la máquina de estados de
 * {@code EstadoCompra}: solo una compra {@code PENDIENTE} admite edición de cabecera o cambio de
 * estado, y el booleano le ahorra al consumidor reimplementar esa regla.
 */
public record CompraResponse(
        Integer idCompra,
        ProveedorRefResponse proveedor,
        LocalDate fecha,
        String estado,
        BigDecimal totalCompra,
        int cantidadItems,
        boolean editable) {
}
