package co.edu.corposucre.productionfood.usuario.dto;

public record UsuarioResponse(
    Integer idUsuario,
    String nombre,
    String correo,
    boolean activo,
    RolResponse rol
) {
    public record RolResponse(Integer idRol, String nombre) {}
}
