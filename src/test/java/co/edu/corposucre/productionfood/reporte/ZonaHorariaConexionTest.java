package co.edu.corposucre.productionfood.reporte;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

/**
 * R-01 (HU-24): el «hoy» del dashboard sale de {@code curdate()} de MySQL, que depende de la zona
 * de la sesión. Si una sola conexión del pool arranca con otra zona, las tarjetas cambian de día
 * a las 7 p.m. de Colombia de forma intermitente; por eso todas las conexiones fijan -05:00.
 */
class ZonaHorariaConexionTest {

    @Test
    @DisplayName("Todas las conexiones del pool fijan la zona -05:00 al abrirse")
    void elPoolFijaLaZonaDeColombiaEnCadaConexion() {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));

        var sql = yaml.getObject().getProperty("spring.datasource.hikari.connection-init-sql");

        assertThat(sql).isEqualTo("SET time_zone = '-05:00'");
    }
}
