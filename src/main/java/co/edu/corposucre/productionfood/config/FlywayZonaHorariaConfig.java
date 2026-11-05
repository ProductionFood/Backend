package co.edu.corposucre.productionfood.config;

import javax.sql.DataSource;

import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.zaxxer.hikari.HikariDataSource;

/**
 * Evita que las migraciones dejen el pool en UTC.
 *
 * <p>La cabecera de {@code V1__esquema_base.sql} (volcado de phpMyAdmin) ejecuta
 * {@code SET time_zone = "+00:00"} en la sesión que migra. Esa conexión vuelve al pool de Hikari
 * con UTC y, mientras no se recicle, los {@code DEFAULT CURRENT_TIMESTAMP} de {@code bitacora},
 * {@code consumo_materia_prima} e {@code inventario} se guardan en UTC aunque el resto del pool
 * trabaje en -05:00 ({@code connection-init-sql}). V1 no se edita: cambiaría su checksum en las
 * bases ya migradas. En su lugar, tras migrar se expulsan las conexiones del pool para que las
 * nuevas pasen por {@code connection-init-sql}.
 */
@Configuration
public class FlywayZonaHorariaConfig {

    @Bean
    FlywayMigrationStrategy migrarYRenovarPool(DataSource dataSource) {
        return flyway -> {
            flyway.migrate();
            renovarConexiones(dataSource);
        };
    }

    /** Descarta las conexiones existentes (las ocupadas, al devolverse) para que se recreen. */
    static void renovarConexiones(DataSource dataSource) {
        try {
            if (dataSource.isWrapperFor(HikariDataSource.class)) {
                var pool = dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean();
                if (pool != null) {
                    pool.softEvictConnections();
                }
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("No se pudo renovar el pool tras migrar", e);
        }
    }
}
