package co.edu.corposucre.productionfood.security;

/**
 * Mensajes de seguridad en un único sitio (JD-3).
 *
 * Los dos textos de aquí son invariantes del contrato: el mismo fallo debe
 * responderse SIEMPRE igual, venga del filtro, del entry point o del servicio.
 * Duplicarlos en varios ficheros era lo que permitía que una edición se
 * desincronizara en silencio sin que ninguna prueba lo notara.
 */
public final class MensajesSeguridad {

    /**
     * Token expirado. El {@code code} de todo 401 sigue siendo NO_AUTENTICADO; solo
     * el texto distingue la causa (ver {@link #MSG_SIN_TOKEN} y
     * {@link #MSG_TOKEN_INVALIDO}).
     */
    public static final String MSG_NO_AUTENTICADO =
            "Su sesión ha expirado. Inicie sesión nuevamente.";

    /** Petición sin cabecera Authorization (o sin credencial utilizable). */
    public static final String MSG_SIN_TOKEN =
            "Debe iniciar sesión: falta el token de autenticación.";

    /** Token malformado, con firma inválida o sin identidad reconocible. */
    public static final String MSG_TOKEN_INVALIDO =
            "El token de autenticación es inválido. Inicie sesión nuevamente.";

    /**
     * Atributo del request con el que JwtAuthenticationFilter le deja al
     * EntryPointNoAutenticado el mensaje 401 que corresponde a la causa.
     */
    public static final String ATRIBUTO_MENSAJE_401 =
            MensajesSeguridad.class.getName() + ".MENSAJE_401";

    /** Usuario dado de baja: el token sigue siendo criptográficamente válido. */
    public static final String MSG_USUARIO_INACTIVO =
            "Su usuario está desactivado. Contacte al administrador.";

    private MensajesSeguridad() {
    }
}
