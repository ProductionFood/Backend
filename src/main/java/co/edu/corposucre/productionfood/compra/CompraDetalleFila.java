package co.edu.corposucre.productionfood.compra;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * Fila plana de una compra unida a sus líneas de detalle (HU-15, R-06).
 *
 * <p>Es lo que {@link CompraRepository#buscarConDetalle} devuelve por cada fila que produce la
 * única consulta con {@code LEFT JOIN} a {@code detalle_compra}, {@code materias_primas} y
 * {@code unidades_medida}: la cabecera se repite en todas las filas de su compra y las columnas
 * del detalle quedan en {@code null} en la fila de una compra sin líneas, que el
 * {@code LEFT JOIN} conserva en vez de descartar. El servicio agrupa estas filas por
 * {@code id_compra} preservando el orden de la primera aparición de cada una.
 *
 * <p>Las escalas son las de las columnas de origen: {@code totalCompra} llega del
 * {@code SUM(cantidad * precio_unitario)} de MySQL —escala 4, porque el producto de dos
 * {@code decimal(10,2)} la tiene— y cantidad y precio de sus {@code decimal(10,2)}; el
 * constructor compacto las fija a 2 con {@code HALF_UP}, la del contrato monetario.
 *
 * @param idCompra           identificador de la compra
 * @param idProveedor        proveedor de la compra
 * @param fecha              fecha de la compra
 * @param estado             estado de la compra
 * @param nombreProveedor    nombre del proveedor, traído del {@code JOIN}
 * @param totalCompra        total derivado del detalle de la compra
 * @param cantidadItems      número de líneas de detalle de la compra
 * @param idMateriaPrima     materia prima de la línea, o {@code null} sin línea
 * @param idDetalleCompra    identificador de la línea, o {@code null} si la compra no tiene
 *                           líneas
 * @param cantidad           cantidad de la línea, o {@code null} sin línea
 * @param precioUnitario     precio pagado en la línea, o {@code null} sin línea
 * @param nombreMateriaPrima nombre de la materia prima de la línea, del {@code JOIN}
 * @param abreviaturaUnidad  abreviatura de la unidad de la materia prima, del {@code JOIN}
 * @param costoReferencia    {@code materias_primas.costo_unitario} vigente de la materia prima
 *                           (comparativo de HU-14 R-04), o {@code null} si no tiene costo
 */
public record CompraDetalleFila(
        Integer idCompra,
        Integer idProveedor,
        LocalDate fecha,
        EstadoCompra estado,
        String nombreProveedor,
        BigDecimal totalCompra,
        int cantidadItems,
        Integer idMateriaPrima,
        Integer idDetalleCompra,
        BigDecimal cantidad,
        BigDecimal precioUnitario,
        String nombreMateriaPrima,
        String abreviaturaUnidad,
        BigDecimal costoReferencia) {

    public CompraDetalleFila {
        totalCompra = aEscala(totalCompra);
        cantidad = aEscala(cantidad);
        precioUnitario = aEscala(precioUnitario);
    }

    /**
     * Indica si la fila trae línea de detalle: sin línea, el {@code LEFT JOIN} deja sus columnas
     * en {@code null} y el identificador es el único indicio fiable de presencia.
     *
     * @return {@code true} si la fila corresponde a una línea de {@code detalle_compra}
     */
    public boolean conDetalle() {
        return idDetalleCompra != null;
    }

    private static BigDecimal aEscala(BigDecimal valor) {
        return valor == null ? null : valor.setScale(2, RoundingMode.HALF_UP);
    }
}
