package co.edu.corposucre.productionfood.pedido.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Resumen de los pedidos de un período (HU-25).
 *
 * <p>Agrupa los pedidos del rango por situación de venta (R-03): {@code entregados} es la venta
 * consumada; {@code enCurso} reúne {@code PENDIENTE} y {@code EN_PREPARACION}, la venta
 * comprometida; {@code cancelados} es solo informativo y queda fuera de toda cifra de venta.
 *
 * <p>Lectura de las cifras, siguiendo el patrón {@code gastoEjecutado}/{@code gastoComprometido}
 * de HU-15 para no mezclar lo facturado con lo prometido:
 *
 * <ul>
 *   <li>{@code ventaBruta}: total de los entregados. No suma {@code enCurso}: sumarlos daría una
 *       cifra que no es ni lo facturado ni lo prometido.</li>
 *   <li>{@code devoluciones}: devoluciones {@code APROBADA} de los pedidos entregados, valoradas
 *       al {@code precio_unitario} de venta (R-04). Las pendientes y rechazadas no cuentan.</li>
 *   <li>{@code ventaNeta}: {@code ventaBruta - devoluciones}.</li>
 *   <li>{@code ticketPromedio}: {@code ventaBruta / entregados.cantidad}, a escala 2 con redondeo
 *       HALF_UP; {@code null} si no hubo entregados.</li>
 * </ul>
 *
 * <p>Todos los importes parten de {@code detalle_pedido.precio_unitario} (R-02). Un período sin
 * pedidos no es un error: responde cantidades en {@code 0} e importes en {@code 0.00}.
 *
 * @param periodo        rango del resumen; {@code null} (y sin serializar) si la petición no
 *                       trajo ningún filtro de fecha
 * @param entregados     pedidos {@code ENTREGADO} del período
 * @param enCurso        pedidos {@code PENDIENTE} y {@code EN_PREPARACION} del período
 * @param cancelados     pedidos {@code CANCELADO} del período; solo informativo
 * @param ventaBruta     total de los entregados
 * @param devoluciones   devoluciones aprobadas de los entregados
 * @param ventaNeta      venta bruta menos devoluciones
 * @param ticketPromedio venta bruta por pedido entregado; {@code null} sin entregados
 */
public record ResumenPedidosResponse(
        @JsonInclude(JsonInclude.Include.NON_NULL) Periodo periodo,
        TotalesEstado entregados,
        TotalesEstado enCurso,
        TotalesEstado cancelados,
        BigDecimal ventaBruta,
        BigDecimal devoluciones,
        BigDecimal ventaNeta,
        BigDecimal ticketPromedio) {

    /**
     * Rango inclusivo al que aplica el resumen; un campo es {@code null} cuando ese límite no se
     * envió.
     *
     * @param desde primer día incluido, o {@code null} sin límite inferior
     * @param hasta último día incluido, o {@code null} sin límite superior
     */
    public record Periodo(LocalDate desde, LocalDate hasta) {
    }

    /**
     * Cantidad de pedidos y total monetario de un grupo de estados dentro del período.
     *
     * @param cantidad número de pedidos del grupo; {@code 0} si el período no tiene
     * @param total    suma de {@code cantidad * precio_unitario} de sus líneas, a escala 2;
     *                 {@code 0.00} si el período no tiene
     */
    public record TotalesEstado(long cantidad, BigDecimal total) {
    }
}
