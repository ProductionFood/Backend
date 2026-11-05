package co.edu.corposucre.productionfood.inventario;

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
 * Pruebas de integración de {@link AlertaRepository} contra MySQL (HU-27).
 *
 * <p>Como las de inventario de producto terminado, usan la base del {@code docker-compose} y se
 * omiten con {@code assumeTrue} si no responde. La base es compartida y puede tener materias
 * primas reales en alerta, así que cada prueba siembra las suyas con un sufijo único y solo
 * afirma sobre las filas con ese sufijo; al terminar elimina únicamente lo que sembró.
 */
class AlertaRepositoryTest {

    private static final String URL = "jdbc:mysql://"
            + entorno("DB_HOST", "127.0.0.1") + ":" + entorno("DB_PORT", "3306")
            + "/" + entorno("DB_NAME", "productionfood")
            + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=America/Bogota";
    private static final String USUARIO = entorno("DB_USER", "root");
    private static final String CLAVE = entorno("DB_PASSWORD", "local");

    private NamedParameterJdbcTemplate jdbc;
    private AlertaRepository repository;

    private String sufijo;
    private Integer idUnidad;
    private final List<Integer> idsMateriaPrima = new ArrayList<>();
    private final List<Integer> idsProveedor = new ArrayList<>();
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
            sentencia.executeQuery("SELECT 1 FROM v_inventario_materia_prima LIMIT 1").close();
            disponible = true;
        } catch (SQLException e) {
            disponible = false;
        }
        assumeTrue(disponible,
                "MySQL no responde en " + URL + " con la vista de inventario; se omite.");
    }

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource(URL, USUARIO, CLAVE);
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        jdbc = new NamedParameterJdbcTemplate(dataSource);
        repository = new AlertaRepository(jdbc);

        sufijo = "HU27-" + UUID.randomUUID().toString().substring(0, 8);
        idUnidad = insertarConId("""
                INSERT INTO unidades_medida (nombre, abreviatura)
                VALUES (:nombre, :abreviatura)
                """, new MapSqlParameterSource()
                        .addValue("nombre", "Unidad " + sufijo)
                        .addValue("abreviatura", sufijo.substring(5)));
    }

    @AfterEach
    void tearDown() {
        if (!idsCompra.isEmpty()) {
            jdbc.update("DELETE FROM detalle_compra WHERE id_compra IN (:ids)",
                    new MapSqlParameterSource().addValue("ids", idsCompra));
            jdbc.update("DELETE FROM compras WHERE id_compra IN (:ids)",
                    new MapSqlParameterSource().addValue("ids", idsCompra));
        }
        if (!idsProveedor.isEmpty()) {
            jdbc.update("DELETE FROM proveedores WHERE id_proveedor IN (:ids)",
                    new MapSqlParameterSource().addValue("ids", idsProveedor));
        }
        if (!idsMateriaPrima.isEmpty()) {
            jdbc.update("DELETE FROM inventario WHERE id_materia_prima IN (:ids)",
                    new MapSqlParameterSource().addValue("ids", idsMateriaPrima));
            jdbc.update("DELETE FROM materias_primas WHERE id_materia_prima IN (:ids)",
                    new MapSqlParameterSource().addValue("ids", idsMateriaPrima));
        }
        if (idUnidad != null) {
            jdbc.update("DELETE FROM unidades_medida WHERE id_unidad = :id",
                    new MapSqlParameterSource().addValue("id", idUnidad));
        }
    }

    @Test
    @DisplayName("R-03: AGOTADO va antes que BAJO aunque su faltante sea menor")
    void agotadoVaAntesQueBajoAunqueFalteMenos() {
        var bajo = sembrar("bajo", "100.00", "40.00", true);
        var agotado = sembrar("agotado", "2.00", "0.00", true);

        assertThat(propias(false)).extracting(AlertaStockFila::idMateriaPrima)
                .containsExactly(agotado, bajo);
    }

    @Test
    @DisplayName("CA-04: dentro de cada nivel manda el mayor faltante")
    void dentroDelNivelOrdenaPorFaltanteDescendente() {
        var poco = sembrar("poco", "10.00", "8.00", true);
        var mucho = sembrar("mucho", "50.00", "5.00", true);
        var medio = sembrar("medio", "30.00", "10.00", true);
        var agotadoPequeno = sembrar("agotado-pequeno", "1.00", "0.00", true);
        var agotadoGrande = sembrar("agotado-grande", "9.00", "0.00", true);

        assertThat(propias(false)).extracting(AlertaStockFila::idMateriaPrima)
                .containsExactly(agotadoGrande, agotadoPequeno, mucho, medio, poco);
    }

    @Test
    @DisplayName("R-02: con disponible igual al mínimo no hay alerta")
    void disponibleIgualAlMinimoNoAlerta() {
        sembrar("justo", "20.00", "20.00", true);

        assertThat(propias(false)).isEmpty();
    }

    @Test
    @DisplayName("R-04: mínimo 0 con disponible positivo no alerta")
    void minimoCeroConExistenciasNoAlerta() {
        sembrar("bajo-pedido", "0.00", "3.00", true);

        assertThat(propias(false)).isEmpty();
    }

    @Test
    @DisplayName("R-04: mínimo 0 con disponible 0 sí aparece como AGOTADO")
    void minimoCeroSinExistenciasEsAgotado() {
        sembrar("bajo-pedido-agotado", "0.00", "0.00", true);

        assertThat(propias(false)).singleElement().satisfies(fila -> {
            assertThat(fila.nivel()).isEqualTo("AGOTADO");
            assertThat(fila.faltante()).isEqualByComparingTo("0.00");
        });
    }

    @Test
    @DisplayName("R-05: las inactivas se excluyen por defecto y entran con incluirInactivas")
    void inactivasSoloConLaBandera() {
        var activa = sembrar("activa", "10.00", "1.00", true);
        var inactiva = sembrar("inactiva", "10.00", "2.00", false);

        assertThat(propias(false)).extracting(AlertaStockFila::idMateriaPrima)
                .containsExactly(activa);
        assertThat(propias(true)).extracting(AlertaStockFila::idMateriaPrima)
                .containsExactlyInAnyOrder(activa, inactiva);
    }

    @Test
    @DisplayName("Una materia prima sin fila de inventario figura como AGOTADO")
    void sinFilaDeInventarioEsAgotado() {
        var id = sembrarSinInventario("sin-fila", "7.00");

        assertThat(propias(false)).singleElement().satisfies(fila -> {
            assertThat(fila.idMateriaPrima()).isEqualTo(id);
            assertThat(fila.nivel()).isEqualTo("AGOTADO");
            assertThat(fila.cantidadDisponible()).isEqualByComparingTo("0.00");
            assertThat(fila.faltante()).isEqualByComparingTo("7.00");
            assertThat(fila.ultimaActualizacion()).isNull();
        });
    }

    @Test
    @DisplayName("Mapea nombre, unidad, cantidades y costo de la vista")
    void mapeaLasColumnasDeLaVista() {
        var id = sembrar("completa", "20.00", "4.50", true);

        assertThat(propias(false)).singleElement().satisfies(fila -> {
            assertThat(fila.idMateriaPrima()).isEqualTo(id);
            assertThat(fila.nombre()).isEqualTo("MP completa " + sufijo);
            assertThat(fila.unidad()).isEqualTo(sufijo.substring(5));
            assertThat(fila.cantidadDisponible()).isEqualByComparingTo("4.50");
            assertThat(fila.stockMinimo()).isEqualByComparingTo("20.00");
            assertThat(fila.faltante()).isEqualByComparingTo("15.50");
            assertThat(fila.nivel()).isEqualTo("BAJO");
            assertThat(fila.costoUnitario()).isEqualByComparingTo("18500.00");
            assertThat(fila.ultimaActualizacion()).isNotNull();
        });
    }

    @Test
    @DisplayName("resumen cuenta AGOTADO y BAJO activos, sin las inactivas ni las que están bien")
    void resumenCuentaSoloActivasEnAlerta() {
        var antes = repository.resumen();
        sembrar("a1", "10.00", "0.00", true);
        sembrar("b1", "10.00", "3.00", true);
        sembrar("b2", "10.00", "9.99", true);
        sembrar("ok", "10.00", "10.00", true);
        sembrar("inactiva", "10.00", "0.00", false);

        var despues = repository.resumen();

        assertThat(despues.agotadas()).isEqualTo(antes.agotadas() + 1);
        assertThat(despues.bajas()).isEqualTo(antes.bajas() + 2);
        assertThat(despues.total()).isEqualTo(antes.total() + 3);
    }

    @Test
    @DisplayName("R-06: el proveedor es el de la última compra RECIBIDA por fecha")
    void proveedorDeLaUltimaCompraRecibida() {
        var mp = sembrar("harina", "50.00", "46.30", true);
        var viejo = sembrarProveedor("viejo");
        var reciente = sembrarProveedor("reciente");
        sembrarCompra(viejo, "RECIBIDA", "2026-01-10", mp);
        sembrarCompra(reciente, "RECIBIDA", "2026-03-05", mp);

        assertThat(sugerencias()).singleElement().satisfies(s -> {
            assertThat(s.idProveedor()).isEqualTo(reciente);
            assertThat(s.nombreProveedor()).isEqualTo("Proveedor reciente " + sufijo);
            assertThat(s.alerta().idMateriaPrima()).isEqualTo(mp);
        });
    }

    @Test
    @DisplayName("R-06: con la misma fecha desempata la compra de mayor id")
    void empateDeFechaLoDesempataElMayorId() {
        var mp = sembrar("harina", "50.00", "46.30", true);
        var primero = sembrarProveedor("primero");
        var segundo = sembrarProveedor("segundo");
        sembrarCompra(primero, "RECIBIDA", "2026-03-05", mp);
        sembrarCompra(segundo, "RECIBIDA", "2026-03-05", mp);

        assertThat(sugerencias()).singleElement()
                .extracting(SugerenciaFila::idProveedor).isEqualTo(segundo);
    }

    @Test
    @DisplayName("R-06: las compras PENDIENTE o CANCELADA no cuentan aunque sean más recientes")
    void comprasEnOtroEstadoSeIgnoran() {
        var mp = sembrar("harina", "50.00", "46.30", true);
        var recibida = sembrarProveedor("recibida");
        var pendiente = sembrarProveedor("pendiente");
        var cancelada = sembrarProveedor("cancelada");
        sembrarCompra(recibida, "RECIBIDA", "2026-01-10", mp);
        sembrarCompra(pendiente, "PENDIENTE", "2026-05-01", mp);
        sembrarCompra(cancelada, "CANCELADA", "2026-06-01", mp);

        assertThat(sugerencias()).singleElement()
                .extracting(SugerenciaFila::idProveedor).isEqualTo(recibida);
    }

    @Test
    @DisplayName("R-06: solo cuentan las compras que contienen esa materia prima")
    void soloComprasConLaMateriaPrima() {
        var mp = sembrar("harina", "50.00", "46.30", true);
        var otra = sembrar("azucar", "50.00", "50.00", true);
        var conHarina = sembrarProveedor("con-harina");
        var conAzucar = sembrarProveedor("con-azucar");
        sembrarCompra(conHarina, "RECIBIDA", "2026-01-10", mp);
        sembrarCompra(conAzucar, "RECIBIDA", "2026-06-01", otra);

        assertThat(sugerencias()).singleElement()
                .extracting(SugerenciaFila::idProveedor).isEqualTo(conHarina);
    }

    @Test
    @DisplayName("Sin compras recibidas la materia prima sale sin proveedor")
    void sinComprasNoTieneProveedor() {
        var mp = sembrar("nueva", "50.00", "10.00", true);
        var pendiente = sembrarProveedor("pendiente");
        sembrarCompra(pendiente, "PENDIENTE", "2026-05-01", mp);

        assertThat(sugerencias()).singleElement().satisfies(s -> {
            assertThat(s.alerta().idMateriaPrima()).isEqualTo(mp);
            assertThat(s.idProveedor()).isNull();
            assertThat(s.nombreProveedor()).isNull();
        });
    }

    @Test
    @DisplayName("La sugerencia excluye inactivas y mantiene el orden de urgencia de las alertas")
    void excluyeInactivasYOrdenaPorUrgencia() {
        var bajo = sembrar("bajo", "100.00", "40.00", true);
        var agotado = sembrar("agotado", "2.00", "0.00", true);
        sembrar("inactiva", "500.00", "0.00", false);

        assertThat(sugerencias()).extracting(s -> s.alerta().idMateriaPrima())
                .containsExactly(agotado, bajo);
    }

    private List<SugerenciaFila> sugerencias() {
        return repository.sugerenciaCompra().stream()
                .filter(s -> s.alerta().nombre().endsWith(sufijo))
                .toList();
    }

    private Integer sembrarProveedor(String etiqueta) {
        var id = insertarConId("INSERT INTO proveedores (nombre) VALUES (:nombre)",
                new MapSqlParameterSource().addValue("nombre", "Proveedor " + etiqueta + " " + sufijo));
        idsProveedor.add(id);
        return id;
    }

    private void sembrarCompra(Integer idProveedor, String estado, String fecha, Integer idMateriaPrima) {
        var idCompra = insertarConId("""
                INSERT INTO compras (id_proveedor, fecha, estado)
                VALUES (:proveedor, :fecha, :estado)
                """, new MapSqlParameterSource().addValue("proveedor", idProveedor)
                        .addValue("fecha", java.sql.Date.valueOf(fecha))
                        .addValue("estado", estado));
        idsCompra.add(idCompra);
        jdbc.update("""
                INSERT INTO detalle_compra (id_compra, id_materia_prima, cantidad, precio_unitario)
                VALUES (:compra, :mp, 10.00, 100.00)
                """, new MapSqlParameterSource().addValue("compra", idCompra)
                        .addValue("mp", idMateriaPrima));
    }

    private List<AlertaStockFila> propias(boolean incluirInactivas) {
        return repository.listarAlertas(incluirInactivas).stream()
                .filter(fila -> fila.nombre().endsWith(sufijo))
                .toList();
    }

    private Integer sembrar(String etiqueta, String minimo, String disponible, boolean activa) {
        var id = sembrarSinInventario(etiqueta, minimo, activa);
        jdbc.update("""
                INSERT INTO inventario (id_materia_prima, cantidad_disponible, fecha_actualizacion)
                VALUES (:id, :cantidad, NOW())
                """, new MapSqlParameterSource().addValue("id", id)
                        .addValue("cantidad", new BigDecimal(disponible)));
        return id;
    }

    private Integer sembrarSinInventario(String etiqueta, String minimo) {
        return sembrarSinInventario(etiqueta, minimo, true);
    }

    private Integer sembrarSinInventario(String etiqueta, String minimo, boolean activa) {
        var id = insertarConId("""
                INSERT INTO materias_primas (nombre, id_unidad, stock_minimo, costo_unitario, estado)
                VALUES (:nombre, :idUnidad, :minimo, 18500.00, :estado)
                """, new MapSqlParameterSource()
                        .addValue("nombre", "MP " + etiqueta + " " + sufijo)
                        .addValue("idUnidad", idUnidad)
                        .addValue("minimo", new BigDecimal(minimo))
                        .addValue("estado", activa ? 1 : 0));
        idsMateriaPrima.add(id);
        return id;
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
