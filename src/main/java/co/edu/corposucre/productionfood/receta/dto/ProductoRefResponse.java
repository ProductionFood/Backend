package co.edu.corposucre.productionfood.receta.dto;

/**
 * Referencia embebida al producto de una receta (HU-12).
 */
public record ProductoRefResponse(
    Integer idProducto,
    String nombre) {}
