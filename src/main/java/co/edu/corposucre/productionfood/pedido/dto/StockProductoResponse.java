package co.edu.corposucre.productionfood.pedido.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Stock disponible de un producto terminado (HU-17).
 *
 * <p>Agrega todos los lotes vendibles del producto: {@code cantidadDisponible} es la suma de
 * {@code lotes}, y {@code lotesActivos} cuántos contribuyen. {@code proximoVencimiento} es la
 * fecha del lote que FEFO consumiría primero, y sirve de aviso para venderlo antes de que caduque
 * (R-02, R-03).
 *
 * <p>{@code lotes} viene en orden FEFO: la vista no ordena por sí sola —MySQL ignora su
 * {@code ORDER BY}— y el orden lo aplica la consulta que la usa. El consumidor no debe
 * reordenar la lista.
 *
 * @param idProducto         producto consultado
 * @param nombre             nombre del producto
 * @param cantidadDisponible suma de las cantidades vendibles de todos los lotes
 * @param lotesActivos       número de lotes con saldo vendible
 * @param proximoVencimiento vencimiento del primer lote en FEFO
 * @param lotes              lotes vendibles, en orden FEFO
 */
public record StockProductoResponse(
    Integer idProducto,
    String nombre,
    BigDecimal cantidadDisponible,
    int lotesActivos,
    LocalDate proximoVencimiento,
    List<LoteDisponibleResponse> lotes) {}
