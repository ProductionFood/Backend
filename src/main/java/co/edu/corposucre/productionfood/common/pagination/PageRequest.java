package co.edu.corposucre.productionfood.common.pagination;

import java.util.Map;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import co.edu.corposucre.productionfood.common.error.ConflictoNegocioException;

public record PageRequest(int page, int size, String propiedad, boolean ascendente) {

    public static PageRequest of(int page, int size, String sort,
                                 Map<String, String> camposOrden, String porDefecto) {
        if (page < 0) page = 0;
        if (size < 1) size = 1;
        if (size > 100) size = 100;

        var partes = sort != null ? sort.split(",") : new String[]{porDefecto};
        var campo = partes[0].trim();
        var propiedad = camposOrden.get(campo);
        if (propiedad == null) {
            throw new ConflictoNegocioException("CAMPO_ORDEN_INVALIDO",
                "El campo de ordenamiento '" + campo + "' no es válido.");
        }
        var ascendente = partes.length > 1 && "asc".equalsIgnoreCase(partes[1].trim());
        return new PageRequest(page, size, propiedad, ascendente);
    }

    public Pageable toPageable() {
        return org.springframework.data.domain.PageRequest.of(page, size,
            Sort.by(ascendente ? Sort.Direction.ASC : Sort.Direction.DESC, propiedad));
    }
}
