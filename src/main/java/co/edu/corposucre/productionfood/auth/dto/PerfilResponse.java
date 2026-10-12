package co.edu.corposucre.productionfood.auth.dto;

/**
 * Datos del usuario autenticado que devuelve GET /api/v1/auth/perfil.
 *
 * No lleva la contraseña ni su hash por ningún motivo: el frontend solo necesita
 * mostrar nombre, correo y rol, y el hash nunca debe cruzar el límite del servidor
 * (CP-14). El `rol` viaja como texto plano y no como el objeto `Rol` anidado,
 * porque este endpoint no necesita el id ni la descripción del rol.
 *
 * Los datos salen del principal `UsuarioAutenticado` que `JwtAuthenticationFilter`
 * deja en el SecurityContext, no de una consulta a la base de datos.
 */
public record PerfilResponse(
    Integer idUsuario,
    String nombre,
    String correo,
    String rol
) {}
