package co.edu.corposucre.productionfood.security;

/**
 * Rutas públicas declaradas en un único sitio (JD-1).
 *
 * El filtro y la cadena de autorización tienen que coincidir EXACTAMENTE aquí:
 * si la ruta pública se escribiera dos veces, el filtro podría cortar una
 * petición que la configuración declara pública (o al revés), y ese desajuste
 * solo se manifestaría en producción.
 */
public final class RutasPublicas {

    /**
     * Única ruta de autenticación pública. Comparación por igualdad exacta a
     * propósito: un prefijo tipo "/api/v1/auth/" dejaría públicas rutas futuras
     * como {@code /api/v1/auth/perfil} que hoy están protegidas.
     */
    public static final String LOGIN = "/api/v1/auth/login";

    private RutasPublicas() {
    }
}
