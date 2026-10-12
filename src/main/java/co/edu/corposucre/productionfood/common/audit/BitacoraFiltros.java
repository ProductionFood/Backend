package co.edu.corposucre.productionfood.common.audit;

import java.time.LocalDate;

/**
 * Filtros opcionales del listado de bitácora; cada campo nulo significa "sin filtrar" por él.
 */
public record BitacoraFiltros(
        Integer idUsuario,
        String tabla,
        String accion,
        LocalDate fechaInicio,
        LocalDate fechaFin
) {
}
