package co.edu.corposucre.productionfood.common.pagination;

import java.util.Map;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import co.edu.corposucre.productionfood.common.error.ParametroInvalidoException;

public record PageRequest(int page, int size, String propiedad, boolean ascendente) {

    public static PageRequest of(int page, int size, String sort,
                                 Map<String, String> camposOrden, String porDefecto) {
        if (page < 0) {
            throw new ParametroInvalidoException("PARAMETRO_INVALIDO",
                "El parámetro 'page' no puede ser negativo.");
        }
        if (size < 1 || size > 100) {
            throw new ParametroInvalidoException("PARAMETRO_INVALIDO",
                "El parámetro 'size' debe estar entre 1 y 100.");
        }

        var partes = (sort == null || sort.isBlank() ? porDefecto : sort).split(",");
        var campo = partes[0].trim();
        var propiedad = camposOrden.get(campo);
        if (propiedad == null) {
            throw new ParametroInvalidoException("CAMPO_ORDEN_INVALIDO",
                "El campo de ordenamiento '" + campo + "' no es válido.");
        }
        var ascendente = partes.length < 2
                || !"desc".equalsIgnoreCase(partes[1].trim());
        return new PageRequest(page, size, propiedad, ascendente);
    }

    public Pageable toPageable() {
        return org.springframework.data.domain.PageRequest.of(page, size,
            Sort.by(ascendente ? Sort.Direction.ASC : Sort.Direction.DESC, propiedad));
    }
}
