package co.edu.corposucre.productionfood.auth.dto;

public record LoginResponse(
    String token,
    String tipo,
    long expiresIn,
    String correo,
    String nombre,
    String rol
) {}
