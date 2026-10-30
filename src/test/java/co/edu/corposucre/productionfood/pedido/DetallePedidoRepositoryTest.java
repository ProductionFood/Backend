package co.edu.corposucre.productionfood.pedido;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.GeneratedKeyHolder;

/**
 * Pruebas de integración de {@link DetallePedidoRepository} contra MySQL (HU-17).
 *
 * <p>El proyecto no usa Testcontainers ni H2: la única base soportada es la del
 * {@code docker-compose}. La prueba se conecta con los mismos parámetros que
 * {@code application.yml} (variables de entorno con los mismos valores por defecto) y se omite
 * con {@code assumeTrue} si la base o la tabla no están disponibles, para no fallar en un
 * entorno sin el contenedor levantado.
 *
 * <p>Cada prueba crea su propia unidad, sus productos, su cliente y sus pedidos, y los elimina al
 * terminar en orden inverso al de las llaves foráneas: el detalle antes que la cabecera, y los
 * productos antes que la unidad.
 */
class DetallePedidoRepositoryTest {

    private static final String URL = "jdbc:mysql://"
            + entorno("DB_HOST", "127.0.0.1") + ":" + entorno("DB_PORT", "3306")
            + "/" + entorno("DB_NAME", "productionfood")
            + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=America/Bogota";
    private static final String USUARIO = entorno("DB_USER", "root");
    private static final String CLAVE = entorno("DB_PASSWORD", "local");

    private NamedParameterJdbcTemplate jdbc;
    private DetallePedidoRepository repository;

    private String sufijo;
    private Integer idUnidad;
    private final List<Integer> idsProducto = new ArrayList<>();
    private Integer idCliente;
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
            sentencia.executeQuery("SELECT 1 FROM detalle_pedido LIMIT 1").close();
            disponible = true;
        } catch (SQLException e) {
            disponible = false;
        }
        assumeTrue(disponible,
                "MySQL no responde en " + URL + " con la tabla detalle_pedido; se omite.");
    }

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource(URL, USUARIO, CLAVE);
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        jdbc = new NamedParameterJdbcTemplate(dataSource);
        repository = new DetallePedidoRepository(jdbc);

        // Sufijo único por prueba: los nombres tienen índice en la base y una corrida anterior
        // que deje datos no debe confundir las lecturas por nombre.
        sufijo = "-" + UUID.randomUUID().toString().substring(0, 8);
        idUnidad = insertarConId("""
                INSERT INTO unidades_medida (nombre, abreviatura)
                VALUES (:nombre, :abreviatura)
                """, new MapSqlParameterSource()
                        .addValue("nombre", "Unidad HU-17 test" + sufijo)
                        .addValue("abreviatura", "u17" + sufijo));
        // Dos productos: uk_detalle_pedido es (id_pedido, id_producto), así que un pedido con
        // dos líneas necesita dos productos distintos.
        idsProducto.add(insertarProducto("Producto A HU-17 test" + sufijo));
        idsProducto.add(insertarProducto("Producto B HU-17 test" + sufijo));
        idCliente = insertarConId("""
                INSERT INTO clientes (nombre, estado) VALUES (:nombre, 1)
                """, new MapSqlParameterSource()
                        .addValue("nombre", "Cliente HU-17 test" + sufijo));
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
    @DisplayName("insertar devuelve el id y listarPorPedido trae nombre y unidad del JOIN en orden")
    void insertarDevuelveIdYListarTraeNombreYUnidadEnOrden() {
        var pedido = insertarPedido();
        var idA = repository.insertar(linea(pedido, idsProducto.get(0), "3.00", "1250.50"));
        var idB = repository.insertar(linea(pedido, idsProducto.get(1), "2.00", "800.00"));

        var filas = repository.listarPorPedido(pedido);

        assertThat(idA).isNotNull();
        assertThat(idB).isNotNull();
        assertThat(filas).extracting(DetallePedido::idDetallePedido)
                .containsExactly(idA, idB);
        assertThat(filas.get(0).nombreProducto()).isEqualTo("Producto A HU-17 test" + sufijo);
        assertThat(filas.get(0).unidad()).isEqualTo("u17" + sufijo);
        assertThat(filas.get(1).nombreProducto()).isEqualTo("Producto B HU-17 test" + sufijo);
        assertThat(filas.get(1).unidad()).isEqualTo("u17" + sufijo);
    }

    @Test
    @DisplayName("obtenerPorId y obtenerPorIdParaActualizar devuelven la línea")
    void obtenerPorIdYParaActualizarDevuelvenLaLinea() {
        var pedido = insertarPedido();
        var id = repository.insertar(linea(pedido, idsProducto.get(0), "3.00", "1250.50"));

        var leida = repository.obtenerPorId(id).orElseThrow();
        var bloqueada = repository.obtenerPorIdParaActualizar(id).orElseThrow();

        assertThat(leida.idDetallePedido()).isEqualTo(id);
        assertThat(leida.idPedido()).isEqualTo(pedido);
        assertThat(leida.idProducto()).isEqualTo(idsProducto.get(0));
        assertThat(leida.cantidad()).isEqualByComparingTo("3.00");
        assertThat(leida.precioUnitario()).isEqualByComparingTo("1250.50");
        assertThat(bloqueada).isEqualTo(leida);
    }

    @Test
    @DisplayName("actualizarCantidad cambia solo la cantidad y deja intacto el precio")
    void actualizarCantidadCambiaSoloLaCantidad() {
        var pedido = insertarPedido();
        var id = repository.insertar(linea(pedido, idsProducto.get(0), "3.00", "1250.50"));

        repository.actualizarCantidad(id, new BigDecimal("7.00"));

        var leida = repository.obtenerPorId(id).orElseThrow();
        assertThat(leida.cantidad()).isEqualByComparingTo("7.00");
        assertThat(leida.precioUnitario()).isEqualByComparingTo("1250.50");
    }

    @Test
    @DisplayName("eliminar borra la línea")
    void eliminarBorraLaLinea() {
        var pedido = insertarPedido();
        var id = repository.insertar(linea(pedido, idsProducto.get(0), "3.00", "1250.50"));

        repository.eliminar(id);

        assertThat(repository.obtenerPorId(id)).isEmpty();
        assertThat(repository.listarPorPedido(pedido)).isEmpty();
    }

    @Test
    @DisplayName("existeProducto distingue un producto presente de uno ausente")
    void existeProductoDistinguePresenteDeAusente() {
        var pedido = insertarPedido();
        repository.insertar(linea(pedido, idsProducto.get(0), "3.00", "1250.50"));

        assertThat(repository.existeProducto(pedido, idsProducto.get(0))).isTrue();
        assertThat(repository.existeProducto(pedido, idsProducto.get(1))).isFalse();
    }

    @Test
    @DisplayName("calcularTotal suma cantidad*precio y responde 0 para un pedido sin líneas")
    void calcularTotalSumaYDevuelveCeroSinLineas() {
        var conLineas = insertarPedido();
        repository.insertar(linea(conLineas, idsProducto.get(0), "3.00", "1250.50"));
        repository.insertar(linea(conLineas, idsProducto.get(1), "2.00", "800.00"));
        var sinLineas = insertarPedido();

        // 3 × 1250.50 + 2 × 800.00 = 5351.50.
        assertThat(repository.calcularTotal(conLineas)).isEqualByComparingTo("5351.50");
        assertThat(repository.calcularTotal(sinLineas)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("R-06: el UNIQUE (id_pedido, id_producto) impide repetir el producto")
    void elUniqueImpideRepetirElProducto() {
        var pedido = insertarPedido();
        repository.insertar(linea(pedido, idsProducto.get(0), "3.00", "1250.50"));

        assertThat(repository.existeProducto(pedido, idsProducto.get(0))).isTrue();
        assertThatThrownBy(() ->
                repository.insertar(linea(pedido, idsProducto.get(0), "1.00", "1250.50")))
                .isInstanceOf(DataAccessException.class);
    }

    /**
     * Crea una línea sin id para las pruebas; el precio viaja explícito, como en el servicio.
     */
    private DetallePedido linea(Integer idPedido, Integer idProducto, String cantidad,
                                String precio) {
        return new DetallePedido(null, idPedido, idProducto,
                new BigDecimal(cantidad), new BigDecimal(precio));
    }

    /**
     * Siembra un pedido de prueba y lo registra para que la limpieza lo borre junto con su
     * detalle.
     *
     * @return identificador del pedido sembrado
     */
    private Integer insertarPedido() {
        var id = insertarConId("""
                INSERT INTO pedidos (id_cliente, estado) VALUES (:idCliente, 'PENDIENTE')
                """, new MapSqlParameterSource().addValue("idCliente", idCliente));
        idsPedido.add(id);
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
