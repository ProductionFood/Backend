package co.edu.corposucre.productionfood.devolucion.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import co.edu.corposucre.productionfood.lote.dto.ProductoRefResponse;

/**
 * Respuesta con una devolución y su línea de pedido (HU-18).
 *
 * <p>El pedido se embebe como {@link PedidoRef} —con el cliente como texto plano, tal como
 * muestra el ejemplo de la especificación— y el producto como
 * {@link ProductoRefResponse} —el mismo tipo que usan HU-17 y HU-19— para mostrar de dónde
 * sale la devolución sin una consulta extra; la unidad del producto queda en {@code null} y
 * no se serializa, porque la devolución no la proyecta.
 *
 * <p>{@code devueltoPreviamente} y {@code disponibleParaDevolver} son derivados del acumulado
 * de la línea (R-03), no columnas de la tabla. Su semántica está fijada por la especificación:
 *
 * <ul>
 *   <li>{@code devueltoPreviamente = comprometidaLinea − (estado cuenta ? cantidadDevuelta : 0)},
 *       donde «cuenta» es que la devolución esté {@code APROBADA} o {@code PENDIENTE}: las
 *       rechazadas liberan la cantidad y no se informan como devueltas.</li>
 *   <li>{@code disponibleParaDevolver = cantidadPedida − comprometidaLinea}, siempre contra el
 *       acumulado y no contra esta devolución sola.</li>
 * </ul>
 *
 * <p>Ejemplo de la spec: una línea de 30 con una devolución nueva de 3 responde
 * {@code devueltoPreviamente = 0.00} y {@code disponibleParaDevolver = 27.00}.
 *
 * <p>{@code estado} viaja como {@code String} (el nombre canónico, por ejemplo
 * {@code "PENDIENTE"}): es lo que el contrato expone al consumidor, igual que en
 * {@code PedidoResponse}.
 *
 * @param idDevolucion          identificador de la devolución
 * @param pedido                pedido del que sale la devolución, con su cliente
 * @param producto              producto de la línea devuelta
 * @param cantidadPedida        cantidad original de la línea
 * @param cantidadDevuelta      cantidad devuelta en esta devolución
 * @param devueltoPreviamente   unidades ya devueltas o en trámite antes de esta, si cuenta
 * @param disponibleParaDevolver unidades de la línea aún devolvibles
 * @param motivo                motivo libre (R-07)
 * @param descripcion            detalle opcional de la devolución
 * @param fechaDevolucion       fecha de registro, fijada por la base (R-06)
 * @param estado                estado actual (R-04)
 */
public record DevolucionResponse(
        Integer idDevolucion,
        PedidoRef pedido,
        ProductoRefResponse producto,
        BigDecimal cantidadPedida,
        BigDecimal cantidadDevuelta,
        BigDecimal devueltoPreviamente,
        BigDecimal disponibleParaDevolver,
        String motivo,
        String descripcion,
        LocalDate fechaDevolucion,
        String estado) {

    /**
     * Referencia embebida al pedido del que sale la devolución.
     *
     * <p>El cliente viaja como nombre plano —igual que el ejemplo de la especificación— porque
     * la bandeja de devoluciones solo necesita con qué cliente mostrar el renglón.
     *
     * @param idPedido pedido de origen
     * @param cliente  nombre del cliente del pedido
     */
    public record PedidoRef(
            Integer idPedido,
            String cliente) {
    }
}
