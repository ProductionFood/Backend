package co.edu.corposucre.productionfood.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("ProductionFood API")
                        .description("API para el sistema de gestión de productora de alimentos")
                        .version("1.0.0"));
    }
}
