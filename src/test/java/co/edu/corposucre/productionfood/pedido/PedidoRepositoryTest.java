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
    @DisplayName("cambiarEstado actualiza el estado de la fila")
    void cambiarEstadoActualizaLaFila() {
        var id = insertarPedidoDirecto(idCliente, INICIO, EstadoPedido.PENDIENTE);

        repository.cambiarEstado(id, EstadoPedido.EN_PREPARACION);

        var leido = repository.buscarPorId(id).orElseThrow();
        assertThat(leido.getEstado()).isEqualTo(EstadoPedido.EN_PREPARACION);
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

    private void insertarLinea(Integer idPedido, Integer idProducto, String cantidad,
                               String precio) {
        jdbc.update("""
                INSERT INTO detalle_pedido (id_pedido, id_producto, cantidad, precio_unitario)
                VALUES (:idPedido, :idProducto, :cantidad, :precio)
                """, new MapSqlParameterSource()
                        .addValue("idPedido", idPedido)
                        .addValue("idProducto", idProducto)
                        .addValue("cantidad", new BigDecimal(cantidad))
                        .addValue("precio", new BigDecimal(precio)));
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
