package co.edu.corposucre.productionfood.pedido;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import co.edu.corposucre.productionfood.common.error.ConflictoNegocioException;
import co.edu.corposucre.productionfood.common.error.ParametroInvalidoException;
import co.edu.corposucre.productionfood.inventario.InventarioPtRepository;
import co.edu.corposucre.productionfood.inventario.InventarioPtService;
import co.edu.corposucre.productionfood.pedido.dto.AgregarDetallePedidoRequest;
import co.edu.corposucre.productionfood.pedido.dto.DetallePedidoListaResponse;
import co.edu.corposucre.productionfood.pedido.dto.DetallePedidoResponse;

/**
 * Prueba de integración del alta de líneas de pedido contra MySQL (HU-17, PR-3).
 *
 * <p>Usa el contexto real de Spring —servicios, repositorios y transacciones reales— porque las
 * garantías que se prueban son de base de datos: FEFO sobre los lotes, el descuento de stock, el
 * kardex de trazabilidad y el rollback de una operación a medias. El proyecto no usa Testcontainers
 * ni H2: la única base soportada es la del {@code docker-compose}, y la prueba se omite con
 * {@code assumeTrue} si no responde.
 *
 * <p>Cada prueba siembra sus propios productos, lotes, inventario y pedidos con un sufijo UUID y
 * los elimina en orden inverso al de las llaves foráneas. Así dos corridas no comparten estado y
 * una ejecución fallida no ensucia las siguientes.
 */
@SpringBootTest
class DetallePedidoFefoIntegrationTest {

    private static final String URL = "jdbc:mysql://"
            + entorno("DB_HOST", "127.0.0.1") + ":" + entorno("DB_PORT", "3306")
            + "/" + entorno("DB_NAME", "productionfood")
            + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=America/Bogota";
    private static final String USUARIO = entorno("DB_USER", "root");
    private static final String CLAVE = entorno("DB_PASSWORD", "local");

    @Autowired
    private DetallePedidoService detallePedidoService;

    @Autowired
    private InventarioPtService inventarioPtService;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    /**
     * Espía sobre el repositorio de inventario, solo para CP-22: permite provocar un fallo en el
     * segundo movimiento de kardex de una línea que consume dos lotes. Mockito lo reinicia después
     * de cada prueba, así que el resto corre contra el comportamiento real.
     */
    @MockitoSpyBean
    private InventarioPtRepository inventarioPtRepository;

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
                        .addValue("nombre", "Unidad HU-17 FEFO" + sufijo)
                        .addValue("abreviatura", "u17f" + sufijo));

        idCliente = insertarConId("""
                INSERT INTO clientes (nombre, estado) VALUES (:nombre, 1)
                """, new MapSqlParameterSource()
                        .addValue("nombre", "Cliente HU-17 FEFO" + sufijo));
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
    @DisplayName("CP-01/CP-02/CP-03: FEFO consume los 12 del primer lote y deja 47 disponibles")
    void agregarConsumeLotesEnOrdenFefoYDescuentaElStock() {
        var idProducto = insertarProducto("1200.00", true);
        var loteA = insertarLote(idProducto, "12.00", 5);
        var loteB = insertarLote(idProducto, "40.00", 7);
        var loteC = insertarLote(idProducto, "25.00", 8);
        var idPedido = insertarPedido(EstadoPedido.PENDIENTE);

        var respuesta = detallePedidoService.agregar(idPedido,
                new AgregarDetallePedidoRequest(idProducto, new BigDecimal("30")));

        assertThat(respuesta.idDetallePedido()).isNotNull();
        assertThat(respuesta.producto().idProducto()).isEqualTo(idProducto);
        assertThat(respuesta.cantidad()).isEqualByComparingTo("30.00");
        assertThat(respuesta.cantidad().scale()).isEqualTo(2);
        assertThat(respuesta.precioUnitario()).isEqualByComparingTo("1200.00");
        assertThat(respuesta.subtotal()).isEqualByComparingTo("36000.00");
        assertThat(respuesta.subtotal().scale()).isEqualTo(2);
        assertThat(respuesta.totalPedido()).isEqualByComparingTo("36000.00");
        assertThat(respuesta.totalPedido().scale()).isEqualTo(2);

        // R-02: el lote que vence antes se agota primero, aunque la línea consuma dos.
        assertThat(respuesta.lotesConsumidos()).hasSize(2);
        assertThat(respuesta.lotesConsumidos().get(0).codigoLote()).isEqualTo(loteA.codigo());
        assertThat(respuesta.lotesConsumidos().get(0).cantidad()).isEqualByComparingTo("12.00");
        assertThat(respuesta.lotesConsumidos().get(1).codigoLote()).isEqualTo(loteB.codigo());
        assertThat(respuesta.lotesConsumidos().get(1).cantidad()).isEqualByComparingTo("18.00");

        assertThat(saldoDe(loteA.idLote())).isEqualByComparingTo("0.00");
        assertThat(saldoDe(loteB.idLote())).isEqualByComparingTo("22.00");
        assertThat(saldoDe(loteC.idLote())).isEqualByComparingTo("25.00");
        assertThat(inventarioPtService.stockDisponible(idProducto)).isEqualByComparingTo("47.00");
    }

    @Test
    @DisplayName("CP-04: FEFO manda el vencimiento, no el orden en que se dieron de alta los lotes")
    void fefoNoDependeDelOrdenDeAltaDeLosLotes() {
        var idProducto = insertarProducto("1200.00", true);
        var loteTardio = insertarLote(idProducto, "40.00", 7);
        var loteTemprano = insertarLote(idProducto, "12.00", 5);
        var idPedido = insertarPedido(EstadoPedido.PENDIENTE);

        var respuesta = detallePedidoService.agregar(idPedido,
                new AgregarDetallePedidoRequest(idProducto, new BigDecimal("30")));

        assertThat(respuesta.lotesConsumidos()).hasSize(2);
        assertThat(respuesta.lotesConsumidos().get(0).codigoLote())
                .isEqualTo(loteTemprano.codigo());
        assertThat(respuesta.lotesConsumidos().get(0).cantidad()).isEqualByComparingTo("12.00");
        assertThat(respuesta.lotesConsumidos().get(1).codigoLote()).isEqualTo(loteTardio.codigo());
        assertThat(respuesta.lotesConsumidos().get(1).cantidad()).isEqualByComparingTo("18.00");
    }

    @Test
    @DisplayName("CP-06/CP-07: el stock exacto deja cero y una unidad más responde STOCK_INSUFICIENTE")
    void agregarConStockExactoYUnaUnidadDeMas() {
        var idProducto = insertarProducto("1200.00", true);
        insertarLote(idProducto, "12.00", 5);
        insertarLote(idProducto, "40.00", 7);
        insertarLote(idProducto, "25.00", 8);
        var idPedido = insertarPedido(EstadoPedido.PENDIENTE);

        detallePedidoService.agregar(idPedido,
                new AgregarDetallePedidoRequest(idProducto, new BigDecimal("77")));

        assertThat(inventarioPtService.stockDisponible(idProducto)).isEqualByComparingTo("0.00");

        var otroProducto = insertarProducto("900.00", true);
        insertarLote(otroProducto, "12.00", 5);
        insertarLote(otroProducto, "40.00", 7);
        insertarLote(otroProducto, "25.00", 8);
        var otroPedido = insertarPedido(EstadoPedido.PENDIENTE);

        assertThatThrownBy(() -> detallePedidoService.agregar(otroPedido,
                new AgregarDetallePedidoRequest(otroProducto, new BigDecimal("78"))))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "STOCK_INSUFICIENTE")
                .hasMessageContaining("77")
                .hasMessageContaining("78");

        assertThat(cantidadLineas(otroPedido)).isZero();
    }

    @Test
    @DisplayName("CP-08/R-03: los 100 de un lote vencido no cuentan como stock vendible")
    void agregarConSoloLotesVencidosRespondeStockInsuficiente() {
        var idProducto = insertarProducto("1200.00", true);
        insertarLote(idProducto, "100.00", -1);
        var idPedido = insertarPedido(EstadoPedido.PENDIENTE);

        assertThatThrownBy(() -> detallePedidoService.agregar(idPedido,
                new AgregarDetallePedidoRequest(idProducto, new BigDecimal("1"))))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "STOCK_INSUFICIENTE")
                .hasMessageContaining("Solo hay 0 unidades disponibles");

        assertThat(cantidadLineas(idPedido)).isZero();
    }

    @Test
    @DisplayName("CP-14/R-04: el precio copiado no cambia aunque después suba el catálogo")
    void elPrecioDeLaLineaSeCongelaAlAgregar() {
        var idProducto = insertarProducto("1200.00", true);
        insertarLote(idProducto, "10.00", 5);
        var idPedido = insertarPedido(EstadoPedido.PENDIENTE);

        detallePedidoService.agregar(idPedido,
                new AgregarDetallePedidoRequest(idProducto, new BigDecimal("5")));

        jdbc.update("UPDATE productos SET precio = 1500.00 WHERE id_producto = :id",
                new MapSqlParameterSource().addValue("id", idProducto));

        DetallePedidoListaResponse listado = detallePedidoService.listar(idPedido);

        assertThat(listado.detalles()).hasSize(1);
        assertThat(listado.detalles().get(0).precioUnitario()).isEqualByComparingTo("1200.00");
        assertThat(listado.detalles().get(0).subtotal()).isEqualByComparingTo("6000.00");
        assertThat(listado.totalPedido()).isEqualByComparingTo("6000.00");

        var precioPersistido = jdbc.queryForObject("""
                SELECT precio_unitario FROM detalle_pedido WHERE id_pedido = :idPedido
                """, new MapSqlParameterSource().addValue("idPedido", idPedido),
                BigDecimal.class);
        assertThat(precioPersistido).isEqualByComparingTo("1200.00");
    }

    @Test
    @DisplayName("CP-23/R-10: el kardex deja una SALIDA_VENTA por lote con id_origen de la línea")
    void elKardexRegistraUnaSalidaPorLoteConOrigenDeLaLinea() {
        var idProducto = insertarProducto("1200.00", true);
        var loteA = insertarLote(idProducto, "12.00", 5);
        var loteB = insertarLote(idProducto, "40.00", 7);
        var idPedido = insertarPedido(EstadoPedido.PENDIENTE);

        var respuesta = detallePedidoService.agregar(idPedido,
                new AgregarDetallePedidoRequest(idProducto, new BigDecimal("30")));

        var movimientos = jdbc.query("""
                SELECT m.id_lote, m.tipo_movimiento, m.cantidad, m.saldo_anterior,
                       m.saldo_posterior, m.tabla_origen, m.id_origen
                FROM movimientos_inventario_pt m
                JOIN lotes l ON l.id_lote = m.id_lote
                WHERE m.tabla_origen = 'detalle_pedido' AND m.id_origen = :idOrigen
                ORDER BY l.fecha_vencimiento, m.id_lote
                """, new MapSqlParameterSource()
                        .addValue("idOrigen", respuesta.idDetallePedido()),
                (rs, fila) -> new Movimiento(
                        rs.getInt("id_lote"),
                        rs.getString("tipo_movimiento"),
                        rs.getBigDecimal("cantidad"),
                        rs.getBigDecimal("saldo_anterior"),
                        rs.getBigDecimal("saldo_posterior"),
                        rs.getString("tabla_origen"),
                        rs.getInt("id_origen")));

        assertThat(movimientos).hasSize(2);
        assertThat(movimientos).allSatisfy(movimiento -> {
            assertThat(movimiento.tipo()).isEqualTo("SALIDA_VENTA");
            assertThat(movimiento.tablaOrigen()).isEqualTo("detalle_pedido");
            assertThat(movimiento.idOrigen()).isEqualTo(respuesta.idDetallePedido());
        });
        assertThat(movimientos.get(0).idLote()).isEqualTo(loteA.idLote());
        assertThat(movimientos.get(0).cantidad()).isEqualByComparingTo("12.00");
        assertThat(movimientos.get(0).saldoAnterior()).isEqualByComparingTo("12.00");
        assertThat(movimientos.get(0).saldoPosterior()).isEqualByComparingTo("0.00");
        assertThat(movimientos.get(1).idLote()).isEqualTo(loteB.idLote());
        assertThat(movimientos.get(1).cantidad()).isEqualByComparingTo("18.00");
        assertThat(movimientos.get(1).saldoAnterior()).isEqualByComparingTo("40.00");
        assertThat(movimientos.get(1).saldoPosterior()).isEqualByComparingTo("22.00");
    }

    @Test
    @DisplayName("CP-20/CP-21/R-09: un pedido ENTREGADO o CANCELADO no admite líneas")
    void agregarAPedidoEntregadoOCanceladoResponde409() {
        var idProducto = insertarProducto("1200.00", true);
        var entregado = insertarPedido(EstadoPedido.ENTREGADO);
        var cancelado = insertarPedido(EstadoPedido.CANCELADO);

        for (var idPedido : List.of(entregado, cancelado)) {
            assertThatThrownBy(() -> detallePedidoService.agregar(idPedido,
                    new AgregarDetallePedidoRequest(idProducto, new BigDecimal("1"))))
                    .isInstanceOf(ConflictoNegocioException.class)
                    .hasFieldOrPropertyWithValue("codigo", "PEDIDO_NO_EDITABLE")
                    .hasMessageContaining("PENDIENTE")
                    .hasMessageContaining("EN_PREPARACION");

            assertThat(cantidadLineas(idPedido)).isZero();
        }
    }

    @Test
    @DisplayName("CP-10/CP-11: producto inexistente e inactivo responden 409 sin crear la línea")
    void agregarProductoInexistenteOInactivoResponde409() {
        var inactivo = insertarProducto("1200.00", false);
        var idPedido = insertarPedido(EstadoPedido.PENDIENTE);

        assertThatThrownBy(() -> detallePedidoService.agregar(idPedido,
                new AgregarDetallePedidoRequest(999_999_999, new BigDecimal("1"))))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "PRODUCTO_INEXISTENTE");

        assertThatThrownBy(() -> detallePedidoService.agregar(idPedido,
                new AgregarDetallePedidoRequest(inactivo, new BigDecimal("1"))))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "PRODUCTO_INACTIVO");

        assertThat(cantidadLineas(idPedido)).isZero();
    }

    @Test
    @DisplayName("CP-15/R-06: un producto ya presente en el pedido responde PRODUCTO_DUPLICADO")
    void agregarProductoDuplicadoResponde409() {
        var idProducto = insertarProducto("1200.00", true);
        var idPedido = insertarPedido(EstadoPedido.PENDIENTE);
        jdbc.update("""
                INSERT INTO detalle_pedido (id_pedido, id_producto, cantidad, precio_unitario)
                VALUES (:idPedido, :idProducto, 2.00, 1200.00)
                """, new MapSqlParameterSource()
                .addValue("idPedido", idPedido)
                .addValue("idProducto", idProducto));

        assertThatThrownBy(() -> detallePedidoService.agregar(idPedido,
                new AgregarDetallePedidoRequest(idProducto, new BigDecimal("1"))))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "PRODUCTO_DUPLICADO");

        assertThat(cantidadLineas(idPedido)).isEqualTo(1);
    }

    @Test
    @DisplayName("CP-12/CP-13: cantidad cero o negativa responde 400 CANTIDAD_INVALIDA")
    void agregarConCantidadCeroONegativaResponde400() {
        var idProducto = insertarProducto("1200.00", true);
        var idPedido = insertarPedido(EstadoPedido.PENDIENTE);

        for (var cantidad : List.of(BigDecimal.ZERO, new BigDecimal("-5"))) {
            assertThatThrownBy(() -> detallePedidoService.agregar(idPedido,
                    new AgregarDetallePedidoRequest(idProducto, cantidad)))
                    .isInstanceOf(ParametroInvalidoException.class)
                    .hasFieldOrPropertyWithValue("codigo", "CANTIDAD_INVALIDA");
        }

        assertThat(cantidadLineas(idPedido)).isZero();
    }

    @Test
    @DisplayName("CP-22: un fallo en el segundo lote revierte el INSERT, el saldo y el kardex")
    void unFalloEnElSegundoMovimientoRevierteTodaLaOperacion() {
        var idProducto = insertarProducto("1200.00", true);
        var loteA = insertarLote(idProducto, "10.00", 5);
        var loteB = insertarLote(idProducto, "20.00", 7);
        var idPedido = insertarPedido(EstadoPedido.PENDIENTE);

        // La línea pide 15: FEFO toca el lote A (10) y el lote B (5), dos movimientos de kardex.
        // El segundo se hace fallar para comprobar que la transacción revierte el primero.
        var llamadas = new AtomicInteger();
        doAnswer(invocacion -> {
            if (llamadas.incrementAndGet() == 2) {
                throw new IllegalStateException(
                        "Fallo simulado al registrar el segundo movimiento de kardex");
            }
            return invocacion.callRealMethod();
        }).when(inventarioPtRepository).registrarMovimiento(any(), any(), any(), any(), any(),
                any(), any(), any());

        // El repositorio de Spring traduce la excepción al salir del proxy @Repository; lo que
        // importa es que la operación falle y que la transacción no deje rastro.
        assertThatThrownBy(() -> detallePedidoService.agregar(idPedido,
                new AgregarDetallePedidoRequest(idProducto, new BigDecimal("15"))))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Fallo simulado");

        // Ningún rastro de la operación a medias: ni línea, ni saldo tocado, ni kardex.
        assertThat(cantidadLineas(idPedido)).isZero();
        assertThat(saldoDe(loteA.idLote())).isEqualByComparingTo("10.00");
        assertThat(saldoDe(loteB.idLote())).isEqualByComparingTo("20.00");
        assertThat(movimientosDeLosLotes(loteA.idLote(), loteB.idLote())).isZero();
    }

    /**
     * Sembrado de producto con precio y estado dados; el nombre lleva el sufijo de la corrida para
     * que la búsqueda por nombre no dependa de datos previos.
     */
    private Integer insertarProducto(String precio, boolean activo) {
        var id = insertarConId("""
                INSERT INTO productos (nombre, precio, id_unidad, estado)
                VALUES (:nombre, :precio, :idUnidad, :estado)
                """, new MapSqlParameterSource()
                        .addValue("nombre", "Producto HU-17 FEFO" + sufijo)
                        .addValue("precio", new BigDecimal(precio))
                        .addValue("idUnidad", idUnidad)
                        .addValue("estado", activo ? 1 : 0));
        idsProducto.add(id);
        return id;
    }

    /**
     * Sembrado de lote e inventario en una sola llamada. {@code diasVencimiento} se suma a hoy:
     * negativo siembra un lote vencido, que la vista de stock excluye (R-03).
     */
    private LoteSembrado insertarLote(Integer idProducto, String cantidad, int diasVencimiento) {
        var codigo = "L17F" + sufijo + "-" + UUID.randomUUID().toString().substring(0, 6);
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

    private long cantidadLineas(Integer idPedido) {
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM detalle_pedido WHERE id_pedido = :idPedido",
                new MapSqlParameterSource().addValue("idPedido", idPedido), Long.class);
        return total == null ? 0L : total;
    }

    private long movimientosDeLosLotes(Integer idLoteA, Integer idLoteB) {
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM movimientos_inventario_pt WHERE id_lote IN (:ids)",
                new MapSqlParameterSource().addValue("ids", List.of(idLoteA, idLoteB)), Long.class);
        return total == null ? 0L : total;
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
                              BigDecimal saldoAnterior, BigDecimal saldoPosterior,
                              String tablaOrigen, Integer idOrigen) {
    }
}
