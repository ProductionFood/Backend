package co.edu.corposucre.productionfood.common.pagination;

import java.util.Locale;

/**
 * Helper común para construir el patrón de prefijo que usan las búsquedas con LIKE.
 */
public final class FiltroTexto {

    private FiltroTexto() {
    }

    /**
     * Convierte el texto del filtro en un patrón de prefijo para un LIKE, escapando los
     * comodines de SQL y normalizando a minúsculas. Devuelve {@code null} cuando no hay
     * filtro para que el repositorio omita ese criterio de búsqueda.
     *
     * <p>Los comodines de SQL se escapan porque el texto va a un LIKE: sin este escapado,
     * un usuario que escriba '%' obtendría todos los registros. La minúscula usa
     * {@code Locale.ROOT} porque {@code toLowerCase()} sin locale sigue el del sistema, y en
     * un locale como el de Türkiye la 'I' no se convierte en 'i' y la búsqueda no
     * encontraría nada.
     *
     * @param texto texto escrito por el usuario; puede ser {@code null} o vacío
     * @return patrón {@code <texto>%} o {@code null} si no hay filtro
     */
    public static String patronPrefijo(String texto) {
        if (texto == null || texto.isBlank()) {
            return null;
        }
        return texto.trim().toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_") + "%";
    }
}
