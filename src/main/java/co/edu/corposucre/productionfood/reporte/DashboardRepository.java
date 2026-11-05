package co.edu.corposucre.productionfood.reporte;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import co.edu.corposucre.productionfood.pedido.EstadoPedido;
import co.edu.corposucre.productionfood.produccion.EstadoPlan;
import co.edu.corposucre.productionfood.reporte.dto.ComprasPendientesResponse;
import co.edu.corposucre.productionfood.reporte.dto.ComprasPendientesResponse.MasAntigua;
import co.edu.corposucre.productionfood.reporte.dto.PedidoHoyResponse;
import co.edu.corposucre.productionfood.reporte.dto.PedidosHoyResponse;
import co.edu.corposucre.productionfood.reporte.dto.PorVencerResponse;
import co.edu.corposucre.productionfood.reporte.dto.ProduccionHoyResponse;
import co.edu.corposucre.productionfood.reporte.dto.VencimientoResponse;

/**
 * Consultas agregadas de solo lectura del dashboard (HU-24).
 *
 * <p>R-01: «hoy» es siempre {@code curdate()} del servidor MySQL, nunca la fecha de Java, para
 * que las tarjetas y los datos coincidan en una sola zona horaria. Cada tarjeta se apoya en un
 * índice de V2 §C-15 ({@code idx_pedidos_fecha}, {@code idx_compras_fecha},
 * {@code idx_planes_fecha}, {@code idx_lotes_vencimiento}). El stock bajo no está aquí: lo
 * aporta {@code AlertaService}.
 */
@Repository
public class DashboardRepository {

    private static final int ESCALA = 2;
    private static final BigDecimal CIEN = BigDecimal.valueOf(100);

    /**
     * Lotes de producto terminado con stock que vencen en {@code hoy + :dias} o antes (ya
     * vencidos incluidos, R-03). Lo comparten la tarjeta y el detalle paginado para que no
     * puedan divergir.
     */
    private static final String LOTES_POR_VENCER = """
            FROM lotes l
            JOIN productos pr ON pr.id_producto = l.id_producto
            JOIN inventario_producto_terminado i ON i.id_lote = l.id_lote
            WHERE l.id_producto IS NOT NULL AND i.cantidad_disponible > 0
              AND l.fecha_vencimiento <= DATE_ADD(curdate(), INTERVAL :dias DAY)
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public DashboardRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Instante actual de la base de datos; su fecha es el «hoy» del dashboard (R-01). */
    public LocalDateTime ahora() {
        return jdbc.getJdbcTemplate().queryForObject("SELECT NOW()", LocalDateTime.class);
    }

    /**
     * Pedidos con {@code fecha_pedido = curdate()}: conteo por estado (las cuatro claves, con 0)
     * y total vendido sobre {@code detalle_pedido}, sin contar los cancelados.
     */
    public PedidosHoyResponse pedidosDelDia() {
        var porEstado = ceros(EstadoPedido.values());
        var total = new BigDecimal[] {BigDecimal.ZERO};
        jdbc.query("""
                SELECT p.estado, COUNT(DISTINCT p.id_pedido) AS cantidad,
                       COALESCE(SUM(dp.cantidad * dp.precio_unitario), 0) AS total
                FROM pedidos p
                LEFT JOIN detalle_pedido dp ON dp.id_pedido = p.id_pedido
                WHERE p.fecha_pedido = curdate()
                GROUP BY p.estado
                """, rs -> {
            porEstado.put(rs.getString("estado"), rs.getLong("cantidad"));
            if (!EstadoPedido.CANCELADO.name().equals(rs.getString("estado"))) {
                total[0] = total[0].add(rs.getBigDecimal("total"));
            }
        });
        long cantidad = porEstado.values().stream().mapToLong(Long::longValue).sum();
        return new PedidosHoyResponse(cantidad, total[0].setScale(ESCALA, RoundingMode.HALF_UP),
                porEstado);
    }

    /**
     * Compras {@code PENDIENTE} sin filtro de fecha (CA-02): cantidad, valor total del detalle y
     * la más antigua (menor fecha, desempate por menor id), o {@code null} si no hay.
     */
    public ComprasPendientesResponse comprasPendientes() {
        var param = new MapSqlParameterSource();
        var resumen = jdbc.queryForObject("""
                SELECT COUNT(DISTINCT c.id_compra) AS cantidad,
                       COALESCE(SUM(d.cantidad * d.precio_unitario), 0) AS total
                FROM compras c
                LEFT JOIN detalle_compra d ON d.id_compra = c.id_compra
                WHERE c.estado = 'PENDIENTE'
                """, param, (rs, fila) -> new ComprasPendientesResponse(rs.getLong("cantidad"),
                        rs.getBigDecimal("total").setScale(ESCALA, RoundingMode.HALF_UP), null));
        var masAntigua = jdbc.query("""
                SELECT id_compra, fecha, DATEDIFF(curdate(), fecha) AS dias
                FROM compras
                WHERE estado = 'PENDIENTE'
                ORDER BY fecha, id_compra
                LIMIT 1
                """, param, (rs, fila) -> new MasAntigua(rs.getInt("id_compra"),
                        rs.getObject("fecha", LocalDate.class), rs.getLong("dias")))
                .stream().findFirst().orElse(null);
        return new ComprasPendientesResponse(resumen.cantidad(), resumen.total(), masAntigua);
    }

    /**
     * Planes con {@code fecha_produccion = curdate()}: conteo por estado (cuatro claves) y
     * avance promedio de los no cancelados. El avance de un plan es
     * {@code SUM(producida) / SUM(planificada) × 100} sobre sus líneas (0 sin líneas o con suma
     * 0); el promedio se redondea a escala 2 {@code HALF_UP}.
     */
    public ProduccionHoyResponse produccionDelDia() {
        var porEstado = ceros(EstadoPlan.values());
        var avances = new ArrayList<BigDecimal>();
        jdbc.query("""
                SELECT p.estado,
                       COALESCE(SUM(d.cantidad_producida), 0) AS producida,
                       COALESCE(SUM(d.cantidad_planificada), 0) AS planificada
                FROM planes_produccion p
                LEFT JOIN detalle_plan_produccion d ON d.id_plan = p.id_plan
                WHERE p.fecha_produccion = curdate()
                GROUP BY p.id_plan, p.estado
                """, rs -> {
            var estado = rs.getString("estado");
            porEstado.merge(estado, 1L, Long::sum);
            var planificada = rs.getBigDecimal("planificada");
            if (!EstadoPlan.CANCELADO.name().equals(estado)) {
                avances.add(planificada.signum() == 0 ? BigDecimal.ZERO
                        : rs.getBigDecimal("producida").multiply(CIEN)
                                .divide(planificada, 10, RoundingMode.HALF_UP));
            }
        });
        long cantidad = porEstado.values().stream().mapToLong(Long::longValue).sum();
        var promedio = avances.isEmpty() ? BigDecimal.ZERO
                : avances.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                        .divide(BigDecimal.valueOf(avances.size()), ESCALA, RoundingMode.HALF_UP);
        return new ProduccionHoyResponse(cantidad, porEstado, promedio.setScale(ESCALA));
    }

    /**
     * Lotes de producto terminado con stock que vencen en {@code hoy + dias} o antes, ya
     * vencidos incluidos (R-03), del más próximo al más lejano. Devuelve como máximo
     * {@code limite} items, pero {@code cantidad} y {@code vencidos} cuentan todos los lotes.
     * {@code disponible} suma las filas de inventario del lote (hoy hay una por lote, clave única).
     */
    public PorVencerResponse proximosAVencer(int dias, int limite) {
        long[] conteo = new long[2];
        var items = jdbc.query("""
                SELECT l.codigo_lote, pr.nombre, l.fecha_vencimiento,
                       DATEDIFF(l.fecha_vencimiento, curdate()) AS dias,
                       SUM(i.cantidad_disponible) AS disponible,
                       COUNT(*) OVER () AS total,
                       SUM(l.fecha_vencimiento < curdate()) OVER () AS vencidos
                """ + LOTES_POR_VENCER + """
                GROUP BY l.id_lote, l.codigo_lote, pr.nombre, l.fecha_vencimiento
                ORDER BY l.fecha_vencimiento, l.id_lote
                LIMIT :limite
                """, new MapSqlParameterSource().addValue("dias", dias).addValue("limite", limite),
                (rs, fila) -> {
                    conteo[0] = rs.getLong("total");
                    conteo[1] = rs.getLong("vencidos");
                    return new PorVencerResponse.Item(rs.getString("codigo_lote"),
                            rs.getString("nombre"), rs.getObject("fecha_vencimiento", LocalDate.class),
                            rs.getLong("dias"),
                            rs.getBigDecimal("disponible").setScale(ESCALA, RoundingMode.HALF_UP));
                });
        return new PorVencerResponse(conteo[0], conteo[1], items);
    }

    /**
     * Pedidos con {@code fecha_pedido = curdate()} (R-01), una página, con su cliente y su total
     * propio ({@code 0.00} sin líneas). {@code columnaOrden} es una columna ya resuelta por la
     * lista blanca del servicio, nunca texto del cliente; el desempate es el id.
     *
     * @param estado estado a filtrar, o {@code null} sin filtrar
     */
    public List<PedidoHoyResponse> pedidosDeHoy(String estado, String columnaOrden,
                                                boolean ascendente, int tamano, long desplazamiento) {
        return jdbc.query("""
                SELECT p.id_pedido, c.nombre AS cliente, p.estado, p.fecha_pedido,
                       p.fecha_entrega,
                       COALESCE(SUM(dp.cantidad * dp.precio_unitario), 0) AS total
                FROM pedidos p
                JOIN clientes c ON c.id_cliente = p.id_cliente
                LEFT JOIN detalle_pedido dp ON dp.id_pedido = p.id_pedido
                WHERE p.fecha_pedido = curdate() AND (:estado IS NULL OR p.estado = :estado)
                GROUP BY p.id_pedido, c.nombre, p.estado, p.fecha_pedido, p.fecha_entrega
                ORDER BY\s""" + columnaOrden + (ascendente ? " ASC" : " DESC") + """
                , p.id_pedido
                LIMIT :tamano OFFSET :desplazamiento
                """, new MapSqlParameterSource().addValue("estado", estado)
                        .addValue("tamano", tamano).addValue("desplazamiento", desplazamiento),
                (rs, fila) -> new PedidoHoyResponse(rs.getInt("id_pedido"),
                        rs.getString("cliente"), rs.getString("estado"),
                        rs.getObject("fecha_pedido", LocalDate.class),
                        rs.getObject("fecha_entrega", LocalDate.class),
                        rs.getBigDecimal("total").setScale(ESCALA, RoundingMode.HALF_UP)));
    }

    /** Total de pedidos de hoy que cumplen el filtro de {@link #pedidosDeHoy}. */
    public long contarPedidosDeHoy(String estado) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM pedidos
                WHERE fecha_pedido = curdate() AND (:estado IS NULL OR estado = :estado)
                """, new MapSqlParameterSource().addValue("estado", estado), Long.class);
    }

    /**
     * Lotes por vencer (misma condición que {@link #proximosAVencer}), una página. La columna
     * de orden la resuelve la lista blanca del servicio; el desempate es el id del lote.
     */
    public List<VencimientoResponse> vencimientos(int dias, String columnaOrden,
                                                  boolean ascendente, int tamano,
                                                  long desplazamiento) {
        return jdbc.query("""
                SELECT l.id_lote, l.codigo_lote, pr.nombre, l.fecha_vencimiento,
                       DATEDIFF(l.fecha_vencimiento, curdate()) AS dias,
                       SUM(i.cantidad_disponible) AS disponible
                """ + LOTES_POR_VENCER + """
                GROUP BY l.id_lote, l.codigo_lote, pr.nombre, l.fecha_vencimiento
                ORDER BY\s""" + columnaOrden + (ascendente ? " ASC" : " DESC") + """
                , l.id_lote
                LIMIT :tamano OFFSET :desplazamiento
                """, new MapSqlParameterSource().addValue("dias", dias)
                        .addValue("tamano", tamano).addValue("desplazamiento", desplazamiento),
                (rs, fila) -> new VencimientoResponse(rs.getInt("id_lote"),
                        rs.getString("codigo_lote"), rs.getString("nombre"),
                        rs.getObject("fecha_vencimiento", LocalDate.class), rs.getLong("dias"),
                        rs.getBigDecimal("disponible").setScale(ESCALA, RoundingMode.HALF_UP)));
    }

    /** Total de lotes que devuelve {@link #vencimientos} para el mismo horizonte. */
    public long contarVencimientos(int dias) {
        return jdbc.queryForObject("SELECT COUNT(DISTINCT l.id_lote) " + LOTES_POR_VENCER,
                new MapSqlParameterSource().addValue("dias", dias), Long.class);
    }

    private static <E extends Enum<E>> Map<String, Long> ceros(E[] valores) {
        return Arrays.stream(valores).collect(LinkedHashMap::new,
                (m, e) -> m.put(e.name(), 0L), Map::putAll);
    }
}
