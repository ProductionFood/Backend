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
     * Token ausente, expirado, malformado o sin identidad reconocible.
     * Deliberadamente no revela cuál de esos casos ocurrió, para que el cliente
     * no pueda deducir nada sobre el token que le rechazan.
     */
    public static final String MSG_NO_AUTENTICADO =
            "Su sesión ha expirado. Inicie sesión nuevamente.";

    /** Usuario dado de baja: el token sigue siendo criptográficamente válido. */
    public static final String MSG_USUARIO_INACTIVO =
            "Su usuario está desactivado. Contacte al administrador.";

    private MensajesSeguridad() {
    }
}
