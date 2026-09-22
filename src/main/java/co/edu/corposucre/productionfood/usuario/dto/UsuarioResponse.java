package co.edu.corposucre.productionfood.usuario.dto;

public record UsuarioResponse(
    Long idUsuario,
    String nombre,
    String correo,
    boolean activo,
    RolResponse rol
) {
    public record RolResponse(Long idRol, String nombre) {}
}
