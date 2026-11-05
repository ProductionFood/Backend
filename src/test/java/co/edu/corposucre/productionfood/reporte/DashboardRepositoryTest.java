package co.edu.corposucre.productionfood.reporte;

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

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.GeneratedKeyHolder;

import co.edu.corposucre.productionfood.reporte.dto.PedidoHoyResponse;
import co.edu.corposucre.productionfood.reporte.dto.PorVencerResponse;
import co.edu.corposucre.productionfood.reporte.dto.VencimientoResponse;

/**
 * Pruebas de integración de {@link DashboardRepository} contra MySQL (HU-24).
 *
 * <p>Como las de alertas, usan la base del {@code docker-compose} y se omiten con
 * {@code assumeTrue} si no responde. La base es compartida y puede tener datos de hoy, así que
 * cada prueba mide la tarjeta antes y después de sembrar sus filas (sufijo único) y afirma sobre
 * la diferencia; al terminar elimina únicamente lo que sembró.
 */
class DashboardRepositoryTest {

    private static final String URL = "jdbc:mysql://"
            + entorno("DB_HOST", "127.0.0.1") + ":" + entorno("DB_PORT", "3306")
            + "/" + entorno("DB_NAME", "productionfood")
            + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=America/Bogota";
    private static final String USUARIO = entorno("DB_USER", "root");
    private static final String CLAVE = entorno("DB_PASSWORD", "local");

    private NamedParameterJdbcTemplate jdbc;
    private DashboardRepository repository;

    private String sufijo;
    private Integer idUnidad;
    private Integer idUsuario;
    private Integer idCliente;
    private Integer idProducto;
    private Integer idMateriaPrima;
    private Integer idProveedor;
    private final List<Integer> idsPedido = new ArrayList<>();
    private final List<Integer> idsPlan = new ArrayList<>();
    private final List<Integer> idsCompra = new ArrayList<>();
    private final List<Integer> idsLote = new ArrayList<>();

    private static String entorno(String nombre, String defecto) {
        String valor = System.getenv(nombre);
        return valor == null || valor.isBlank() ? defecto : valor;
    }

    @BeforeAll
    static void laBaseDelProyectoEstaDisponible() {
        boolean disponible;
        try (var conexion = java.sql.DriverManager.getConnection(URL, USUARIO, CLAVE);
             var sentencia = conexion.createStatement()) {
            sentencia.executeQuery("SELECT precio_unitario FROM detalle_pedido LIMIT 1").close();
            sentencia.executeQuery("SELECT 1 FROM inventario_producto_terminado LIMIT 1").close();
            disponible = true;
        } catch (SQLException e) {
            disponible = false;
        }
        assumeTrue(disponible, "MySQL no responde en " + URL + " con el esquema; se omite.");
    }

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource(URL, USUARIO, CLAVE);
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        jdbc = new NamedParameterJdbcTemplate(dataSource);
        repository = new DashboardRepository(jdbc);

        sufijo = "HU24-" + UUID.randomUUID().toString().substring(0, 8);
        var usuario = jdbc.getJdbcTemplate()
                .queryForList("SELECT id_usuario FROM usuarios LIMIT 1", Integer.class);
        assumeTrue(!usuario.isEmpty(), "No hay usuarios para sembrar planes; se omite.");
        idUsuario = usuario.get(0);
        idUnidad = insertar("INSERT INTO unidades_medida (nombre, abreviatura) "
                + "VALUES (:n, :a)", "n", "Unidad " + sufijo, "a", sufijo.substring(5));
        idCliente = insertar("INSERT INTO clientes (nombre) VALUES (:n)", "n", "Cliente " + sufijo);
        idProveedor = insertar("INSERT INTO proveedores (nombre) VALUES (:n)",
                "n", "Proveedor " + sufijo);
        idProducto = insertar("INSERT INTO productos (nombre, precio, id_unidad, estado) "
                + "VALUES (:n, 10, :u, 1)", "n", "Producto " + sufijo, "u", idUnidad);
        idMateriaPrima = insertar("INSERT INTO materias_primas (nombre, id_unidad, stock_minimo, "
                + "costo_unitario, estado) VALUES (:n, :u, 0, 1, 1)",
                "n", "MP " + sufijo, "u", idUnidad);
    }

    @AfterEach
    void tearDown() {
        borrarIn("detalle_pedido", "id_pedido", idsPedido);
        borrarIn("pedidos", "id_pedido", idsPedido);
        borrarIn("detalle_plan_produccion", "id_plan", idsPlan);
        borrarIn("planes_produccion", "id_plan", idsPlan);
        borrarIn("detalle_compra", "id_compra", idsCompra);
        borrarIn("compras", "id_compra", idsCompra);
        borrarIn("inventario_producto_terminado", "id_lote", idsLote);
        borrarIn("lotes", "id_lote", idsLote);
        borrar("productos", "id_producto", idProducto);
        borrar("clientes", "id_cliente", idCliente);
        borrar("proveedores", "id_proveedor", idProveedor);
        borrar("materias_primas", "id_materia_prima", idMateriaPrima);
        borrar("unidades_medida", "id_unidad", idUnidad);
    }

    @Test
    @DisplayName("R-01: el instante sale de la base de datos y coincide con curdate()")
    void elInstanteSaleDeLaBase() {
        var hoyBd = jdbc.getJdbcTemplate().queryForObject("SELECT curdate()", LocalDate.class);

        assertThat(repository.ahora().toLocalDate()).isIn(hoyBd, hoyBd.plusDays(1));
    }

    @Test
    @DisplayName("CA-01: cuenta los pedidos de hoy, el total excluye cancelados y porEstado trae 4 claves")
    void pedidosDelDia() {
        var antes = repository.pedidosDelDia();
        assertThat(antes.porEstado().keySet())
                .containsExactly("PENDIENTE", "EN_PREPARACION", "ENTREGADO", "CANCELADO");

        sembrarPedido("PENDIENTE", 0, "2.00", "100.00");
        sembrarPedido("ENTREGADO", 0, "1.00", "50.50");
        sembrarPedido("CANCELADO", 0, "9.00", "1000.00");
        sembrarPedido("PENDIENTE", -1, "9.00", "1000.00");
        var despues = repository.pedidosDelDia();

        assertThat(despues.cantidad() - antes.cantidad()).isEqualTo(3);
        assertThat(despues.porEstado().get("PENDIENTE") - antes.porEstado().get("PENDIENTE"))
                .isEqualTo(1);
        assertThat(despues.porEstado().get("ENTREGADO") - antes.porEstado().get("ENTREGADO"))
                .isEqualTo(1);
        assertThat(despues.porEstado().get("CANCELADO") - antes.porEstado().get("CANCELADO"))
                .isEqualTo(1);
        assertThat(despues.total().subtract(antes.total())).isEqualByComparingTo("250.50");
        assertThat(despues.total().scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("Día sin pedidos: cantidad 0, total 0.00 y las cuatro claves en cero, nunca null")
    void pedidosDeUnDiaVacioNoSonNulos() {
        var vacio = repository.pedidosDelDia();
        if (vacio.cantidad() == 0) {
            assertThat(vacio.total()).isEqualByComparingTo("0.00");
            assertThat(vacio.total().scale()).isEqualTo(2);
            assertThat(vacio.porEstado().values()).containsOnly(0L);
        }
        assertThat(vacio.total()).isNotNull();
        assertThat(vacio.porEstado()).hasSize(4);
    }

    @Test
    @DisplayName("CA-02: las compras PENDIENTE cuentan sin filtro de fecha; RECIBIDA y CANCELADA no")
    void comprasPendientesSinFiltroDeFecha() {
        var antes = repository.comprasPendientes();

        var antigua = sembrarCompra("PENDIENTE", "2000-01-01");
        sembrarCompra("PENDIENTE", "2000-02-01");
        sembrarCompra("CANCELADA", "2000-01-01");
        sembrarCompra("RECIBIDA", "2000-01-01");
        var despues = repository.comprasPendientes();

        assertThat(despues.cantidad() - antes.cantidad()).isEqualTo(2);
        assertThat(despues.total().subtract(antes.total())).isEqualByComparingTo("2000.00");
        assertThat(despues.masAntigua().idCompra()).isEqualTo(antigua);
        assertThat(despues.masAntigua().fecha()).isEqualTo(LocalDate.of(2000, 1, 1));
        assertThat(despues.masAntigua().diasPendiente()).isEqualTo(java.time.temporal.ChronoUnit
                .DAYS.between(LocalDate.of(2000, 1, 1),
                        jdbc.getJdbcTemplate().queryForObject("SELECT curdate()", LocalDate.class)));
    }

    @Test
    @DisplayName("CA-04: cuenta los planes de hoy, los cancelados aparte y promedia el avance")
    void produccionDelDia() {
        var antes = repository.produccionDelDia();
        assertThat(antes.porEstado().keySet())
                .containsExactly("PLANIFICADO", "EN_PROCESO", "COMPLETADO", "CANCELADO");

        sembrarPlan("EN_PROCESO", 0, "10.00", "5.00");   // 50 %
        sembrarPlan("COMPLETADO", 0, "4.00", "4.00");    // 100 %
        sembrarPlan("CANCELADO", 0, "10.00", "0.00");    // no entra al promedio
        sembrarPlan("PLANIFICADO", 1, "10.00", "10.00"); // mañana: no cuenta
        var despues = repository.produccionDelDia();

        assertThat(despues.cantidad() - antes.cantidad()).isEqualTo(3);
        assertThat(despues.porEstado().get("CANCELADO") - antes.porEstado().get("CANCELADO"))
                .isEqualTo(1);
        assertThat(despues.avancePromedio().scale()).isEqualTo(2);
        if (antes.cantidad() == 0) {
            assertThat(despues.avancePromedio()).isEqualByComparingTo("75.00");
        }
    }

    @Test
    @DisplayName("Un plan sin líneas cuenta como avance 0 y no rompe el promedio")
    void planSinLineasTieneAvanceCero() {
        var antes = repository.produccionDelDia();
        idsPlan.add(insertar("INSERT INTO planes_produccion (fecha_planificacion, "
                + "fecha_produccion, estado, id_usuario) VALUES (curdate(), curdate(), "
                + "'PLANIFICADO', :u)", "u", idUsuario));
        var despues = repository.produccionDelDia();

        assertThat(despues.cantidad() - antes.cantidad()).isEqualTo(1);
        assertThat(despues.avancePromedio()).isNotNull();
    }

    @Test
    @DisplayName("CA-05/R-03: hoy+7 entra, hoy+8 no, sin stock no, vencido con stock sí (días negativos)")
    void proximosAVencer() {
        var mas7 = sembrarLote("a", 7, "5.00");
        var mas8 = sembrarLote("b", 8, "5.00");
        var sinStock = sembrarLote("c", 3, "0.00");
        var vencido = sembrarLote("d", -2, "8.00");

        var resultado = repository.proximosAVencer(7, 100_000);
        var codigos = resultado.items().stream().map(PorVencerResponse.Item::codigoLote).toList();

        assertThat(codigos).contains(mas7, vencido).doesNotContain(mas8, sinStock);
        var itemVencido = item(resultado, vencido);
        assertThat(itemVencido.diasParaVencer()).isEqualTo(-2);
        assertThat(itemVencido.disponible()).isEqualByComparingTo("8.00");
        assertThat(itemVencido.producto()).isEqualTo("Producto " + sufijo);
        assertThat(item(resultado, mas7).diasParaVencer()).isEqualTo(7);
        assertThat(resultado.vencidos()).isGreaterThanOrEqualTo(1);
        assertThat(codigos.indexOf(vencido)).isLessThan(codigos.indexOf(mas7));
    }

    @Test
    @DisplayName("El parámetro de días amplía o reduce el horizonte")
    void parametroDias() {
        var mas8 = sembrarLote("a", 8, "5.00");

        assertThat(repository.proximosAVencer(7, 100_000).items())
                .extracting(PorVencerResponse.Item::codigoLote).doesNotContain(mas8);
        assertThat(repository.proximosAVencer(8, 100_000).items())
                .extracting(PorVencerResponse.Item::codigoLote).contains(mas8);
    }

    @Test
    @DisplayName("Los items se recortan al límite pero cantidad y vencidos cuentan todos los lotes")
    void limiteDeItemsNoRecortaLosConteos() {
        var antes = repository.proximosAVencer(7, 10);
        for (int i = 0; i < 12; i++) {
            sembrarLote("l" + i, -1, "1.00");
        }
        var despues = repository.proximosAVencer(7, 10);

        assertThat(despues.items()).hasSize(10);
        assertThat(despues.cantidad() - antes.cantidad()).isEqualTo(12);
        assertThat(despues.vencidos() - antes.vencidos()).isEqualTo(12);
    }


    // ---- detalle paginado (PR2) -----------------------------------------------------------

    private List<PedidoHoyResponse> mios(List<PedidoHoyResponse> filas) {
        return filas.stream().filter(f -> f.cliente().equals("Cliente " + sufijo)).toList();
    }

    @Test
    @DisplayName("Pedidos de hoy: solo los de hoy, con total propio (cancelado incluido) y 0.00 sin líneas")
    void pedidosDeHoyDetalle() {
        var antes = repository.contarPedidosDeHoy(null);
        sembrarPedido("PENDIENTE", 0, "2.000", "10.50");
        sembrarPedido("CANCELADO", 0, "1.000", "5.00");
        sembrarPedido("ENTREGADO", -1, "1.000", "99.00");
        var sinLineas = insertar("INSERT INTO pedidos (id_cliente, fecha_pedido, estado) VALUES "
                + "(:c, curdate(), 'PENDIENTE')", "c", idCliente);
        idsPedido.add(sinLineas);

        assertThat(repository.contarPedidosDeHoy(null) - antes).isEqualTo(3);
        var filas = mios(repository.pedidosDeHoy(null, "p.id_pedido", true, 100_000, 0));

        assertThat(filas).hasSize(3);
        assertThat(filas).extracting(PedidoHoyResponse::total)
                .usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .containsExactly(new BigDecimal("21.00"), new BigDecimal("5.00"),
                        new BigDecimal("0.00"));
        assertThat(filas).extracting(PedidoHoyResponse::estado)
                .containsExactly("PENDIENTE", "CANCELADO", "PENDIENTE");
        assertThat(filas.get(0).fechaEntrega()).isNull();
        assertThat(filas.get(0).total().scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("Pedidos de hoy: el filtro de estado, el orden y la paginación cuadran con el conteo")
    void pedidosDeHoyFiltroOrdenYPagina() {
        var antesPend = repository.contarPedidosDeHoy("PENDIENTE");
        sembrarPedido("PENDIENTE", 0, "1.000", "30.00");
        sembrarPedido("PENDIENTE", 0, "1.000", "10.00");
        sembrarPedido("PENDIENTE", 0, "1.000", "20.00");
        sembrarPedido("EN_PREPARACION", 0, "1.000", "1.00");

        assertThat(repository.contarPedidosDeHoy("PENDIENTE") - antesPend).isEqualTo(3);
        var pend = mios(repository.pedidosDeHoy("PENDIENTE", "total", true, 100_000, 0));
        assertThat(pend).extracting(PedidoHoyResponse::total)
                .usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .containsExactly(new BigDecimal("10.00"), new BigDecimal("20.00"),
                        new BigDecimal("30.00"));
        assertThat(pend).extracting(PedidoHoyResponse::estado).containsOnly("PENDIENTE");

        var todos = repository.contarPedidosDeHoy(null);
        var primera = repository.pedidosDeHoy(null, "p.id_pedido", false, 2, 0);
        var ultima = repository.pedidosDeHoy(null, "p.id_pedido", false, 2, todos - 1);
        assertThat(primera).hasSize(2);
        assertThat(primera.get(0).idPedido()).isGreaterThan(primera.get(1).idPedido());
        assertThat(ultima).hasSize(1);
    }

    private List<String> codigos(List<VencimientoResponse> filas) {
        return filas.stream().map(VencimientoResponse::codigoLote).toList();
    }

    @Test
    @DisplayName("Vencimientos: hoy+7 sí, hoy+8 no, sin stock no, vencido sí con días negativos")
    void vencimientosDetalle() {
        var antes = repository.contarVencimientos(7);
        var mas7 = sembrarLote("a", 7, "5.00");
        var mas8 = sembrarLote("b", 8, "5.00");
        var sinStock = sembrarLote("c", 3, "0.00");
        var vencido = sembrarLote("d", -2, "8.00");

        assertThat(repository.contarVencimientos(7) - antes).isEqualTo(2);
        var filas = repository.vencimientos(7, "l.fecha_vencimiento", true, 100_000, 0);

        assertThat(codigos(filas)).contains(mas7, vencido).doesNotContain(mas8, sinStock);
        var v = filas.stream().filter(f -> f.codigoLote().equals(vencido)).findFirst()
                .orElseThrow();
        assertThat(v.diasParaVencer()).isEqualTo(-2);
        assertThat(v.disponible()).isEqualByComparingTo("8.00");
        assertThat(v.producto()).isEqualTo("Producto " + sufijo);
        assertThat(codigos(filas).indexOf(vencido)).isLessThan(codigos(filas).indexOf(mas7));
        assertThat(repository.contarVencimientos(8) - antes).isEqualTo(3);
    }

    @Test
    @DisplayName("Vencimientos: la página recorta, totalElements cuenta todo y el orden por disponible respeta la dirección")
    void vencimientosPaginaYOrden() {
        var antes = repository.contarVencimientos(7);
        sembrarLote("a", 1, "3.00");
        sembrarLote("b", 1, "1.00");
        sembrarLote("c", 1, "2.00");

        assertThat(repository.contarVencimientos(7) - antes).isEqualTo(3);
        assertThat(repository.vencimientos(7, "l.fecha_vencimiento", true, 2, 0)).hasSize(2);
        var porDisponible = repository.vencimientos(7, "disponible", false, 100_000, 0).stream()
                .filter(f -> f.producto().equals("Producto " + sufijo)).toList();
        assertThat(porDisponible).extracting(VencimientoResponse::disponible)
                .usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .containsExactly(new BigDecimal("3.00"), new BigDecimal("2.00"),
                        new BigDecimal("1.00"));
    }

    // ---- fixtures -------------------------------------------------------------------------

    private static PorVencerResponse.Item item(PorVencerResponse r, String codigo) {
        return r.items().stream().filter(i -> i.codigoLote().equals(codigo)).findFirst()
                .orElseThrow();
    }

    private void sembrarPedido(String estado, int diasDesdeHoy, String cantidad, String precio) {
        var id = insertar("INSERT INTO pedidos (id_cliente, fecha_pedido, estado) VALUES "
                + "(:c, DATE_ADD(curdate(), INTERVAL :d DAY), :e)",
                "c", idCliente, "d", diasDesdeHoy, "e", estado);
        idsPedido.add(id);
        jdbc.update("INSERT INTO detalle_pedido (id_pedido, id_producto, cantidad, "
                + "precio_unitario) VALUES (:p, :pr, :c, :pu)", new MapSqlParameterSource()
                        .addValue("p", id).addValue("pr", idProducto)
                        .addValue("c", new BigDecimal(cantidad))
                        .addValue("pu", new BigDecimal(precio)));
    }

    private Integer sembrarCompra(String estado, String fecha) {
        var id = insertar("INSERT INTO compras (id_proveedor, fecha, estado) VALUES (:p, :f, :e)",
                "p", idProveedor, "f", java.sql.Date.valueOf(fecha), "e", estado);
        idsCompra.add(id);
        jdbc.update("INSERT INTO detalle_compra (id_compra, id_materia_prima, cantidad, "
                + "precio_unitario) VALUES (:c, :mp, 10.00, 100.00)", new MapSqlParameterSource()
                        .addValue("c", id).addValue("mp", idMateriaPrima));
        return id;
    }

    private void sembrarPlan(String estado, int diasDesdeHoy, String planificada, String producida) {
        var id = insertar("INSERT INTO planes_produccion (fecha_planificacion, fecha_produccion, "
                + "estado, id_usuario) VALUES (curdate(), DATE_ADD(curdate(), INTERVAL :d DAY), "
                + ":e, :u)", "d", diasDesdeHoy, "e", estado, "u", idUsuario);
        idsPlan.add(id);
        jdbc.update("INSERT INTO detalle_plan_produccion (id_plan, id_producto, "
                + "cantidad_planificada, cantidad_producida) VALUES (:p, :pr, :pl, :pd)",
                new MapSqlParameterSource().addValue("p", id).addValue("pr", idProducto)
                        .addValue("pl", new BigDecimal(planificada))
                        .addValue("pd", new BigDecimal(producida)));
    }

    /** Siembra un lote de producto terminado que vence en {@code hoy + dias}; devuelve su código. */
    private String sembrarLote(String etiqueta, int dias, String disponible) {
        var codigo = (sufijo + "-" + etiqueta);
        codigo = codigo.substring(0, Math.min(30, codigo.length()));
        var id = insertar("INSERT INTO lotes (codigo_lote, id_producto, fecha_produccion, "
                + "fecha_vencimiento, cantidad) VALUES (:c, :p, DATE_SUB(curdate(), "
                + "INTERVAL 30 DAY), DATE_ADD(curdate(), INTERVAL :d DAY), 10.00)",
                "c", codigo, "p", idProducto, "d", dias);
        idsLote.add(id);
        jdbc.update("INSERT INTO inventario_producto_terminado (id_lote, cantidad_disponible) "
                + "VALUES (:l, :c)", new MapSqlParameterSource().addValue("l", id)
                        .addValue("c", new BigDecimal(disponible)));
        return codigo;
    }

    private Integer insertar(String sql, Object... parametros) {
        var params = new MapSqlParameterSource();
        for (int i = 0; i < parametros.length; i += 2) {
            params.addValue((String) parametros[i], parametros[i + 1]);
        }
        var generadas = new GeneratedKeyHolder();
        jdbc.update(sql, params, generadas);
        Number clave = generadas.getKey();
        if (clave == null) {
            throw new IllegalStateException("La base no devolvió el id del fixture insertado.");
        }
        return clave.intValue();
    }

    private void borrarIn(String tabla, String columna, List<Integer> ids) {
        if (!ids.isEmpty()) {
            jdbc.update("DELETE FROM " + tabla + " WHERE " + columna + " IN (:ids)",
                    new MapSqlParameterSource().addValue("ids", ids));
        }
    }

    private void borrar(String tabla, String columna, Integer id) {
        if (id != null) {
            jdbc.update("DELETE FROM " + tabla + " WHERE " + columna + " = :id",
                    new MapSqlParameterSource().addValue("id", id));
        }
    }
}
