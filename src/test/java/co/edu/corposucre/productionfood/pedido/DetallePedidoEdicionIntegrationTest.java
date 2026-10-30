package co.edu.corposucre.productionfood.pedido;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;

import co.edu.corposucre.productionfood.common.error.ConflictoNegocioException;
import co.edu.corposucre.productionfood.inventario.InventarioPtService;
import co.edu.corposucre.productionfood.pedido.dto.ActualizarCantidadRequest;
import co.edu.corposucre.productionfood.pedido.dto.AgregarDetallePedidoRequest;
import co.edu.corposucre.productionfood.pedido.dto.CambiarEstadoPedidoRequest;
import co.edu.corposucre.productionfood.pedido.dto.DetallePedidoResponse;

/**
 * Prueba de integración de la edición y eliminación de líneas de pedido contra MySQL
 * (HU-17, PR-4).
 *
 * <p>Usa el contexto real de Spring —servicios, repositorios, transacciones y bloqueos— porque
 * lo que se prueba es el comportamiento del inventario y del kardex en la base: el ajuste por
 * diferencia (R-07), la devolución completa al eliminar (R-08), el neteo al cancelar y la
 * serialización de dos ventas concurrentes por la última unidad (R-05). El proyecto no usa
 * Testcontainers ni H2: la única base soportada es la del {@code docker-compose}, y la prueba se
 * omite con {@code assumeTrue} si no responde.
 *
 * <p>Cada prueba siembra sus propios productos, lotes, inventario y pedidos con un sufijo UUID y
 * los elimina en orden inverso al de las llaves foráneas.
 */
@SpringBootTest
class DetallePedidoEdicionIntegrationTest {

    private static final String URL = "jdbc:mysql://"
            + entorno("DB_HOST", "127.0.0.1") + ":" + entorno("DB_PORT", "3306")
            + "/" + entorno("DB_NAME", "productionfood")
            + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=America/Bogota";
    private static final String USUARIO = entorno("DB_USER", "root");
    private static final String CLAVE = entorno("DB_PASSWORD", "local");

    @Autowired
    private DetallePedidoService detallePedidoService;

    @Autowired
    private PedidoService pedidoService;

    @Autowired
    private InventarioPtService inventarioPtService;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    private final List<Integer> idsLote = new ArrayList<>();
    private final List<Integer> idsProducto = new ArrayList<>();
    private final List<Integer> idsPedido = new ArrayList<>();
    private Integer idUnidad;
    private Integer idCliente;
    private String sufijo;

    private static String entorno(String nombre, String defecto) {
        String valor = System.getenv(nombre);
        return valor == null || valor.isBlank() ? defecto : valor;
    }

    @BeforeAll
    static void laBaseDelProyectoEstaDisponible() {
        boolean disponible;
        try (var conexion = java.sql.DriverManager.getConnection(URL, USUARIO, CLAVE);
             var sentencia = conexion.createStatement()) {
            sentencia.executeQuery("SELECT 1 FROM inventario_producto_terminado LIMIT 1").close();
            disponible = true;
        } catch (SQLException e) {
            disponible = false;
        }
        assumeTrue(disponible,
                "MySQL no responde en " + URL + " con la tabla de inventario; se omite.");
    }

    @BeforeEach
    void setUp() {
        sufijo = "-" + UUID.randomUUID().toString().substring(0, 8);

        idUnidad = insertarConId("""
                INSERT INTO unidades_medida (nombre, abreviatura)
                VALUES (:nombre, :abreviatura)
                """, new MapSqlParameterSource()
                        .addValue("nombre", "Unidad HU-17 edición" + sufijo)
                        .addValue("abreviatura", "u17e" + sufijo));

        idCliente = insertarConId("""
                INSERT INTO clientes (nombre, estado) VALUES (:nombre, 1)
                """, new MapSqlParameterSource()
                        .addValue("nombre", "Cliente HU-17 edición" + sufijo));
    }

    @AfterEach
    void tearDown() {
        for (var idPedido : idsPedido) {
            jdbc.update("""
                    DELETE FROM bitacora
                    WHERE tabla_afectada = 'detalle_pedido'
                      AND detalle LIKE :patron
                    """, new MapSqlParameterSource()
                    .addValue("patron", "%pedido " + idPedido));
            jdbc.update("""
                    DELETE FROM bitacora
                    WHERE tabla_afectada = 'pedidos'
                      AND accion = 'CAMBIAR_ESTADO'
                      AND detalle = :detalle
                    """, new MapSqlParameterSource()
                    .addValue("detalle", "Pedido " + idPedido + " a CANCELADO"));
        }
        if (!idsLote.isEmpty()) {
            jdbc.update("DELETE FROM movimientos_inventario_pt WHERE id_lote IN (:ids)",
                    new MapSqlParameterSource().addValue("ids", idsLote));
            jdbc.update("DELETE FROM inventario_producto_terminado WHERE id_lote IN (:ids)",
                    new MapSqlParameterSource().addValue("ids", idsLote));
        }
        if (!idsPedido.isEmpty()) {
            jdbc.update("DELETE FROM detalle_pedido WHERE id_pedido IN (:ids)",
                    new MapSqlParameterSource().addValue("ids", idsPedido));
            jdbc.update("DELETE FROM pedidos WHERE id_pedido IN (:ids)",
                    new MapSqlParameterSource().addValue("ids", idsPedido));
        }
        if (!idsLote.isEmpty()) {
            jdbc.update("DELETE FROM lotes WHERE id_lote IN (:ids)",
                    new MapSqlParameterSource().addValue("ids", idsLote));
        }
        if (!idsProducto.isEmpty()) {
            jdbc.update("DELETE FROM productos WHERE id_producto IN (:ids)",
                    new MapSqlParameterSource().addValue("ids", idsProducto));
        }
        if (idCliente != null) {
            jdbc.update("DELETE FROM clientes WHERE id_cliente = :id",
                    new MapSqlParameterSource().addValue("id", idCliente));
        }
        if (idUnidad != null) {
            jdbc.update("DELETE FROM unidades_medida WHERE id_unidad = :id",
                    new MapSqlParameterSource().addValue("id", idUnidad));
        }
    }

    @Test
    @DisplayName("CP-16/R-07: aumentar de 10 a 15 deja una sola salida de 5 y no devuelve nada")
    void aumentarLaCantidadDescuentaSoloLaDiferencia() {
        var idProducto = insertarProducto("1200.00");
        var lote = insertarLote(idProducto, "100.00", 5);
        var idPedido = insertarPedido(EstadoPedido.PENDIENTE);

        var alta = detallePedidoService.agregar(idPedido,
                new AgregarDetallePedidoRequest(idProducto, new BigDecimal("10")));
        assertThat(saldoDe(lote.idLote())).isEqualByComparingTo("90.00");

        var editada = detallePedidoService.actualizarCantidad(idPedido, alta.idDetallePedido(),
                new ActualizarCantidadRequest(new BigDecimal("15")));

        assertThat(editada.cantidad()).isEqualByComparingTo("15.00");
        assertThat(editada.cantidad().scale()).isEqualTo(2);
        assertThat(saldoDe(lote.idLote())).isEqualByComparingTo("85.00");

        var movimientos = movimientosDeOrigen(alta.idDetallePedido());
        assertThat(movimientos).hasSize(2);
        assertThat(movimientos.get(0).tipo()).isEqualTo("SALIDA_VENTA");
        assertThat(movimientos.get(0).cantidad()).isEqualByComparingTo("10.00");
        assertThat(movimientos.get(1).tipo()).isEqualTo("SALIDA_VENTA");
        assertThat(movimientos.get(1).cantidad()).isEqualByComparingTo("5.00");
        assertThat(movimientos.get(1).idLote()).isEqualTo(lote.idLote());
        // No se devolvió todo para volver a descontar: no hay ENTRADA ni salida de 15.
        assertThat(movimientos).noneMatch(m -> "ENTRADA_DEVOLUCION".equals(m.tipo()));
        assertThat(movimientos).noneMatch(
                m -> m.cantidad().compareTo(new BigDecimal("15.00")) == 0);
    }

    @Test
    @DisplayName("CP-17/R-07: reducir de 10 a 6 devuelve 4 al lote de origen y deja la línea en 6")
    void reducirLaCantidadDevuelveSoloLaDiferencia() {
        var idProducto = insertarProducto("1200.00");
        var loteA = insertarLote(idProducto, "12.00", 5);
        var loteB = insertarLote(idProducto, "40.00", 7);
        var idPedido = insertarPedido(EstadoPedido.PENDIENTE);

        var alta = detallePedidoService.agregar(idPedido,
                new AgregarDetallePedidoRequest(idProducto, new BigDecimal("10")));
        assertThat(alta.lotesConsumidos()).hasSize(1);
        assertThat(saldoDe(loteA.idLote())).isEqualByComparingTo("2.00");
        assertThat(inventarioPtService.stockDisponible(idProducto)).isEqualByComparingTo("42.00");

        var editada = detallePedidoService.actualizarCantidad(idPedido, alta.idDetallePedido(),
                new ActualizarCantidadRequest(new BigDecimal("6")));

        assertThat(editada.cantidad()).isEqualByComparingTo("6.00");
        assertThat(editada.lotesConsumidos()).hasSize(1);
        assertThat(editada.lotesConsumidos().get(0).cantidad()).isEqualByComparingTo("6.00");
        assertThat(saldoDe(loteA.idLote())).isEqualByComparingTo("6.00");
        assertThat(saldoDe(loteB.idLote())).isEqualByComparingTo("40.00");
        assertThat(inventarioPtService.stockDisponible(idProducto)).isEqualByComparingTo("46.00");

        var movimientos = movimientosDeOrigen(alta.idDetallePedido());
        assertThat(movimientos).hasSize(2);
        assertThat(movimientos.get(0).tipo()).isEqualTo("SALIDA_VENTA");
        assertThat(movimientos.get(0).cantidad()).isEqualByComparingTo("10.00");
        assertThat(movimientos.get(1).tipo()).isEqualTo("ENTRADA_DEVOLUCION");
        assertThat(movimientos.get(1).cantidad()).isEqualByComparingTo("4.00");
        assertThat(movimientos.get(1).idLote()).isEqualTo(loteA.idLote());
    }

    @Test
    @DisplayName("CP-18: aumentar sin stock responde 409 y deja la línea y el stock intactos")
    void aumentarSinStockNoCambiaLaLineaNiElStock() {
        var idProducto = insertarProducto("1200.00");
        var lote = insertarLote(idProducto, "10.00", 5);
        var idPedido = insertarPedido(EstadoPedido.PENDIENTE);
        var alta = detallePedidoService.agregar(idPedido,
                new AgregarDetallePedidoRequest(idProducto, new BigDecimal("10")));

        assertThat(saldoDe(lote.idLote())).isEqualByComparingTo("0.00");

        assertThatThrownBy(() -> detallePedidoService.actualizarCantidad(idPedido,
                alta.idDetallePedido(), new ActualizarCantidadRequest(new BigDecimal("200"))))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "STOCK_INSUFICIENTE")
                .hasMessageContaining("Solo hay 0 unidades disponibles");

        assertThat(cantidadDeLaLinea(alta.idDetallePedido())).isEqualByComparingTo("10.00");
        assertThat(saldoDe(lote.idLote())).isEqualByComparingTo("0.00");
        assertThat(movimientosDeOrigen(alta.idDetallePedido())).hasSize(1);
    }

    @Test
    @DisplayName("CP-19/R-08: eliminar una línea de 30 devuelve 12 y 18 a sus lotes de origen")
    void eliminarLaLineaDevuelveTodoElStockALosLotesDeOrigen() {
        var idProducto = insertarProducto("1200.00");
        var loteA = insertarLote(idProducto, "12.00", 5);
        var loteB = insertarLote(idProducto, "18.00", 7);
        var idPedido = insertarPedido(EstadoPedido.PENDIENTE);
        var alta = detallePedidoService.agregar(idPedido,
                new AgregarDetallePedidoRequest(idProducto, new BigDecimal("30")));

        assertThat(saldoDe(loteA.idLote())).isEqualByComparingTo("0.00");
        assertThat(saldoDe(loteB.idLote())).isEqualByComparingTo("0.00");

        detallePedidoService.eliminar(idPedido, alta.idDetallePedido());

        assertThat(cantidadLineas(idPedido)).isZero();
        assertThat(saldoDe(loteA.idLote())).isEqualByComparingTo("12.00");
        assertThat(saldoDe(loteB.idLote())).isEqualByComparingTo("18.00");
        assertThat(inventarioPtService.stockDisponible(idProducto)).isEqualByComparingTo("30.00");

        var devoluciones = movimientosDeOrigen(alta.idDetallePedido()).stream()
                .filter(m -> "ENTRADA_DEVOLUCION".equals(m.tipo()))
                .toList();
        assertThat(devoluciones).hasSize(2);
        assertThat(devoluciones)
                .extracting(Movimiento::idLote, Movimiento::cantidad)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(loteA.idLote(),
                                new BigDecimal("12.00")),
                        org.assertj.core.groups.Tuple.tuple(loteB.idLote(),
                                new BigDecimal("18.00")));
    }

    @Test
    @DisplayName("regresión: reducir y luego cancelar devuelve solo el neto, sin duplicar stock")
    void cancelarUnPedidoConLineaReducidaDevuelveSoloElNeto() {
        var idProducto = insertarProducto("1200.00");
        var lote = insertarLote(idProducto, "10.00", 5);
        var idPedido = insertarPedido(EstadoPedido.PENDIENTE);
        var alta = detallePedidoService.agregar(idPedido,
                new AgregarDetallePedidoRequest(idProducto, new BigDecimal("10")));
        detallePedidoService.actualizarCantidad(idPedido, alta.idDetallePedido(),
                new ActualizarCantidadRequest(new BigDecimal("6")));

        assertThat(saldoDe(lote.idLote())).isEqualByComparingTo("4.00");

        pedidoService.cambiarEstado(idPedido,
                new CambiarEstadoPedidoRequest(EstadoPedido.CANCELADO));

        assertThat(saldoDe(lote.idLote())).isEqualByComparingTo("10.00");

        var movimientos = movimientosDelLote(lote.idLote());
        assertThat(movimientos).hasSize(3);
        assertThat(movimientos.get(0).tipo()).isEqualTo("SALIDA_VENTA");
        assertThat(movimientos.get(0).cantidad()).isEqualByComparingTo("10.00");
        assertThat(movimientos.get(0).tablaOrigen()).isEqualTo("detalle_pedido");
        assertThat(movimientos.get(1).tipo()).isEqualTo("ENTRADA_DEVOLUCION");
        assertThat(movimientos.get(1).cantidad()).isEqualByComparingTo("4.00");
        assertThat(movimientos.get(1).tablaOrigen()).isEqualTo("detalle_pedido");
        assertThat(movimientos.get(2).tipo()).isEqualTo("ENTRADA_DEVOLUCION");
        assertThat(movimientos.get(2).cantidad()).isEqualByComparingTo("6.00");
        assertThat(movimientos.get(2).tablaOrigen()).isEqualTo("pedidos");
    }

    @Test
    @DisplayName("CP-09/R-05: dos ventas simultáneas del último pan dejan una sola salida y cero stock")
    void dosVentasSimultaneasPorLaUltimaUnidadNoDejanStockNegativo() throws Exception {
        var idProducto = insertarProducto("1200.00");
        var lote = insertarLote(idProducto, "1.00", 5);
        var pedidoA = insertarPedido(EstadoPedido.PENDIENTE);
        var pedidoB = insertarPedido(EstadoPedido.PENDIENTE);

        var listo = new CountDownLatch(1);
        var ejecutor = Executors.newFixedThreadPool(2);
        try {
            Future<DetallePedidoResponse> ventaA = ejecutor.submit(() -> {
                listo.await();
                return detallePedidoService.agregar(pedidoA,
                        new AgregarDetallePedidoRequest(idProducto, BigDecimal.ONE));
            });
            Future<DetallePedidoResponse> ventaB = ejecutor.submit(() -> {
                listo.await();
                return detallePedidoService.agregar(pedidoB,
                        new AgregarDetallePedidoRequest(idProducto, BigDecimal.ONE));
            });
            listo.countDown();

            var exitosas = new ArrayList<DetallePedidoResponse>();
            var fallidas = new ArrayList<Throwable>();
            for (var futuro : List.of(ventaA, ventaB)) {
                try {
                    exitosas.add(futuro.get(20, TimeUnit.SECONDS));
                } catch (ExecutionException e) {
                    fallidas.add(e.getCause());
                }
            }

            assertThat(exitosas).hasSize(1);
            assertThat(fallidas).hasSize(1);
            assertThat(fallidas.get(0))
                    .isInstanceOf(ConflictoNegocioException.class)
                    .hasFieldOrPropertyWithValue("codigo", "STOCK_INSUFICIENTE");
        } finally {
            ejecutor.shutdownNow();
            ejecutor.awaitTermination(10, TimeUnit.SECONDS);
        }

        assertThat(saldoDe(lote.idLote())).isEqualByComparingTo("0.00");
        assertThat(saldosNegativosDe(idProducto)).isZero();
        assertThat(cantidadLineasDe(pedidoA, pedidoB)).isEqualTo(1);
        assertThat(cantidadSalidasDe(pedidoA, pedidoB)).isEqualTo(1);
    }

    private Integer insertarProducto(String precio) {
        var id = insertarConId("""
                INSERT INTO productos (nombre, precio, id_unidad, estado)
                VALUES (:nombre, :precio, :idUnidad, 1)
                """, new MapSqlParameterSource()
                        .addValue("nombre", "Producto HU-17 edición" + sufijo)
                        .addValue("precio", new BigDecimal(precio))
                        .addValue("idUnidad", idUnidad));
        idsProducto.add(id);
        return id;
    }

    /**
     * Siembra un lote y su fila de inventario. {@code diasVencimiento} se suma a hoy: negativo
     * siembra un lote vencido, que la vista de stock excluye (R-03).
     */
    private LoteSembrado insertarLote(Integer idProducto, String cantidad, int diasVencimiento) {
        var codigo = "L17E" + sufijo + "-" + UUID.randomUUID().toString().substring(0, 6);
        var id = insertarConId("""
                INSERT INTO lotes (codigo_lote, id_producto, fecha_produccion, fecha_vencimiento,
                                   cantidad)
                VALUES (:codigo, :idProducto, :produccion, :vencimiento, :cantidad)
                """, new MapSqlParameterSource()
                        .addValue("codigo", codigo)
                        .addValue("idProducto", idProducto)
                        .addValue("produccion", LocalDate.now().minusDays(30))
                        .addValue("vencimiento", LocalDate.now().plusDays(diasVencimiento))
                        .addValue("cantidad", new BigDecimal(cantidad)));
        idsLote.add(id);
        jdbc.update("""
                INSERT INTO inventario_producto_terminado (id_lote, cantidad_disponible)
                VALUES (:idLote, :cantidad)
                """, new MapSqlParameterSource()
                .addValue("idLote", id)
                .addValue("cantidad", new BigDecimal(cantidad)));
        return new LoteSembrado(id, codigo);
    }

    private Integer insertarPedido(EstadoPedido estado) {
        var id = insertarConId("""
                INSERT INTO pedidos (id_cliente, fecha_pedido, estado)
                VALUES (:idCliente, :fecha, :estado)
                """, new MapSqlParameterSource()
                        .addValue("idCliente", idCliente)
                        .addValue("fecha", LocalDate.now())
                        .addValue("estado", estado.name()));
        idsPedido.add(id);
        return id;
    }

    private BigDecimal saldoDe(Integer idLote) {
        return jdbc.queryForObject("""
                SELECT cantidad_disponible
                FROM inventario_producto_terminado
                WHERE id_lote = :idLote
                """, new MapSqlParameterSource().addValue("idLote", idLote), BigDecimal.class);
    }

    private BigDecimal cantidadDeLaLinea(Integer idDetalle) {
        return jdbc.queryForObject("""
                SELECT cantidad FROM detalle_pedido WHERE id_detalle_pedido = :idDetalle
                """, new MapSqlParameterSource().addValue("idDetalle", idDetalle),
                BigDecimal.class);
    }

    private long cantidadLineas(Integer idPedido) {
        return cantidadLineasDe(idPedido);
    }

    private long cantidadLineasDe(Integer... idsPedido) {
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM detalle_pedido WHERE id_pedido IN (:ids)",
                new MapSqlParameterSource().addValue("ids", List.of(idsPedido)), Long.class);
        return total == null ? 0L : total;
    }

    private long cantidadSalidasDe(Integer... idsPedido) {
        Long total = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM movimientos_inventario_pt m
                JOIN detalle_pedido dp ON dp.id_detalle_pedido = m.id_origen
                WHERE m.tabla_origen = 'detalle_pedido'
                  AND m.tipo_movimiento = 'SALIDA_VENTA'
                  AND dp.id_pedido IN (:ids)
                """, new MapSqlParameterSource().addValue("ids", List.of(idsPedido)), Long.class);
        return total == null ? 0L : total;
    }

    private long saldosNegativosDe(Integer idProducto) {
        Long total = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM inventario_producto_terminado i
                JOIN lotes l ON l.id_lote = i.id_lote
                WHERE l.id_producto = :idProducto AND i.cantidad_disponible < 0
                """, new MapSqlParameterSource().addValue("idProducto", idProducto), Long.class);
        return total == null ? 0L : total;
    }

    private List<Movimiento> movimientosDeOrigen(Integer idDetalle) {
        return jdbc.query("""
                SELECT id_lote, tipo_movimiento, cantidad, tabla_origen
                FROM movimientos_inventario_pt
                WHERE tabla_origen = 'detalle_pedido' AND id_origen = :idOrigen
                ORDER BY id_movimiento
                """, new MapSqlParameterSource().addValue("idOrigen", idDetalle),
                (rs, fila) -> new Movimiento(
                        rs.getInt("id_lote"),
                        rs.getString("tipo_movimiento"),
                        rs.getBigDecimal("cantidad"),
                        rs.getString("tabla_origen")));
    }

    private List<Movimiento> movimientosDelLote(Integer idLote) {
        return jdbc.query("""
                SELECT id_lote, tipo_movimiento, cantidad, tabla_origen
                FROM movimientos_inventario_pt
                WHERE id_lote = :idLote
                ORDER BY id_movimiento
                """, new MapSqlParameterSource().addValue("idLote", idLote),
                (rs, fila) -> new Movimiento(
                        rs.getInt("id_lote"),
                        rs.getString("tipo_movimiento"),
                        rs.getBigDecimal("cantidad"),
                        rs.getString("tabla_origen")));
    }

    private Integer insertarConId(String sql, MapSqlParameterSource params) {
        var generadas = new GeneratedKeyHolder();
        jdbc.update(sql, params, generadas);
        Number clave = generadas.getKey();
        if (clave == null) {
            throw new IllegalStateException("La base no devolvió el id del fixture insertado.");
        }
        return clave.intValue();
    }

    private record LoteSembrado(Integer idLote, String codigo) {
    }

    private record Movimiento(Integer idLote, String tipo, BigDecimal cantidad,
                              String tablaOrigen) {
    }
}
