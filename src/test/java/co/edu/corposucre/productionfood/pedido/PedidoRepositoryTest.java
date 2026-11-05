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

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.GeneratedKeyHolder;

/**
 * Pruebas de integración de {@link PedidoRepository} contra MySQL (HU-16).
 *
 * <p>El proyecto no usa Testcontainers ni H2: la única base soportada es la del
 * {@code docker-compose}. La prueba se conecta con los mismos parámetros que
 * {@code application.yml} (variables de entorno con los mismos valores por defecto) y se omite
 * con {@code assumeTrue} si la base o la tabla no están disponibles, para no fallar en un
 * entorno sin el contenedor levantado.
 *
 * <p>Cada prueba crea su propio cliente, sus productos y sus pedidos, y los elimina al terminar.
 * Los pedidos sembrados con {@code INSERT} directo —los que necesitan una {@code fecha_pedido}
 * específica para filtrar u ordenar— viven en 1990, un rango lejano a cualquier dato realista
 * de la base, y se limpian por id porque todos se registran en la lista propia.
 */
class PedidoRepositoryTest {

    private static final String URL = "jdbc:mysql://"
            + entorno("DB_HOST", "127.0.0.1") + ":" + entorno("DB_PORT", "3306")
            + "/" + entorno("DB_NAME", "productionfood")
            + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=America/Bogota";
    private static final String USUARIO = entorno("DB_USER", "root");
    private static final String CLAVE = entorno("DB_PASSWORD", "local");

    /** Rango propio de la siembra filtrable; lejano a cualquier dato realista de la base. */
    private static final LocalDate INICIO = LocalDate.of(1990, 11, 1);
    private static final LocalDate FIN = LocalDate.of(1990, 11, 30);

    private NamedParameterJdbcTemplate jdbc;
    private PedidoRepository repository;

    private String sufijo;
    private Integer idUnidad;
    private final List<Integer> idsProducto = new ArrayList<>();
    private Integer idCliente;
    private final List<Integer> idsClienteExtra = new ArrayList<>();
    private final List<Integer> idsPedido = new ArrayList<>();
    private final List<Integer> idsLote = new ArrayList<>();
    private final List<Integer> idsDevolucion = new ArrayList<>();

    private static String entorno(String nombre, String defecto) {
        String valor = System.getenv(nombre);
        return valor == null || valor.isBlank() ? defecto : valor;
    }

    @BeforeAll
    static void laBaseDelProyectoEstaDisponible() {
        boolean disponible;
        try (var conexion = java.sql.DriverManager.getConnection(URL, USUARIO, CLAVE);
             var sentencia = conexion.createStatement()) {
            sentencia.executeQuery("SELECT 1 FROM pedidos LIMIT 1").close();
            disponible = true;
        } catch (SQLException e) {
            disponible = false;
        }
        assumeTrue(disponible,
                "MySQL no responde en " + URL + " con la tabla pedidos; se omite.");
    }

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource(URL, USUARIO, CLAVE);
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        jdbc = new NamedParameterJdbcTemplate(dataSource);
        repository = new PedidoRepository(jdbc);

        // Sufijo único por prueba: nombres tienen índice en la base y una corrida anterior que
        // deje datos no debe confundir las lecturas por nombre.
        sufijo = "-" + UUID.randomUUID().toString().substring(0, 8);
        idUnidad = insertarConId("""
                INSERT INTO unidades_medida (nombre, abreviatura)
                VALUES (:nombre, :abreviatura)
                """, new MapSqlParameterSource()
                        .addValue("nombre", "Unidad HU-16 test" + sufijo)
                        .addValue("abreviatura", "u16" + sufijo));
        // Dos productos: uk_detalle_pedido es (id_pedido, id_producto), así que un pedido con
        // dos líneas necesita dos productos distintos.
        idsProducto.add(insertarProducto("Producto A HU-16 test" + sufijo));
        idsProducto.add(insertarProducto("Producto B HU-16 test" + sufijo));
        idCliente = insertarConId("""
                INSERT INTO clientes (nombre, estado) VALUES (:nombre, 1)
                """, new MapSqlParameterSource()
                        .addValue("nombre", "Cliente HU-16 test" + sufijo));
    }

    @AfterEach
    void tearDown() {
        // El kardex y el inventario de un lote son FK de lotes: se borran antes que el lote.
        if (!idsLote.isEmpty()) {
            jdbc.update("DELETE FROM movimientos_inventario_pt WHERE id_lote IN (:ids)",
                    new MapSqlParameterSource().addValue("ids", idsLote));
            jdbc.update("DELETE FROM inventario_producto_terminado WHERE id_lote IN (:ids)",
                    new MapSqlParameterSource().addValue("ids", idsLote));
            jdbc.update("DELETE FROM lotes WHERE id_lote IN (:ids)",
                    new MapSqlParameterSource().addValue("ids", idsLote));
        }
        if (!idsDevolucion.isEmpty()) {
            jdbc.update("DELETE FROM devoluciones WHERE id_devolucion IN (:ids)",
                    new MapSqlParameterSource().addValue("ids", idsDevolucion));
        }
        // El detalle primero que la cabecera: detalle_pedido → pedidos es FK.
        if (!idsPedido.isEmpty()) {
            jdbc.update("DELETE FROM detalle_pedido WHERE id_pedido IN (:ids)",
                    new MapSqlParameterSource().addValue("ids", idsPedido));
            jdbc.update("DELETE FROM pedidos WHERE id_pedido IN (:ids)",
                    new MapSqlParameterSource().addValue("ids", idsPedido));
        }
        if (!idsClienteExtra.isEmpty()) {
            jdbc.update("DELETE FROM clientes WHERE id_cliente IN (:ids)",
                    new MapSqlParameterSource().addValue("ids", idsClienteExtra));
        }
        if (idCliente != null) {
            jdbc.update("DELETE FROM clientes WHERE id_cliente = :id",
                    new MapSqlParameterSource().addValue("id", idCliente));
        }
        if (!idsProducto.isEmpty()) {
            jdbc.update("DELETE FROM productos WHERE id_producto IN (:ids)",
                    new MapSqlParameterSource().addValue("ids", idsProducto));
        }
        if (idUnidad != null) {
            jdbc.update("DELETE FROM unidades_medida WHERE id_unidad = :id",
                    new MapSqlParameterSource().addValue("id", idUnidad));
        }
    }

    @Test
    @DisplayName("R-01: el INSERT no envía fecha_pedido y la base le pone CURDATE()")
    void insertarOmiteFechaPedidoYLaBasePoneCurdate() {
        var id = insertarConRepositorio(pedidoNuevo());

        var leido = repository.buscarPorId(id).orElseThrow();
        assertThat(leido.getFechaPedido()).isEqualTo(LocalDate.now());
    }

    @Test
    @DisplayName("R-02: insertar fija el estado PENDIENTE, nunca otro")
    void insertarFijaElEstadoPendiente() {
        var id = insertarConRepositorio(pedidoNuevo());

        var leido = repository.buscarPorId(id).orElseThrow();
        assertThat(leido.getEstado()).isEqualTo(EstadoPedido.PENDIENTE);
    }

    @Test
    @DisplayName("CA-03: insertar persiste la fecha de entrega en la misma sentencia")
    void insertarPersisteLaFechaDeEntrega() {
        var pedido = pedidoNuevo();
        pedido.setFechaEntrega(LocalDate.now().plusDays(3));

        var id = insertarConRepositorio(pedido);

        var leido = repository.buscarPorId(id).orElseThrow();
        assertThat(leido.getFechaEntrega()).isEqualTo(pedido.getFechaEntrega());
    }

    @Test
    @DisplayName("buscarPorId devuelve el pedido con el nombre del cliente del JOIN")
    void buscarPorIdTraeElNombreDelCliente() {
        var id = insertarPedidoDirecto(idCliente, INICIO, EstadoPedido.PENDIENTE);

        var leido = repository.buscarPorId(id).orElseThrow();

        assertThat(leido.getIdPedido()).isEqualTo(id);
        assertThat(leido.getIdCliente()).isEqualTo(idCliente);
        assertThat(leido.getNombreCliente()).isEqualTo("Cliente HU-16 test" + sufijo);
        assertThat(leido.getFechaPedido()).isEqualTo(INICIO);
        assertThat(leido.getEstado()).isEqualTo(EstadoPedido.PENDIENTE);
    }

    @Test
    @DisplayName("HU-25 CP-14: totalBruto y totalNeto de un pedido sin devoluciones son el total")
    void importesSinDevolucionesCoincidenConElTotal() {
        var id = insertarPedidoDirecto(idCliente, INICIO, EstadoPedido.ENTREGADO);
        insertarLinea(id, idsProducto.get(0), "2.00", "50.00");
        insertarLinea(id, idsProducto.get(1), "3.00", "10.00");

        var leido = repository.buscarPorId(id).orElseThrow();

        assertThat(leido.getTotalPedido()).isEqualByComparingTo("130.00");
        assertThat(leido.getTotalBruto()).isEqualByComparingTo("130.00");
        assertThat(leido.getDevoluciones()).isEqualByComparingTo("0.00");
        assertThat(leido.getTotalNeto()).isEqualByComparingTo("130.00");
    }

    @Test
    @DisplayName("HU-25 CP-15/16: solo las devoluciones APROBADA descuentan, al precio histórico")
    void soloLasDevolucionesAprobadasDescuentan() {
        var id = insertarPedidoDirecto(idCliente, INICIO, EstadoPedido.ENTREGADO);
        var lineaA = insertarLinea(id, idsProducto.get(0), "10.00", "50.00");
        var lineaB = insertarLinea(id, idsProducto.get(1), "5.00", "20.00");
        insertarDevolucion(lineaA, "2.00", "APROBADA");
        insertarDevolucion(lineaA, "1.00", "APROBADA");
        insertarDevolucion(lineaB, "4.00", "PENDIENTE");
        insertarDevolucion(lineaB, "3.00", "RECHAZADA");

        var leido = repository.buscarPorId(id).orElseThrow();

        // bruto 10*50 + 5*20 = 600; devuelto aprobado (2+1)*50 = 150; neto 450.
        assertThat(leido.getTotalBruto()).isEqualByComparingTo("600.00");
        assertThat(leido.getDevoluciones()).isEqualByComparingTo("150.00");
        assertThat(leido.getTotalNeto()).isEqualByComparingTo("450.00");
        assertThat(leido.getTotalPedido()).isEqualByComparingTo("600.00");
    }

    @Test
    @DisplayName("HU-25 R-02: cambiar productos.precio no cambia los importes del pedido")
    void cambiarElPrecioDelCatalogoNoAlteraLosImportes() {
        var id = insertarPedidoDirecto(idCliente, INICIO, EstadoPedido.ENTREGADO);
        var linea = insertarLinea(id, idsProducto.get(0), "4.00", "25.00");
        insertarDevolucion(linea, "1.00", "APROBADA");

        jdbc.update("UPDATE productos SET precio = 9999.00 WHERE id_producto = :id",
                new MapSqlParameterSource().addValue("id", idsProducto.get(0)));

        var leido = repository.buscarPorId(id).orElseThrow();
        assertThat(leido.getTotalBruto()).isEqualByComparingTo("100.00");
        assertThat(leido.getDevoluciones()).isEqualByComparingTo("25.00");
        assertThat(leido.getTotalNeto()).isEqualByComparingTo("75.00");
    }

    @Test
    @DisplayName("HU-25: las devoluciones de un pedido no contaminan a otro ni duplican filas")
    void lasDevolucionesNoSeCruzanEntrePedidos() {
        var a = insertarPedidoDirecto(idCliente, INICIO, EstadoPedido.ENTREGADO);
        var b = insertarPedidoDirecto(idCliente, INICIO.plusDays(1), EstadoPedido.ENTREGADO);
        var lineaA = insertarLinea(a, idsProducto.get(0), "2.00", "10.00");
        insertarLinea(b, idsProducto.get(0), "2.00", "10.00");
        insertarDevolucion(lineaA, "1.00", "APROBADA");

        var filas = repository.buscar(idCliente, null, INICIO, FIN, "p.id_pedido", true, 10, 0);

        assertThat(filas).hasSize(2);
        assertThat(filas.get(0).getDevoluciones()).isEqualByComparingTo("10.00");
        assertThat(filas.get(0).getTotalNeto()).isEqualByComparingTo("10.00");
        assertThat(filas.get(1).getDevoluciones()).isEqualByComparingTo("0.00");
        assertThat(filas.get(1).getTotalNeto()).isEqualByComparingTo("20.00");
    }

    @Test
    @DisplayName("HU-25: un pedido sin líneas responde importes en cero")
    void pedidoSinLineasTieneImportesEnCero() {
        var id = insertarPedidoDirecto(idCliente, INICIO, EstadoPedido.PENDIENTE);

        var leido = repository.buscarPorId(id).orElseThrow();

        assertThat(leido.getTotalPedido()).isEqualByComparingTo("0.00");
        assertThat(leido.getTotalBruto()).isEqualByComparingTo("0.00");
        assertThat(leido.getDevoluciones()).isEqualByComparingTo("0.00");
        assertThat(leido.getTotalNeto()).isEqualByComparingTo("0.00");
        assertThat(leido.getCantidadItems()).isZero();
    }

    @Test
    @DisplayName("HU-25: buscar ordena por la columna calculada total_pedido")
    void buscarOrdenaPorTotalPedido() {
        var barato = insertarPedidoDirecto(idCliente, INICIO, EstadoPedido.PENDIENTE);
        var caro = insertarPedidoDirecto(idCliente, INICIO.plusDays(1), EstadoPedido.PENDIENTE);
        insertarLinea(barato, idsProducto.get(0), "1.00", "10.00");
        insertarLinea(caro, idsProducto.get(0), "1.00", "900.00");

        var asc = repository.buscar(idCliente, null, INICIO, FIN, "total_pedido", true, 10, 0);
        var desc = repository.buscar(idCliente, null, INICIO, FIN, "total_pedido", false, 10, 0);

        assertThat(asc).extracting(Pedido::getIdPedido).containsExactly(barato, caro);
        assertThat(desc).extracting(Pedido::getIdPedido).containsExactly(caro, barato);
    }

    @Test
    @DisplayName("HU-25 CA-04: buscarConDetalle trae una fila por línea y una fila sin línea por "
            + "pedido sin detalle, en el orden de la página")
    void buscarConDetalleTraeLineasYPedidosSinLineas() {
        var conDos = insertarPedidoDirecto(idCliente, INICIO, EstadoPedido.PENDIENTE);
        var sinLineas = insertarPedidoDirecto(idCliente, INICIO.plusDays(1),
                EstadoPedido.PENDIENTE);
        var conUna = insertarPedidoDirecto(idCliente, INICIO.plusDays(2),
                EstadoPedido.PENDIENTE);
        var lineaA = insertarLinea(conDos, idsProducto.get(0), "3.00", "10.50");
        var lineaB = insertarLinea(conDos, idsProducto.get(1), "2.00", "4.00");
        var lineaC = insertarLinea(conUna, idsProducto.get(0), "1.50", "2.00");

        var filas = repository.buscarConDetalle(idCliente, null, INICIO, FIN, "p.fecha_pedido",
                true, 10, 0);

        assertThat(filas).extracting(f -> f.pedido().getIdPedido())
                .containsExactly(conDos, conDos, sinLineas, conUna);
        assertThat(filas.get(0).linea().idDetallePedido()).isEqualTo(lineaA);
        assertThat(filas.get(1).linea().idDetallePedido()).isEqualTo(lineaB);
        assertThat(filas.get(2).conDetalle()).isFalse();
        assertThat(filas.get(2).linea()).isNull();
        assertThat(filas.get(3).linea().idDetallePedido()).isEqualTo(lineaC);
        // La cabecera trae los mismos importes derivados que buscar.
        assertThat(filas.get(0).pedido().getTotalPedido()).isEqualByComparingTo("39.50");
        assertThat(filas.get(0).pedido().getCantidadItems()).isEqualTo(2);
        assertThat(filas.get(0).pedido().getNombreCliente())
                .isEqualTo("Cliente HU-16 test" + sufijo);
        assertThat(filas.get(2).pedido().getTotalPedido()).isEqualByComparingTo("0.00");
        // La línea trae producto y unidad de los JOIN, y cantidad y precio de la línea.
        var linea = filas.get(0).linea();
        assertThat(linea.nombreProducto()).isEqualTo("Producto A HU-16 test" + sufijo);
        assertThat(linea.unidad()).isEqualTo("u16" + sufijo);
        assertThat(linea.cantidad()).isEqualByComparingTo("3.00");
        assertThat(linea.precioUnitario()).isEqualByComparingTo("10.50");
    }

    @Test
    @DisplayName("HU-25 R-02: la línea usa detalle_pedido.precio_unitario aunque cambie "
            + "productos.precio")
    void buscarConDetalleUsaElPrecioHistorico() {
        var id = insertarPedidoDirecto(idCliente, INICIO, EstadoPedido.PENDIENTE);
        insertarLinea(id, idsProducto.get(0), "2.00", "7.00");
        jdbc.update("UPDATE productos SET precio = 9999.00 WHERE id_producto = :id",
                new MapSqlParameterSource("id", idsProducto.get(0)));

        var filas = repository.buscarConDetalle(idCliente, null, INICIO, FIN, "p.fecha_pedido",
                true, 10, 0);

        assertThat(filas).hasSize(1);
        assertThat(filas.get(0).linea().precioUnitario()).isEqualByComparingTo("7.00");
    }

    @Test
    @DisplayName("HU-25 R-06: la página se corta por pedidos, antes de unir el detalle")
    void buscarConDetallePaginaPorPedidosYNoPorFilas() {
        var primero = insertarPedidoDirecto(idCliente, INICIO, EstadoPedido.PENDIENTE);
        var segundo = insertarPedidoDirecto(idCliente, INICIO.plusDays(1),
                EstadoPedido.PENDIENTE);
        var tercero = insertarPedidoDirecto(idCliente, INICIO.plusDays(2),
                EstadoPedido.PENDIENTE);
        insertarLinea(primero, idsProducto.get(0), "1.00", "1.00");
        insertarLinea(primero, idsProducto.get(1), "1.00", "1.00");
        insertarLinea(segundo, idsProducto.get(0), "1.00", "1.00");

        var paginaUno = repository.buscarConDetalle(idCliente, null, INICIO, FIN,
                "p.fecha_pedido", true, 2, 0);
        var paginaDos = repository.buscarConDetalle(idCliente, null, INICIO, FIN,
                "p.fecha_pedido", true, 2, 2);

        // Dos pedidos en la página aunque sean tres filas; un LIMIT sobre el JOIN habría
        // devuelto solo dos filas, ambas del primer pedido.
        assertThat(paginaUno).extracting(f -> f.pedido().getIdPedido())
                .containsExactly(primero, primero, segundo);
        assertThat(paginaDos).extracting(f -> f.pedido().getIdPedido())
                .containsExactly(tercero);
    }

    @Test
    @DisplayName("HU-25: buscarConDetalle ordena por el alias calculado total_pedido y aplica "
            + "los filtros de buscar")
    void buscarConDetalleOrdenaPorTotalYFiltra() {
        var otroCliente = insertarClienteExtra("Cliente extra HU-16 test" + sufijo);
        var barato = insertarPedidoDirecto(idCliente, INICIO, EstadoPedido.PENDIENTE);
        var caro = insertarPedidoDirecto(idCliente, INICIO.plusDays(1), EstadoPedido.PENDIENTE);
        var ajeno = insertarPedidoDirecto(otroCliente, INICIO, EstadoPedido.PENDIENTE);
        var otroEstado = insertarPedidoDirecto(idCliente, INICIO, EstadoPedido.EN_PREPARACION);
        insertarLinea(barato, idsProducto.get(0), "1.00", "10.00");
        insertarLinea(caro, idsProducto.get(0), "1.00", "900.00");
        insertarLinea(ajeno, idsProducto.get(0), "1.00", "5000.00");
        insertarLinea(otroEstado, idsProducto.get(0), "1.00", "5000.00");

        var desc = repository.buscarConDetalle(idCliente, EstadoPedido.PENDIENTE, INICIO, FIN,
                "total_pedido", false, 10, 0);
        var asc = repository.buscarConDetalle(idCliente, EstadoPedido.PENDIENTE, INICIO, FIN,
                "total_pedido", true, 10, 0);

        assertThat(desc).extracting(f -> f.pedido().getIdPedido()).containsExactly(caro, barato);
        assertThat(asc).extracting(f -> f.pedido().getIdPedido()).containsExactly(barato, caro);
    }

    @Test
    @DisplayName("HU-25 R-06: buscarConDetalle ejecuta una sola consulta, sin una por pedido")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void buscarConDetalleEjecutaUnaSolaConsulta() {
        for (int i = 0; i < 4; i++) {
            var id = insertarPedidoDirecto(idCliente, INICIO.plusDays(i), EstadoPedido.PENDIENTE);
            insertarLinea(id, idsProducto.get(0), "1.00", "1.00");
        }
        var espia = org.mockito.Mockito.spy(jdbc);
        var repositorioEspiado = new PedidoRepository(espia);

        var filas = repositorioEspiado.buscarConDetalle(idCliente, null, INICIO, FIN,
                "p.fecha_pedido", true, 10, 0);

        assertThat(filas).hasSize(4);
        org.mockito.Mockito.verify(espia, org.mockito.Mockito.times(1)).query(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(
                        org.springframework.jdbc.core.namedparam.SqlParameterSource.class),
                org.mockito.ArgumentMatchers.any(
                        org.springframework.jdbc.core.RowMapper.class));
    }

    @Test
    @DisplayName("contar y buscar aplican los mismos filtros de cliente, estado y rango de fechas")
    void contarYBuscarFiltranPorClienteEstadoYRango() {
        var otroCliente = insertarClienteExtra("Cliente extra HU-16 test" + sufijo);
        var propia = insertarPedidoDirecto(idCliente, INICIO, EstadoPedido.PENDIENTE);
        insertarPedidoDirecto(idCliente, INICIO.plusDays(9), EstadoPedido.EN_PREPARACION);
        insertarPedidoDirecto(otroCliente, INICIO.plusDays(9), EstadoPedido.PENDIENTE);
        // Fuera del rango propio: no debe entrar en ningún conteo de noviembre.
        insertarPedidoDirecto(idCliente, FIN.plusDays(5), EstadoPedido.PENDIENTE);

        assertThat(repository.contar(idCliente, EstadoPedido.PENDIENTE, INICIO, FIN))
                .isEqualTo(1L);
        assertThat(repository.contar(idCliente, null, INICIO, FIN)).isEqualTo(2L);
        assertThat(repository.contar(null, null, INICIO, FIN)).isEqualTo(3L);

        var filas = repository.buscar(idCliente, EstadoPedido.PENDIENTE, INICIO, FIN,
                "p.fecha_pedido", true, 20, 0);
        assertThat(filas).extracting(Pedido::getIdPedido).containsExactly(propia);
    }

    @Test
    @DisplayName("buscar ordena por la propiedad pedida y el desempate por id_pedido es estable")
    void buscarOrdenaPorLaPropiedadYPorIdDescComoDesempate() {
        var primero = insertarPedidoDirecto(idCliente, INICIO, EstadoPedido.PENDIENTE);
        var segundo = insertarPedidoDirecto(idCliente, INICIO.plusDays(1),
                EstadoPedido.PENDIENTE);
        var tercero = insertarPedidoDirecto(idCliente, INICIO.plusDays(1),
                EstadoPedido.PENDIENTE);
        var cuarto = insertarPedidoDirecto(idCliente, INICIO.plusDays(2), EstadoPedido.PENDIENTE);

        var ascendente = repository.buscar(null, null, INICIO, FIN, "p.fecha_pedido", true,
                20, 0);
        var descendente = repository.buscar(null, null, INICIO, FIN, "p.fecha_pedido", false,
                20, 0);

        // El empate de fecha del día 2 se resuelve por id_pedido DESC: el tercero (insertado
        // después) va antes que el segundo, y el mismo par cae en el mismo lugar en ambos
        // sentidos sin perder filas ni duplicarlas.
        assertThat(ascendente).extracting(Pedido::getIdPedido)
                .containsExactly(primero, tercero, segundo, cuarto);
        assertThat(descendente).extracting(Pedido::getIdPedido)
                .containsExactly(cuarto, tercero, segundo, primero);
    }

    @Test
    @DisplayName("cambiarEstado actualiza la fila solo si sigue en el estado esperado")
    void cambiarEstadoActualizaLaFila() {
        var id = insertarPedidoDirecto(idCliente, INICIO, EstadoPedido.PENDIENTE);

        var afectadas = repository.cambiarEstado(id, EstadoPedido.PENDIENTE,
                EstadoPedido.EN_PREPARACION);
        // La segunda vez el pedido ya no está PENDIENTE: cero filas, sin doble transición.
        var repetida = repository.cambiarEstado(id, EstadoPedido.PENDIENTE,
                EstadoPedido.CANCELADO);

        assertThat(afectadas).isEqualTo(1);
        assertThat(repetida).isZero();
        var leido = repository.buscarPorId(id).orElseThrow();
        assertThat(leido.getEstado()).isEqualTo(EstadoPedido.EN_PREPARACION);
    }

    @Test
    @DisplayName("R-07/R-10: lineasParaDevolucion devuelve el neto por lote del kardex del pedido")
    void lineasParaDevolucionDevuelveElNetoPorLoteDelPedido() {
        var pedido = insertarPedidoDirecto(idCliente, INICIO, EstadoPedido.EN_PREPARACION);
        var otroPedido = insertarPedidoDirecto(idCliente, INICIO, EstadoPedido.EN_PREPARACION);
        var loteA = insertarLote(idsProducto.get(0), "3.00");
        var loteB = insertarLote(idsProducto.get(1), "2.00");
        var loteC = insertarLote(idsProducto.get(0), "1.00");
        var detalleA = insertarLinea(pedido, idsProducto.get(0), "3.00", "1250.50");
        var detalleB = insertarLinea(pedido, idsProducto.get(1), "2.00", "800.00");
        var detalleOtro = insertarLinea(otroPedido, idsProducto.get(0), "1.00", "1250.50");

        // Salidas del pedido, una salida de otro pedido, una devolución de una cancelación
        // anterior —con origen 'pedidos', que no netea— y una reducción de línea con origen
        // 'detalle_pedido', que sí descuenta de lo que queda por reponer.
        insertarMovimiento(loteA, "SALIDA_VENTA", "3.00", "detalle_pedido", detalleA);
        insertarMovimiento(loteB, "SALIDA_VENTA", "2.00", "detalle_pedido", detalleB);
        insertarMovimiento(loteA, "SALIDA_VENTA", "1.00", "detalle_pedido", detalleOtro);
        insertarMovimiento(loteA, "ENTRADA_DEVOLUCION", "3.00", "pedidos", pedido);
        insertarMovimiento(loteA, "ENTRADA_DEVOLUCION", "1.00", "detalle_pedido", detalleA);
        // Lote C: salida y devolución completas, neto cero; no debe aparecer.
        insertarMovimiento(loteC, "SALIDA_VENTA", "1.00", "detalle_pedido", detalleB);
        insertarMovimiento(loteC, "ENTRADA_DEVOLUCION", "1.00", "detalle_pedido", detalleB);

        var lineas = repository.lineasParaDevolucion(pedido);

        // Lote A neto 3−1 = 2 (la devolución de 'pedidos' no netea) y lote B neto 2. El orden es
        // MAX(id_movimiento) DESC: el último movimiento del lote A se sembró después del de B.
        assertThat(lineas)
                .extracting(LineaDevolucion::idLote, LineaDevolucion::cantidad)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(loteA, new BigDecimal("2.00")),
                        org.assertj.core.groups.Tuple.tuple(loteB, new BigDecimal("2.00")));
    }

    @Test
    @DisplayName("total_pedido y cantidad_items se calculan del detalle; sin líneas responden 0")
    void totalYCantidadItemsSeCalculanDelDetallePedido() {
        var conLineas = insertarConRepositorio(pedidoNuevo());
        insertarLinea(conLineas, idsProducto.get(0), "3.00", "1250.50");
        insertarLinea(conLineas, idsProducto.get(1), "2.00", "800.00");
        var sinLineas = insertarConRepositorio(pedidoNuevo());

        var leido = repository.buscarPorId(conLineas).orElseThrow();
        // 3 × 1250.50 + 2 × 800.00 = 5351.50, a escala 2.
        assertThat(leido.getTotalPedido()).isEqualByComparingTo("5351.50");
        assertThat(leido.getTotalPedido().scale()).isEqualTo(2);
        assertThat(leido.getCantidadItems()).isEqualTo(2);

        var vacio = repository.buscarPorId(sinLineas).orElseThrow();
        assertThat(vacio.getTotalPedido()).isEqualByComparingTo("0.00");
        assertThat(vacio.getTotalPedido().scale()).isEqualTo(2);
        assertThat(vacio.getCantidadItems()).isZero();
    }

    @Test
    @DisplayName("actualizar cambia cliente y fecha_entrega, pero ni fecha_pedido ni estado")
    void actualizarCambiaClienteYFechaEntregaSinTocarFechaPedidoNiEstado() {
        var otroCliente = insertarClienteExtra("Cliente edición HU-16 test" + sufijo);
        var id = insertarConRepositorio(pedidoNuevo());
        var antes = repository.buscarPorId(id).orElseThrow();

        var editado = new Pedido();
        editado.setIdPedido(id);
        editado.setIdCliente(otroCliente);
        editado.setFechaEntrega(LocalDate.of(1990, 12, 24));
        repository.actualizar(editado);

        var leido = repository.buscarPorId(id).orElseThrow();
        assertThat(leido.getIdCliente()).isEqualTo(otroCliente);
        assertThat(leido.getFechaEntrega()).isEqualTo(LocalDate.of(1990, 12, 24));
        assertThat(leido.getFechaPedido()).isEqualTo(antes.getFechaPedido());
        assertThat(leido.getEstado()).isEqualTo(EstadoPedido.PENDIENTE);
    }

    // ---- HU-25 PR3: agregaciones ----

    /** Rango propio de las agregaciones, distinto del de la siembra filtrable. */
    private static final LocalDate AGR_INICIO = LocalDate.of(1990, 3, 1);
    private static final LocalDate AGR_FIN = LocalDate.of(1990, 3, 31);

    @Test
    @DisplayName("HU-25 resumenPorPeriodo: agrupa por estado, cuenta pedidos y suma al precio "
            + "histórico")
    void resumenAgrupaPorEstado() {
        var e1 = insertarPedidoDirecto(idCliente, AGR_INICIO, EstadoPedido.ENTREGADO);
        insertarLinea(e1, idsProducto.get(0), "2.00", "50.00");
        insertarLinea(e1, idsProducto.get(1), "1.00", "20.00");
        var e2 = insertarPedidoDirecto(idCliente, AGR_FIN, EstadoPedido.ENTREGADO);
        insertarLinea(e2, idsProducto.get(0), "1.00", "50.00");
        var p = insertarPedidoDirecto(idCliente, AGR_INICIO.plusDays(3), EstadoPedido.PENDIENTE);
        insertarLinea(p, idsProducto.get(0), "3.00", "10.00");
        var c = insertarPedidoDirecto(idCliente, AGR_INICIO.plusDays(4), EstadoPedido.CANCELADO);
        insertarLinea(c, idsProducto.get(0), "9.00", "10.00");
        // Fuera del rango: no debe contar.
        var fuera = insertarPedidoDirecto(idCliente, AGR_FIN.plusDays(1), EstadoPedido.ENTREGADO);
        insertarLinea(fuera, idsProducto.get(0), "100.00", "100.00");

        var filas = repository.resumenPorPeriodo(AGR_INICIO, AGR_FIN);

        var entregado = filas.stream().filter(f -> f.estado() == EstadoPedido.ENTREGADO)
                .findFirst().orElseThrow();
        // Los pedidos de los dos extremos del rango entran (inclusivo).
        assertThat(entregado.cantidad()).isEqualTo(2);
        assertThat(entregado.total()).isEqualByComparingTo("170.00");
        assertThat(entregado.total().scale()).isEqualTo(2);
        var pendiente = filas.stream().filter(f -> f.estado() == EstadoPedido.PENDIENTE)
                .findFirst().orElseThrow();
        assertThat(pendiente.cantidad()).isEqualTo(1);
        assertThat(pendiente.total()).isEqualByComparingTo("30.00");
        var cancelado = filas.stream().filter(f -> f.estado() == EstadoPedido.CANCELADO)
                .findFirst().orElseThrow();
        assertThat(cancelado.cantidad()).isEqualTo(1);
        assertThat(cancelado.total()).isEqualByComparingTo("90.00");
        assertThat(filas).extracting(ResumenPedidoEstadoFila::estado)
                .doesNotContain(EstadoPedido.EN_PREPARACION);
    }

    @Test
    @DisplayName("HU-25 CP-15/16: el resumen descuenta solo devoluciones APROBADA y sin duplicar "
            + "por varias devoluciones")
    void resumenDescuentaSoloDevolucionesAprobadas() {
        var e = insertarPedidoDirecto(idCliente, AGR_INICIO, EstadoPedido.ENTREGADO);
        var lineaA = insertarLinea(e, idsProducto.get(0), "10.00", "50.00");
        var lineaB = insertarLinea(e, idsProducto.get(1), "5.00", "20.00");
        insertarDevolucion(lineaA, "2.00", "APROBADA");
        insertarDevolucion(lineaA, "1.00", "APROBADA");
        insertarDevolucion(lineaB, "4.00", "PENDIENTE");
        insertarDevolucion(lineaB, "3.00", "RECHAZADA");

        var filas = repository.resumenPorPeriodo(AGR_INICIO, AGR_FIN);

        assertThat(filas).hasSize(1);
        // Dos devoluciones a la misma línea no deben multiplicar el bruto: 600, no 1200.
        assertThat(filas.get(0).total()).isEqualByComparingTo("600.00");
        assertThat(filas.get(0).devoluciones()).isEqualByComparingTo("150.00");
    }

    @Test
    @DisplayName("HU-25 CP-14: el resumen usa precio_unitario aunque cambie productos.precio")
    void resumenUsaElPrecioHistorico() {
        var e = insertarPedidoDirecto(idCliente, AGR_INICIO, EstadoPedido.ENTREGADO);
        var linea = insertarLinea(e, idsProducto.get(0), "4.00", "25.00");
        insertarDevolucion(linea, "1.00", "APROBADA");

        jdbc.update("UPDATE productos SET precio = 9999.00 WHERE id_producto = :id",
                new MapSqlParameterSource().addValue("id", idsProducto.get(0)));

        var fila = repository.resumenPorPeriodo(AGR_INICIO, AGR_FIN).get(0);
        assertThat(fila.total()).isEqualByComparingTo("100.00");
        assertThat(fila.devoluciones()).isEqualByComparingTo("25.00");
    }

    @Test
    @DisplayName("HU-25: un pedido sin líneas cuenta en su estado con total 0.00")
    void resumenCuentaUnPedidoSinLineas() {
        insertarPedidoDirecto(idCliente, AGR_INICIO, EstadoPedido.PENDIENTE);

        var filas = repository.resumenPorPeriodo(AGR_INICIO, AGR_INICIO);

        assertThat(filas).hasSize(1);
        assertThat(filas.get(0).cantidad()).isEqualTo(1);
        assertThat(filas.get(0).total()).isEqualByComparingTo("0.00");
        assertThat(filas.get(0).devoluciones()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("HU-25 CP-22: un rango sin pedidos devuelve la lista vacía")
    void resumenDeUnRangoVacio() {
        var vacio = LocalDate.of(1990, 1, 15);

        assertThat(repository.resumenPorPeriodo(vacio, vacio)).isEmpty();
        assertThat(repository.ventasPorCliente(vacio, vacio)).isEmpty();
        assertThat(repository.productosMasVendidos(vacio, vacio)).isEmpty();
    }

    @Test
    @DisplayName("HU-25 CP-21: ventas por cliente ordena por total descendente y excluye "
            + "cancelados")
    void ventasPorClienteOrdenaYExcluyeCancelados() {
        var otro = insertarClienteExtra("Cliente extra HU-25 test" + sufijo);
        var chico = insertarClienteExtra("Cliente chico HU-25 test" + sufijo);
        var a1 = insertarPedidoDirecto(idCliente, AGR_INICIO, EstadoPedido.ENTREGADO);
        insertarLinea(a1, idsProducto.get(0), "1.00", "100.00");
        var a2 = insertarPedidoDirecto(idCliente, AGR_FIN, EstadoPedido.PENDIENTE);
        insertarLinea(a2, idsProducto.get(0), "1.00", "50.00");
        var b = insertarPedidoDirecto(otro, AGR_INICIO, EstadoPedido.ENTREGADO);
        insertarLinea(b, idsProducto.get(0), "1.00", "400.00");
        // Un cancelado enorme no cuenta como venta: el cliente "chico" ni aparece.
        var cancelado = insertarPedidoDirecto(chico, AGR_INICIO, EstadoPedido.CANCELADO);
        insertarLinea(cancelado, idsProducto.get(0), "10.00", "1000.00");
        var canceladoDeA = insertarPedidoDirecto(idCliente, AGR_INICIO, EstadoPedido.CANCELADO);
        insertarLinea(canceladoDeA, idsProducto.get(0), "10.00", "1000.00");

        var filas = repository.ventasPorCliente(AGR_INICIO, AGR_FIN);

        assertThat(filas).extracting(VentaClienteFila::idCliente).containsExactly(otro, idCliente);
        assertThat(filas.get(0).cliente()).isEqualTo("Cliente extra HU-25 test" + sufijo);
        assertThat(filas.get(0).cantidadPedidos()).isEqualTo(1);
        assertThat(filas.get(0).total()).isEqualByComparingTo("400.00");
        assertThat(filas.get(0).total().scale()).isEqualTo(2);
        assertThat(filas.get(1).cantidadPedidos()).isEqualTo(2);
        assertThat(filas.get(1).total()).isEqualByComparingTo("150.00");
    }

    @Test
    @DisplayName("HU-25 CP-14: ventas por cliente y ranking usan el precio histórico")
    void ventasYRankingUsanElPrecioHistorico() {
        var e = insertarPedidoDirecto(idCliente, AGR_INICIO, EstadoPedido.ENTREGADO);
        insertarLinea(e, idsProducto.get(0), "4.00", "25.00");
        jdbc.update("UPDATE productos SET precio = 9999.00 WHERE id_producto = :id",
                new MapSqlParameterSource().addValue("id", idsProducto.get(0)));

        var ventas = repository.ventasPorCliente(AGR_INICIO, AGR_FIN);
        var ranking = repository.productosMasVendidos(AGR_INICIO, AGR_FIN);

        assertThat(ventas.get(0).total()).isEqualByComparingTo("100.00");
        assertThat(ranking.get(0).importe()).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("HU-25 CP-20 R-07: el ranking ordena por unidades, no por importe")
    void rankingPorUnidadesNoPorImporte() {
        // Producto A: caro, 3 unidades en 3 pedidos. Producto B: barato, 200 unidades en 2.
        var p1 = insertarPedidoDirecto(idCliente, AGR_INICIO, EstadoPedido.ENTREGADO);
        var p2 = insertarPedidoDirecto(idCliente, AGR_INICIO.plusDays(1), EstadoPedido.ENTREGADO);
        var p3 = insertarPedidoDirecto(idCliente, AGR_INICIO.plusDays(2), EstadoPedido.ENTREGADO);
        insertarLinea(p1, idsProducto.get(0), "1.00", "5000.00");
        insertarLinea(p2, idsProducto.get(0), "1.00", "5000.00");
        insertarLinea(p3, idsProducto.get(0), "1.00", "5000.00");
        insertarLinea(p1, idsProducto.get(1), "120.00", "2.00");
        insertarLinea(p2, idsProducto.get(1), "80.00", "2.00");

        var ranking = repository.productosMasVendidos(AGR_INICIO, AGR_FIN);

        assertThat(ranking).extracting(ProductoVendidoFila::producto)
                .containsExactly("Producto B HU-16 test" + sufijo,
                        "Producto A HU-16 test" + sufijo);
        assertThat(ranking.get(0).unidadesVendidas()).isEqualByComparingTo("200.00");
        assertThat(ranking.get(0).importe()).isEqualByComparingTo("400.00");
        assertThat(ranking.get(0).pedidos()).isEqualTo(2);
        assertThat(ranking.get(1).unidadesVendidas()).isEqualByComparingTo("3.00");
        assertThat(ranking.get(1).importe()).isEqualByComparingTo("15000.00");
        assertThat(ranking.get(1).pedidos()).isEqualTo(3);
    }

    @Test
    @DisplayName("HU-25 CP-20 R-07: el ranking solo suma pedidos ENTREGADO, no los en curso ni "
            + "los cancelados")
    void rankingSoloCuentaEntregados() {
        var entregado = insertarPedidoDirecto(idCliente, AGR_INICIO, EstadoPedido.ENTREGADO);
        var pendiente = insertarPedidoDirecto(idCliente, AGR_INICIO, EstadoPedido.PENDIENTE);
        var enPreparacion = insertarPedidoDirecto(idCliente, AGR_INICIO,
                EstadoPedido.EN_PREPARACION);
        var cancelado = insertarPedidoDirecto(idCliente, AGR_INICIO, EstadoPedido.CANCELADO);
        insertarLinea(entregado, idsProducto.get(0), "4.00", "10.00");
        insertarLinea(pendiente, idsProducto.get(0), "100.00", "10.00");
        insertarLinea(enPreparacion, idsProducto.get(0), "200.00", "10.00");
        insertarLinea(cancelado, idsProducto.get(0), "300.00", "10.00");

        var ranking = repository.productosMasVendidos(AGR_INICIO, AGR_FIN);

        assertThat(ranking).hasSize(1);
        assertThat(ranking.get(0).producto()).isEqualTo("Producto A HU-16 test" + sufijo);
        assertThat(ranking.get(0).unidadesVendidas()).isEqualByComparingTo("4.00");
        assertThat(ranking.get(0).importe()).isEqualByComparingTo("40.00");
        assertThat(ranking.get(0).pedidos()).isEqualTo(1);
    }

    @Test
    @DisplayName("HU-25: el ranking excluye cancelados, cuenta pedidos distintos y desempata por "
            + "importe")
    void rankingExcluyeCanceladosYDesempataPorImporte() {
        var p1 = insertarPedidoDirecto(idCliente, AGR_INICIO, EstadoPedido.ENTREGADO);
        var cancelado = insertarPedidoDirecto(idCliente, AGR_INICIO, EstadoPedido.CANCELADO);
        // Misma cantidad de unidades; B tiene más importe, así que va primero.
        insertarLinea(p1, idsProducto.get(0), "10.00", "1.00");
        insertarLinea(p1, idsProducto.get(1), "10.00", "3.00");
        insertarLinea(cancelado, idsProducto.get(0), "500.00", "1.00");

        var ranking = repository.productosMasVendidos(AGR_INICIO, AGR_FIN);

        assertThat(ranking).extracting(ProductoVendidoFila::producto)
                .containsExactly("Producto B HU-16 test" + sufijo,
                        "Producto A HU-16 test" + sufijo);
        assertThat(ranking.get(1).unidadesVendidas()).isEqualByComparingTo("10.00");
        assertThat(ranking.get(1).pedidos()).isEqualTo(1);
    }

    @Test
    @DisplayName("HU-25: las agregaciones sin fechas no filtran por rango")
    void agregacionesSinFechasIncluyenElPedidoSembrado() {
        var e = insertarPedidoDirecto(idCliente, AGR_INICIO, EstadoPedido.ENTREGADO);
        insertarLinea(e, idsProducto.get(0), "1.00", "10.00");

        assertThat(repository.ventasPorCliente(null, null))
                .extracting(VentaClienteFila::idCliente).contains(idCliente);
        assertThat(repository.productosMasVendidos(null, null))
                .extracting(ProductoVendidoFila::producto)
                .contains("Producto A HU-16 test" + sufijo);
        assertThat(repository.resumenPorPeriodo(null, null)).isNotEmpty();
        assertThat(repository.resumenPorPeriodo(AGR_INICIO, null)).isNotEmpty();
        assertThat(repository.resumenPorPeriodo(null, AGR_FIN)).isNotEmpty();
    }

    /**
     * Crea un pedido sin fechaEntrega para los tests que usan {@link PedidoRepository#insertar},
     * que es el camino de la aplicación: cliente y estado, y la fecha la pone la base.
     *
     * @return el pedido a persistir
     */
    private Pedido pedidoNuevo() {
        var pedido = new Pedido();
        pedido.setIdCliente(idCliente);
        pedido.setEstado(EstadoPedido.PENDIENTE);
        return pedido;
    }

    /**
     * Inserta por el repositorio —el camino real de la aplicación— y registra el id para que la
     * limpieza borre el pedido antes que su cliente.
     *
     * @param pedido pedido a persistir
     * @return identificador asignado por la base
     */
    private Integer insertarConRepositorio(Pedido pedido) {
        var id = repository.insertar(pedido);
        idsPedido.add(id);
        return id;
    }

    /**
     * Siembra un pedido con {@code INSERT} directo para poder fijar su {@code fecha_pedido}, que
     * el repositorio nunca envía y la base rellenaría con {@code CURDATE()}.
     *
     * @param idCliente cliente del pedido
     * @param fecha     fecha de registro a sembrar
     * @param estado    estado a sembrar
     * @return identificador del pedido sembrado
     */
    private Integer insertarPedidoDirecto(Integer idCliente, LocalDate fecha,
                                          EstadoPedido estado) {
        var id = insertarConId("""
                INSERT INTO pedidos (id_cliente, fecha_pedido, estado)
                VALUES (:idCliente, :fecha, :estado)
                """, new MapSqlParameterSource()
                        .addValue("idCliente", idCliente)
                        .addValue("fecha", fecha)
                        .addValue("estado", estado.name()));
        idsPedido.add(id);
        return id;
    }

    private Integer insertarLinea(Integer idPedido, Integer idProducto, String cantidad,
                                  String precio) {
        return insertarConId("""
                INSERT INTO detalle_pedido (id_pedido, id_producto, cantidad, precio_unitario)
                VALUES (:idPedido, :idProducto, :cantidad, :precio)
                """, new MapSqlParameterSource()
                        .addValue("idPedido", idPedido)
                        .addValue("idProducto", idProducto)
                        .addValue("cantidad", new BigDecimal(cantidad))
                        .addValue("precio", new BigDecimal(precio)));
    }

    /**
     * Siembra un lote de producto terminado y lo registra para que la limpieza lo borre después
     * de su kardex y su fila de inventario.
     */
    private Integer insertarLote(Integer idProducto, String cantidad) {
        var id = insertarConId("""
                INSERT INTO lotes (codigo_lote, id_producto, fecha_produccion, fecha_vencimiento,
                                   cantidad)
                VALUES (:codigo, :idProducto, :produccion, :vencimiento, :cantidad)
                """, new MapSqlParameterSource()
                        .addValue("codigo", "LH16" + sufijo + "-"
                                + UUID.randomUUID().toString().substring(0, 8))
                        .addValue("idProducto", idProducto)
                        .addValue("produccion", LocalDate.now().minusDays(1))
                        .addValue("vencimiento", LocalDate.now().plusDays(30))
                        .addValue("cantidad", new BigDecimal(cantidad)));
        idsLote.add(id);
        return id;
    }

    /**
     * Siembra una salida del kardex de producto terminado ligada a una línea de pedido.
     */
    private void insertarMovimiento(Integer idLote, String tipo, String cantidad,
                                    String tablaOrigen, Integer idOrigen) {
        jdbc.update("""
                INSERT INTO movimientos_inventario_pt
                    (id_lote, tipo_movimiento, cantidad, saldo_anterior, saldo_posterior,
                     tabla_origen, id_origen)
                VALUES (:idLote, :tipo, :cantidad, :anterior, :posterior, :tablaOrigen,
                        :idOrigen)
                """, new MapSqlParameterSource()
                        .addValue("idLote", idLote)
                        .addValue("tipo", tipo)
                        .addValue("cantidad", new BigDecimal(cantidad))
                        .addValue("anterior", new BigDecimal("10.00"))
                        .addValue("posterior", new BigDecimal("7.00"))
                        .addValue("tablaOrigen", tablaOrigen)
                        .addValue("idOrigen", idOrigen));
    }

    private Integer insertarDevolucion(Integer idDetallePedido, String cantidad, String estado) {
        var id = insertarConId("""
                INSERT INTO devoluciones (id_detalle_pedido, motivo, cantidad_devuelta,
                                          fecha_devolucion, estado)
                VALUES (:linea, 'DEFECTO', :cantidad, :fecha, :estado)
                """, new MapSqlParameterSource()
                        .addValue("linea", idDetallePedido)
                        .addValue("cantidad", new BigDecimal(cantidad))
                        .addValue("fecha", INICIO)
                        .addValue("estado", estado));
        idsDevolucion.add(id);
        return id;
    }

    private Integer insertarClienteExtra(String nombre) {
        var id = insertarConId("""
                INSERT INTO clientes (nombre, estado) VALUES (:nombre, 1)
                """, new MapSqlParameterSource().addValue("nombre", nombre));
        idsClienteExtra.add(id);
        return id;
    }

    private Integer insertarProducto(String nombre) {
        return insertarConId("""
                INSERT INTO productos (nombre, precio, id_unidad, estado)
                VALUES (:nombre, 100.00, :idUnidad, 1)
                """, new MapSqlParameterSource()
                        .addValue("nombre", nombre)
                        .addValue("idUnidad", idUnidad));
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
}
