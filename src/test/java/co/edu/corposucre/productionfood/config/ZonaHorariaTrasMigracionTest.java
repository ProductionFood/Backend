package co.edu.corposucre.productionfood.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * La cabecera de V1 fija {@code time_zone = +00:00} en la sesion que migra. Con el pool real (la
 * conexion de Flyway vuelve al pool y luego atiende las peticiones) los {@code DEFAULT
 * CURRENT_TIMESTAMP} se guardaban en UTC. Esta prueba migra un esquema vacio y comprueba que la
 * sesion queda en -05:00. Se omite si MySQL no responde.
 */
class ZonaHorariaTrasMigracionTest {

    private static final String HOST = entorno("DB_HOST", "127.0.0.1") + ":"
            + entorno("DB_PORT", "3306");
    private static final String PARAMS = "?useSSL=false&allowPublicKeyRetrieval=true"
            + "&serverTimezone=America/Bogota";
    private static final String USUARIO = entorno("DB_USER", "root");
    private static final String CLAVE = entorno("DB_PASSWORD", "local");

    private static String entorno(String nombre, String defecto) {
        var valor = System.getenv(nombre);
        return valor == null || valor.isBlank() ? defecto : valor;
    }

    @Test
    @DisplayName("Tras migrar un esquema vacio la conexion del pool queda en -05:00, no en UTC")
    void laConexionDeFlywayVuelveAlPoolEnHoraDeColombia() throws Exception {
        var esquema = "pf_tz_test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        try (var admin = DriverManager.getConnection("jdbc:mysql://" + HOST + "/" + PARAMS,
                USUARIO, CLAVE)) {
            admin.createStatement().execute("CREATE DATABASE `" + esquema + "`");
        } catch (SQLException e) {
            assumeTrue(false, "MySQL no disponible: " + e.getMessage());
        }
        try (var admin = DriverManager.getConnection("jdbc:mysql://" + HOST + "/" + PARAMS,
                USUARIO, CLAVE)) {
            var config = new HikariConfig();
            config.setJdbcUrl("jdbc:mysql://" + HOST + "/" + esquema + PARAMS);
            config.setUsername(USUARIO);
            config.setPassword(CLAVE);
            config.setMaximumPoolSize(2);
            config.setMinimumIdle(1);
            config.setConnectionInitSql("SET time_zone = '-05:00'");
            try (var pool = new HikariDataSource(config)) {
                var flyway = Flyway.configure().dataSource(pool)
                        .locations("classpath:db/migration").load();
                new FlywayZonaHorariaConfig().migrarYRenovarPool(pool).migrate(flyway);

                // Las dos conexiones del pool (la que migro y la otra) deben quedar en -05:00.
                try (var uno = pool.getConnection(); var dos = pool.getConnection()) {
                    for (var conexion : new java.sql.Connection[] {uno, dos}) {
                        try (var rs = conexion.createStatement()
                                .executeQuery("SELECT @@session.time_zone")) {
                            rs.next();
                            assertThat(rs.getString(1)).isEqualTo("-05:00");
                        }
                    }
                }
            }
        } finally {
            try (var admin = DriverManager.getConnection("jdbc:mysql://" + HOST + "/" + PARAMS,
                    USUARIO, CLAVE)) {
                admin.createStatement().execute("DROP DATABASE IF EXISTS `" + esquema + "`");
            }
        }
    }
}
