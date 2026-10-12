package co.edu.corposucre.productionfood.auth.dto;

/**
 * Respuesta de POST /api/v1/auth/login (HU-03 §4).
 *
 * Reutiliza {@link PerfilResponse} para el objeto `usuario` en lugar de declarar unos
 * campos propios: es exactamente el mismo dato que devuelve GET /api/v1/auth/perfil, y
 * un record duplicado podría divergir del perfil sin que ninguna prueba lo notara.
 *
 * `expiraEn` va en SEGUNDOS, que es la unidad que consume el frontend para refrescar
 * la sesión; el token se firma en milisegundos (`app.jwt.expiration-ms`).
 */
public record LoginResponse(
    String token,
    String tipo,
    long expiraEn,
    PerfilResponse usuario
) {}
