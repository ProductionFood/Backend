package co.edu.corposucre.productionfood.pedido;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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

import co.edu.corposucre.productionfood.pedido.dto.CambiarEstadoPedidoRequest;

/**
 * Prueba de integración de la cancelación de un pedido contra MySQL (HU-16, PR-3).
 *
 * <p>Usa el contexto real de Spring —repositorios, servicios y transacciones reales— porque la
 * garantía que se prueba es transaccional: la devolución de cada línea al lote y el cambio de
 * estado deben ocurrir juntos. El proyecto no usa Testcontainers ni H2: la única base soportada
 * es la del {@code docker-compose}, y la prueba se omite con {@code assumeTrue} si no responde.
 *
 * <p>La siembra parte de un pedido con dos líneas y dos {@code SALIDA_VENTA} en el kardex que
 * dejaron cada lote en su saldo actual. Al cancelar, el servicio debe reponer esas cantidades a
 * cada lote y dejar dos {@code ENTRADA_DEVOLUCION} con origen {@code pedidos}/{@code idPedido}.
 */
@SpringBootTest
class PedidoCancelacionIntegrationTest {

    private static final String URL = "jdbc:mysql://"
            + entorno("DB_HOST", "127.0.0.1") + ":" + entorno("DB_PORT", "3306")
            + "/" + entorno("DB_NAME", "productionfood")
            + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=America/Bogota";
    private static final String USUARIO = entorno("DB_USER", "root");
    private static final String CLAVE = entorno("DB_PASSWORD", "local");

    @Autowired
    private PedidoService pedidoService;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    private final List<Integer> idsLote = new ArrayList<>();
    private Integer idUnidad;
    private final List<Integer> idsProducto = new ArrayList<>();
    private Integer idCliente;
    private Integer idPedido;
    private Integer idLoteA;
    private Integer idLoteB;

    private static String entorno(String nombre, String defecto) {
        String valor = System.getenv(nombre);
        return valor == null || valor.isBlank() ? defecto : valor;
    }

    @BeforeAll
    static void laBaseDelProyectoEstaDisponible() {
        boolean disponible;
        try (var conexion = java.sql.DriverManager.getConnection(URL, USUARIO, CLAVE);
             var sentencia = conexion.createStatement()) {
            sentencia.executeQuery("SELECT 1 FROM lotes LIMIT 1").close();
            disponible = true;
        } catch (SQLException e) {
            disponible = false;
        }
        assumeTrue(disponible,
                "MySQL no responde en " + URL + " con la tabla lotes; se omite.");
    }

    @BeforeEach
    void setUp() {
        var sufijo = "-" + UUID.randomUUID().toString().substring(0, 8);

        idUnidad = insertarConId("""
                INSERT INTO unidades_medida (nombre, abreviatura)
                VALUES (:nombre, :abreviatura)
                """, new MapSqlParameterSource()
                        .addValue("nombre", "Unidad HU-16 cancelación" + sufijo)
                        .addValue("abreviatura", "u16c" + sufijo));

        idsProducto.add(insertarProducto("Producto A HU-16 cancelación" + sufijo));
        idsProducto.add(insertarProducto("Producto B HU-16 cancelación" + sufijo));

        idCliente = insertarConId("""
                INSERT INTO clientes (nombre, estado) VALUES (:nombre, 1)
                """, new MapSqlParameterSource()
                        .addValue("nombre", "Cliente HU-16 cancelación" + sufijo));

        // Dos lotes, cada uno con su propia fila de inventario. Las salidas de venta dejaron a
        // A en 7,00 y a B en 3,00; al cancelar deben volver a 10,00 y 5,00.
        idLoteA = insertarLote(idsProducto.get(0), "10.00", sufijo);
        idLoteB = insertarLote(idsProducto.get(1), "5.00", sufijo);
        insertarInventario(idLoteA, "7.00");
        insertarInventario(idLoteB, "3.00");

        idPedido = insertarConId("""
                INSERT INTO pedidos (id_cliente, fecha_pedido, estado)
                VALUES (:idCliente, :fecha, :estado)
                """, new MapSqlParameterSource()
                        .addValue("idCliente", idCliente)
                        .addValue("fecha", LocalDate.now())
                        .addValue("estado", EstadoPedido.PENDIENTE.name()));

        var idDetalleA = insertarLinea(idsProducto.get(0), "3.00", "1250.50");
        var idDetalleB = insertarLinea(idsProducto.get(1), "2.00", "800.00");

        insertarSalida(idLoteA, idDetalleA, "3.00", "10.00", "7.00");
        insertarSalida(idLoteB, idDetalleB, "2.00", "5.00", "3.00");
    }

    @AfterEach
    void tearDown() {
        // La bitácora no tiene FK con pedidos; se limpia por el detalle exacto que escribió el
        // aspecto de auditoría para no dejar filas de prueba.
        if (idPedido != null) {
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
        if (idPedido != null) {
            jdbc.update("DELETE FROM detalle_pedido WHERE id_pedido = :id",
                    new MapSqlParameterSource().addValue("id", idPedido));
            jdbc.update("DELETE FROM pedidos WHERE id_pedido = :id",
                    new MapSqlParameterSource().addValue("id", idPedido));
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
    @DisplayName("R-04/R-10: cancelar repone cada lote y deja dos ENTRADA_DEVOLUCION del pedido")
    void cancelarDevuelveElStockDeCadaLote() {
        var respuesta = pedidoService.cambiarEstado(idPedido,
                new CambiarEstadoPedidoRequest(EstadoPedido.CANCELADO));

        assertThat(respuesta.estado()).isEqualTo("CANCELADO");
        assertThat(saldoDe(idLoteA)).isEqualByComparingTo("10.00");
        assertThat(saldoDe(idLoteB)).isEqualByComparingTo("5.00");

        var devoluciones = jdbc.query("""
                SELECT id_lote, cantidad, saldo_anterior, saldo_posterior
                FROM movimientos_inventario_pt
                WHERE tipo_movimiento = 'ENTRADA_DEVOLUCION'
                  AND tabla_origen = 'pedidos'
                  AND id_origen = :idPedido
                ORDER BY id_lote
                """, new MapSqlParameterSource().addValue("idPedido", idPedido),
                (rs, fila) -> new Devolucion(
                        rs.getInt("id_lote"),
                        rs.getBigDecimal("cantidad"),
                        rs.getBigDecimal("saldo_anterior"),
                        rs.getBigDecimal("saldo_posterior")));

        assertThat(devoluciones).hasSize(2);
        assertThat(devoluciones.get(0).idLote()).isEqualTo(idLoteA);
        assertThat(devoluciones.get(0).cantidad()).isEqualByComparingTo("3.00");
        assertThat(devoluciones.get(0).saldoAnterior()).isEqualByComparingTo("7.00");
        assertThat(devoluciones.get(0).saldoPosterior()).isEqualByComparingTo("10.00");
        assertThat(devoluciones.get(1).idLote()).isEqualTo(idLoteB);
        assertThat(devoluciones.get(1).cantidad()).isEqualByComparingTo("2.00");
        assertThat(devoluciones.get(1).saldoAnterior()).isEqualByComparingTo("3.00");
        assertThat(devoluciones.get(1).saldoPosterior()).isEqualByComparingTo("5.00");
    }

    private BigDecimal saldoDe(Integer idLote) {
        return jdbc.queryForObject("""
                SELECT cantidad_disponible
                FROM inventario_producto_terminado
                WHERE id_lote = :idLote
                """, new MapSqlParameterSource().addValue("idLote", idLote), BigDecimal.class);
    }

    private Integer insertarProducto(String nombre) {
        return insertarConId("""
                INSERT INTO productos (nombre, precio, id_unidad, estado)
                VALUES (:nombre, 100.00, :idUnidad, 1)
                """, new MapSqlParameterSource()
                        .addValue("nombre", nombre)
                        .addValue("idUnidad", idUnidad));
    }

    private Integer insertarLote(Integer idProducto, String cantidad, String sufijo) {
        var id = insertarConId("""
                INSERT INTO lotes (codigo_lote, id_producto, fecha_produccion, fecha_vencimiento,
                                   cantidad)
                VALUES (:codigo, :idProducto, :produccion, :vencimiento, :cantidad)
                """, new MapSqlParameterSource()
                        .addValue("codigo", "LH16C" + sufijo + "-"
                                + UUID.randomUUID().toString().substring(0, 8))
                        .addValue("idProducto", idProducto)
                        .addValue("produccion", LocalDate.now().minusDays(1))
                        .addValue("vencimiento", LocalDate.now().plusDays(30))
                        .addValue("cantidad", new BigDecimal(cantidad)));
        idsLote.add(id);
        return id;
    }

    private void insertarInventario(Integer idLote, String cantidad) {
        jdbc.update("""
                INSERT INTO inventario_producto_terminado (id_lote, cantidad_disponible)
                VALUES (:idLote, :cantidad)
                """, new MapSqlParameterSource()
                        .addValue("idLote", idLote)
                        .addValue("cantidad", new BigDecimal(cantidad)));
    }

    private Integer insertarLinea(Integer idProducto, String cantidad, String precio) {
        return insertarConId("""
                INSERT INTO detalle_pedido (id_pedido, id_producto, cantidad, precio_unitario)
                VALUES (:idPedido, :idProducto, :cantidad, :precio)
                """, new MapSqlParameterSource()
                        .addValue("idPedido", idPedido)
                        .addValue("idProducto", idProducto)
                        .addValue("cantidad", new BigDecimal(cantidad))
                        .addValue("precio", new BigDecimal(precio)));
    }

    private void insertarSalida(Integer idLote, Integer idDetalle, String cantidad,
                                String anterior, String posterior) {
        jdbc.update("""
                INSERT INTO movimientos_inventario_pt
                    (id_lote, tipo_movimiento, cantidad, saldo_anterior, saldo_posterior,
                     tabla_origen, id_origen)
                VALUES (:idLote, 'SALIDA_VENTA', :cantidad, :anterior, :posterior,
                        'detalle_pedido', :idOrigen)
                """, new MapSqlParameterSource()
                        .addValue("idLote", idLote)
                        .addValue("cantidad", new BigDecimal(cantidad))
                        .addValue("anterior", new BigDecimal(anterior))
                        .addValue("posterior", new BigDecimal(posterior))
                        .addValue("idOrigen", idDetalle));
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

    private record Devolucion(Integer idLote, BigDecimal cantidad, BigDecimal saldoAnterior,
                              BigDecimal saldoPosterior) {
    }
}
