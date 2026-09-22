package co.edu.corposucre.productionfood.common.pagination;

import java.util.Set;

import co.edu.corposucre.productionfood.common.error.ConflictoNegocioException;

public record PageRequest(int page, int size, String campo, boolean ascendente) {

    private static final Set<String> CAMPOS_PERMITIDOS = Set.of("nombre", "correo", "id");

    public PageRequest {
        if (page < 0) page = 0;
        if (size < 1) size = 1;
        if (size > 100) size = 100;

        var partes = campo != null ? campo.split(",") : new String[]{"nombre,asc"};
        campo = partes[0].trim();
        if (!CAMPOS_PERMITIDOS.contains(campo)) {
            throw new ConflictoNegocioException("CAMPO_ORDEN_INVALIDO",
                "El campo de ordenamiento '" + campo + "' no es válido.");
        }
        ascendente = partes.length > 1 && "asc".equalsIgnoreCase(partes[1].trim());
    }

    public int offset() {
        return page * size;
    }
}
