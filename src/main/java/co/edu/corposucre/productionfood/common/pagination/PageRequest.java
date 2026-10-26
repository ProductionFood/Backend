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

        // split con límite -1: sin él Java descarta las partes vacías finales y «fecha,»
        // llegaría como un campo solo, ocultando una dirección mal formada.
        var partes = (sort == null || sort.isBlank() ? porDefecto : sort).split(",", -1);
        var campo = partes[0].trim();
        var propiedad = camposOrden.get(campo);
        if (propiedad == null) {
            throw new ParametroInvalidoException("CAMPO_ORDEN_INVALIDO",
                "El campo de ordenamiento '" + campo + "' no es válido.");
        }
        if (partes.length > 2) {
            throw new ParametroInvalidoException("CAMPO_ORDEN_INVALIDO",
                "El parámetro 'sort' solo admite 'campo' o 'campo,asc|desc'.");
        }
        var ascendente = true;
        if (partes.length == 2) {
            var direccion = partes[1].trim();
            if (!"asc".equalsIgnoreCase(direccion) && !"desc".equalsIgnoreCase(direccion)) {
                throw new ParametroInvalidoException("CAMPO_ORDEN_INVALIDO",
                    "La dirección de ordenamiento '" + direccion
                        + "' no es válida; use 'asc' o 'desc'.");
            }
            ascendente = !"desc".equalsIgnoreCase(direccion);
        }
        return new PageRequest(page, size, propiedad, ascendente);
    }

    public Pageable toPageable() {
        return org.springframework.data.domain.PageRequest.of(page, size,
            Sort.by(ascendente ? Sort.Direction.ASC : Sort.Direction.DESC, propiedad));
    }
}
