package co.edu.corposucre.productionfood.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Date;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;

class JwtServiceTest {

    private static final String SECRETO =
            "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
    private static final String SECRETO_OTRO =
            "ZmVkY2JhOTg3NjU0MzIxMGZlZGNiYTk4NzY1NDMyMTA=";

    private JwtService servicio;
    private JwtService ajeno;

    @BeforeEach
    void setUp() {
        servicio = new JwtService(SECRETO, 3_600_000L);
        ajeno = new JwtService(SECRETO_OTRO, 3_600_000L);
    }

    private UsuarioAutenticado usuario() {
        return new UsuarioAutenticado(
                7, "admin@productionfood.local", "Administrador Inicial", "ADMIN");
    }

    @Test
    @DisplayName("El token generado se valida con la misma clave")
    void generarTokenSeValidaConLaMismaClave() {
        var token = servicio.generar(usuario());

        var claims = servicio.validarYExtraer(token);

        assertThat(claims.getSubject()).isEqualTo("7");
    }

    @Test
    @DisplayName("El token conserva los claims de usuario y expira en una hora")
    void generarTokenConservaLosClaims() {
        var antes = System.currentTimeMillis();

        var claims = servicio.validarYExtraer(servicio.generar(usuario()));

        assertThat(claims.get("correo", String.class))
                .isEqualTo("admin@productionfood.local");
        assertThat(claims.get("nombre", String.class))
                .isEqualTo("Administrador Inicial");
        assertThat(claims.get("rol", String.class)).isEqualTo("ADMIN");
        assertThat(claims.getIssuedAt())
                .isAfterOrEqualTo(new Date((antes / 1000) * 1000));
        assertThat(claims.getExpiration().getTime())
                .isBetween(((antes / 1000) * 1000) + 3_600_000L, antes + 3_610_000L);
    }

    @Test
    @DisplayName("Un token firmado con otra clave se rechaza")
    void tokenConFirmaAjenaSeRechaza() {
        var token = ajeno.generar(usuario());

        assertThatThrownBy(() -> servicio.validarYExtraer(token))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("Un token vencido se rechaza con ExpiredJwtException")
    void tokenVencidoSeRechaza() {
        var vencido = new JwtService(SECRETO, -1_000L);

        var token = vencido.generar(usuario());

        assertThatThrownBy(() -> servicio.validarYExtraer(token))
                .isInstanceOf(ExpiredJwtException.class);
    }
}
