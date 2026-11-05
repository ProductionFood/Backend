package co.edu.corposucre.productionfood.receta;

import static org.assertj.core.api.Assertions.assertThat;
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

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.GeneratedKeyHolder;

/**
 * Pruebas de integración de {@link RecetaRepository#buscarPorProductos(List)} contra MySQL
 * (HU-21).
 *
 * <p>El proyecto no usa Testcontainers ni H2: la única base soportada es la del
 * {@code docker-compose}. La prueba se conecta con los mismos parámetros que
 * {@code application.yml} (variables de entorno con los mismos valores por defecto) y se omite
 * con {@code assumeTrue} si la base o la tabla no están disponibles, para no fallar en un
 * entorno sin el contenedor levantado.
 *
 * <p>Cada prueba crea su propia unidad, sus productos y sus recetas, y los elimina al terminar en
 * orden inverso al de las llaves foráneas: las recetas antes que los productos y los productos
 * antes que la unidad.
 */
class RecetaRepositoryBusquedaProductosTest {

    private static final String URL = "jdbc:mysql://"
            + entorno("DB_HOST", "127.0.0.1") + ":" + entorno("DB_PORT", "3306")
            + "/" + entorno("DB_NAME", "productionfood")
            + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=America/Bogota";
    private static final String USUARIO = entorno("DB_USER", "root");
    private static final String CLAVE = entorno("DB_PASSWORD", "local");

    private NamedParameterJdbcTemplate jdbc;
    private RecetaRepository repository;

    private String sufijo;
    private Integer idUnidad;
    private final List<Integer> idsProducto = new ArrayList<>();
    private final List<Integer> idsReceta = new ArrayList<>();

    private static String entorno(String nombre, String defecto) {
        String valor = System.getenv(nombre);
        return valor == null || valor.isBlank() ? defecto : valor;
    }

    @BeforeAll
    static void laBaseDelProyectoEstaDisponible() {
        boolean disponible;
        try (var conexion = java.sql.DriverManager.getConnection(URL, USUARIO, CLAVE);
             var sentencia = conexion.createStatement()) {
            sentencia.executeQuery("SELECT 1 FROM recetas LIMIT 1").close();
            disponible = true;
        } catch (SQLException e) {
            disponible = false;
        }
        assumeTrue(disponible,
                "MySQL no responde en " + URL + " con la tabla recetas; se omite.");
    }

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource(URL, USUARIO, CLAVE);
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        jdbc = new NamedParameterJdbcTemplate(dataSource);
        repository = new RecetaRepository(jdbc);

        // Sufijo único por prueba: los nombres y abreviaturas tienen índice en la base y una
        // corrida anterior que deje datos no debe confundir las lecturas del JOIN.
        sufijo = "-" + UUID.randomUUID().toString().substring(0, 8);
        idUnidad = insertarConId("""
                INSERT INTO unidades_medida (nombre, abreviatura)
                VALUES (:nombre, :abreviatura)
                """, new MapSqlParameterSource()
                        .addValue("nombre", "Unidad HU-21 test" + sufijo)
                        .addValue("abreviatura", "u21" + sufijo));
        // Cuatro productos: los tres primeros tienen receta y el último no. Así se comprueba que
        // el IN filtra por los pedidos y que un producto sin receta no aparece.
        idsProducto.add(insertarProducto("Producto A HU-21 test" + sufijo));
        idsProducto.add(insertarProducto("Producto B HU-21 test" + sufijo));
        idsProducto.add(insertarProducto("Producto C HU-21 test" + sufijo));
        idsProducto.add(insertarProducto("Producto D HU-21 test" + sufijo));
        idsReceta.add(insertarReceta(idsProducto.get(0), "10.00"));
        idsReceta.add(insertarReceta(idsProducto.get(1), "25.50"));
        idsReceta.add(insertarReceta(idsProducto.get(2), "7.00"));
    }

    @AfterEach
    void tearDown() {
        // La receta antes que el producto y la unidad: recetas → productos, unidades_medida.
        if (!idsReceta.isEmpty()) {
            jdbc.update("DELETE FROM recetas WHERE id_receta IN (:ids)",
                    new MapSqlParameterSource().addValue("ids", idsReceta));
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
    @DisplayName("devuelve solo las recetas pedidas con nombre, rendimiento y unidad del JOIN")
    void devuelveSoloLasRecetasPedidasConDatosDelJoin() {
        var recetas = repository.buscarPorProductos(
                List.of(idsProducto.get(0), idsProducto.get(1)));

        assertThat(recetas).hasSize(2);
        assertThat(recetas).extracting(Receta::getIdReceta)
                .containsExactly(idsReceta.get(0), idsReceta.get(1));
        assertThat(recetas).extracting(Receta::getIdProducto)
                .containsExactly(idsProducto.get(0), idsProducto.get(1));
        assertThat(recetas).extracting(Receta::getNombreProducto)
                .containsExactly("Producto A HU-21 test" + sufijo,
                        "Producto B HU-21 test" + sufijo);
        assertThat(recetas).extracting(Receta::getCantidadProducir)
                .containsExactly(new BigDecimal("10.00"), new BigDecimal("25.50"));
        assertThat(recetas).extracting(Receta::getAbreviaturaUnidad)
                .containsOnly("u21" + sufijo);
        assertThat(recetas).extracting(Receta::getNombreUnidad)
                .containsOnly("Unidad HU-21 test" + sufijo);
    }

    @Test
    @DisplayName("una lista vacía o null devuelve una lista vacía")
    void listaVaciaONullDevuelveListaVacia() {
        assertThat(repository.buscarPorProductos(List.of())).isEmpty();
        assertThat(repository.buscarPorProductos(null)).isEmpty();
    }

    @Test
    @DisplayName("el producto sin receta no aparece y los no pedidos tampoco")
    void productoSinRecetaNoAparece() {
        // B tiene receta y D no; C tiene receta pero no se pide.
        var recetas = repository.buscarPorProductos(
                List.of(idsProducto.get(1), idsProducto.get(3)));

        assertThat(recetas).singleElement().satisfies(receta -> {
            assertThat(receta.getIdProducto()).isEqualTo(idsProducto.get(1));
            assertThat(receta.getNombreProducto()).isEqualTo("Producto B HU-21 test" + sufijo);
        });
        assertThat(recetas).extracting(Receta::getIdProducto)
                .doesNotContain(idsProducto.get(2), idsProducto.get(3));
        assertThat(repository.buscarPorProductos(List.of(idsProducto.get(3)))).isEmpty();
    }

    private Integer insertarProducto(String nombre) {
        return insertarConId("""
                INSERT INTO productos (nombre, precio, id_unidad, estado)
                VALUES (:nombre, 100.00, :idUnidad, 1)
                """, new MapSqlParameterSource()
                        .addValue("nombre", nombre)
                        .addValue("idUnidad", idUnidad));
    }

    private Integer insertarReceta(Integer idProducto, String cantidadProducir) {
        return insertarConId("""
                INSERT INTO recetas (id_producto, nombre, cantidad_producir, id_unidad, estado)
                VALUES (:idProducto, :nombre, :cantidadProducir, :idUnidad, 1)
                """, new MapSqlParameterSource()
                        .addValue("idProducto", idProducto)
                        .addValue("nombre", "Receta HU-21 test" + sufijo)
                        .addValue("cantidadProducir", new BigDecimal(cantidadProducir))
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
