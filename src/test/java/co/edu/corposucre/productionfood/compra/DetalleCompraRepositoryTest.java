package co.edu.corposucre.productionfood.compra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
import java.sql.DriverManager;
import java.sql.SQLException;
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
 * Pruebas de integración de {@link DetalleCompraRepository} contra MySQL.
 *
 * <p>El proyecto no usa Testcontainers ni H2: la única base soportada es la del
 * {@code docker-compose}. La prueba se conecta con los mismos parámetros que
 * {@code application.yml} (variables de entorno con los mismos valores por defecto) y se omite
 * con {@code assumeTrue} si la base o la tabla no están disponibles, para no fallar en un
 * entorno sin el contenedor levantado.
 *
 * <p>Cada prueba crea su propia unidad, materias primas, proveedor y compra, y las elimina al
 * terminar: no depende de los datos semilla ni deja rastro en la base.
 */
class DetalleCompraRepositoryTest {

    private static final String URL = "jdbc:mysql://"
            + entorno("DB_HOST", "127.0.0.1") + ":" + entorno("DB_PORT", "3306")
            + "/" + entorno("DB_NAME", "productionfood")
            + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=America/Bogota";
    private static final String USUARIO = entorno("DB_USER", "root");
    private static final String CLAVE = entorno("DB_PASSWORD", "local");

    private NamedParameterJdbcTemplate jdbc;
    private DetalleCompraRepository repository;

    private String sufijo;
    private Integer idUnidad;
    private final List<Integer> idsMateriaPrima = new ArrayList<>();
    private Integer idProveedor;
    private final List<Integer> idsCompra = new ArrayList<>();

    private static String entorno(String nombre, String defecto) {
        String valor = System.getenv(nombre);
        return valor == null || valor.isBlank() ? defecto : valor;
    }

    @BeforeAll
    static void laBaseDelProyectoEstaDisponible() {
        boolean disponible;
        try (var conexion = java.sql.DriverManager.getConnection(URL, USUARIO, CLAVE);
             var sentencia = conexion.createStatement()) {
            sentencia.executeQuery("SELECT 1 FROM detalle_compra LIMIT 1").close();
            disponible = true;
        } catch (SQLException e) {
            disponible = false;
        }
        assumeTrue(disponible,
                "MySQL no responde en " + URL + " con la tabla detalle_compra; se omite.");
    }

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource(URL, USUARIO, CLAVE);
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        jdbc = new NamedParameterJdbcTemplate(dataSource);
        repository = new DetalleCompraRepository(jdbc);

        // Sufijo único por prueba: abreviatura y nombres tienen UNIQUE en la base y una
        // corrida anterior que deje datos no debe hacer colisionar esta.
        sufijo = "-" + UUID.randomUUID().toString().substring(0, 8);
        idUnidad = insertarConId("""
                INSERT INTO unidades_medida (nombre, abreviatura)
                VALUES (:nombre, :abreviatura)
                """, new MapSqlParameterSource()
                        .addValue("nombre", "Unidad HU-14 test" + sufijo)
                        .addValue("abreviatura", "u14" + sufijo));
        idsMateriaPrima.add(insertarMateriaPrima("Harina HU-14 test" + sufijo));
        idsMateriaPrima.add(insertarMateriaPrima("Azúcar HU-14 test" + sufijo));
        idProveedor = insertarConId("""
                INSERT INTO proveedores (nombre, estado) VALUES (:nombre, 1)
                """, new MapSqlParameterSource()
                        .addValue("nombre", "Proveedor HU-14 test" + sufijo));
        crearCompra();
    }

    @AfterEach
    void tearDown() {
        for (Integer idCompra : idsCompra) {
            jdbc.update("DELETE FROM detalle_compra WHERE id_compra = :id",
                    new MapSqlParameterSource().addValue("id", idCompra));
            jdbc.update("DELETE FROM compras WHERE id_compra = :id",
                    new MapSqlParameterSource().addValue("id", idCompra));
        }
        if (!idsMateriaPrima.isEmpty()) {
            jdbc.update("DELETE FROM materias_primas WHERE id_materia_prima IN (:ids)",
                    new MapSqlParameterSource().addValue("ids", idsMateriaPrima));
        }
        if (idProveedor != null) {
            jdbc.update("DELETE FROM proveedores WHERE id_proveedor = :id",
                    new MapSqlParameterSource().addValue("id", idProveedor));
        }
        if (idUnidad != null) {
            jdbc.update("DELETE FROM unidades_medida WHERE id_unidad = :id",
                    new MapSqlParameterSource().addValue("id", idUnidad));
        }
    }

    @Test
    @DisplayName("insertar devuelve el id y listarPorCompra trae la línea con nombre y unidad del JOIN")
    void insertarYListarPorCompra() {
        var id = repository.insertar(linea(idCompraDePrueba(), 0, "100.00", "3200.00"));

        assertThat(id).isNotNull().isPositive();

        var lineas = repository.listarPorCompra(idCompraDePrueba());
        assertThat(lineas).hasSize(1);
        var linea = lineas.get(0);
        assertThat(linea.idDetalle()).isEqualTo(id);
        assertThat(linea.idCompra()).isEqualTo(idCompraDePrueba());
        assertThat(linea.idMateriaPrima()).isEqualTo(idsMateriaPrima.get(0));
        assertThat(linea.cantidad()).isEqualByComparingTo("100.00");
        assertThat(linea.cantidad().scale()).isEqualTo(2);
        assertThat(linea.precioUnitario()).isEqualByComparingTo("3200.00");
        assertThat(linea.precioUnitario().scale()).isEqualTo(2);
        assertThat(linea.nombreMateriaPrima()).isEqualTo("Harina HU-14 test" + sufijo);
        assertThat(linea.abreviaturaUnidad()).isEqualTo("u14" + sufijo);
        assertThat(linea.subtotal()).isEqualByComparingTo("320000.00");
    }

    @Test
    @DisplayName("listarPorCompra respeta el orden de inserción y no cruza otras compras")
    void listarPorCompraRespetaElOrdenYNoCruzaCompras() {
        repository.insertar(linea(idCompraDePrueba(), 0, "10.00", "3200.00"));
        repository.insertar(linea(idCompraDePrueba(), 1, "5.00", "1500.00"));
        var otraCompra = crearCompra();
        repository.insertar(linea(otraCompra, 0, "1.00", "1.00"));

        var lineas = repository.listarPorCompra(idCompraDePrueba());

        assertThat(lineas).extracting(DetalleCompra::idMateriaPrima)
                .containsExactly(idsMateriaPrima.get(0), idsMateriaPrima.get(1));
        assertThat(lineas).extracting(DetalleCompra::idCompra).containsOnly(idCompraDePrueba());
    }

    @Test
    @DisplayName("actualizar cambia cantidad y precio sin tocar compra ni materia prima")
    void actualizarCambiaCantidadYPrecio() {
        var id = repository.insertar(linea(idCompraDePrueba(), 0, "100.00", "3200.00"));

        repository.actualizar(new DetalleCompra(id, idCompraDePrueba(), idsMateriaPrima.get(0),
                new BigDecimal("50.00"), new BigDecimal("3000.00")));

        var linea = repository.obtenerPorId(id).orElseThrow();
        assertThat(linea.cantidad()).isEqualByComparingTo("50.00");
        assertThat(linea.precioUnitario()).isEqualByComparingTo("3000.00");
        assertThat(linea.idCompra()).isEqualTo(idCompraDePrueba());
        assertThat(linea.idMateriaPrima()).isEqualTo(idsMateriaPrima.get(0));
        assertThat(repository.listarPorCompra(idCompraDePrueba())).hasSize(1);
    }

    @Test
    @DisplayName("eliminar quita la línea y obtenerPorId deja de encontrarla")
    void eliminarQuitaLaLinea() {
        var id = repository.insertar(linea(idCompraDePrueba(), 0, "100.00", "3200.00"));

        repository.eliminar(id);

        assertThat(repository.listarPorCompra(idCompraDePrueba())).isEmpty();
        assertThat(repository.obtenerPorId(id)).isEmpty();
    }

    @Test
    @DisplayName("existeMateriaPrimaEnCompra distingue la materia prima presente de la ausente")
    void existeMateriaPrimaEnCompraDistinguePresenteYAusente() {
        repository.insertar(linea(idCompraDePrueba(), 0, "100.00", "3200.00"));

        assertThat(repository.existeMateriaPrimaEnCompra(idCompraDePrueba(),
                idsMateriaPrima.get(0))).isTrue();
        assertThat(repository.existeMateriaPrimaEnCompra(idCompraDePrueba(),
                idsMateriaPrima.get(1))).isFalse();
    }

    @Test
    @DisplayName("calcularTotal devuelve el producto exacto con la escala del motor: 33.33 × 3333.33")
    void calcularTotalConDecimalesExactos() {
        repository.insertar(linea(idCompraDePrueba(), 0, "33.33", "3333.33"));

        var total = repository.calcularTotal(idCompraDePrueba());

        // MySQL multiplica dos decimal(10,2) con aritmética exacta y devuelve escala 4.
        assertThat(total).isEqualByComparingTo("111099.8889");
        assertThat(total.scale()).isEqualTo(4);
    }

    @Test
    @DisplayName("calcularTotal suma todas las líneas de la compra")
    void calcularTotalSumaLasLineas() {
        repository.insertar(linea(idCompraDePrueba(), 0, "100.00", "3200.00"));
        repository.insertar(linea(idCompraDePrueba(), 1, "50.00", "3200.00"));

        assertThat(repository.calcularTotal(idCompraDePrueba())).isEqualByComparingTo("480000.00");
    }

    @Test
    @DisplayName("calcularTotal de una compra sin líneas es cero")
    void calcularTotalSinLineasEsCero() {
        assertThat(repository.calcularTotal(idCompraDePrueba())).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("obtenerPorId devuelve vacío para un id inexistente")
    void obtenerPorIdInexistenteDevuelveVacio() {
        assertThat(repository.obtenerPorId(0)).isEmpty();
    }

    private Integer idCompraDePrueba() {
        return idsCompra.get(0);
    }

    private DetalleCompra linea(Integer idCompra, int indiceMateriaPrima, String cantidad,
                                String precio) {
        return new DetalleCompra(null, idCompra, idsMateriaPrima.get(indiceMateriaPrima),
                new BigDecimal(cantidad), new BigDecimal(precio));
    }

    private Integer crearCompra() {
        var id = insertarConId("""
                INSERT INTO compras (id_proveedor, fecha, estado)
                VALUES (:idProveedor, CURDATE(), 'PENDIENTE')
                """, new MapSqlParameterSource().addValue("idProveedor", idProveedor));
        idsCompra.add(id);
        return id;
    }

    private Integer insertarMateriaPrima(String nombre) {
        return insertarConId("""
                INSERT INTO materias_primas (nombre, id_unidad, stock_minimo, costo_unitario, estado)
                VALUES (:nombre, :idUnidad, 10.00, 3200.00, 1)
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
