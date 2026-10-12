package co.edu.corposucre.productionfood.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class SecurityUtils {

    private SecurityUtils() {
    }

    /**
     * Principal completo de la petición en curso, o null si no hay uno.
     *
     * Se prefiere a encadenar los getters sueltos cuando se necesitan varios campos
     * a la vez: leerlos por separado abre la posibilidad de que el principal cambie
     * entre llamadas y se arme una respuesta con datos de dos usuarios distintos.
     * También evita repetir el instanceof en cada consumidor.
     */
    public static UsuarioAutenticado usuarioActual() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UsuarioAutenticado u) {
            return u;
        }
        return null;
    }

    public static Integer idUsuarioActual() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UsuarioAutenticado u) {
            return u.idUsuario();
        }
        return null;
    }

    public static String correoActual() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UsuarioAutenticado u) {
            return u.correo();
        }
        return null;
    }

    public static String rolActual() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UsuarioAutenticado u) {
            return u.rol();
        }
        return null;
    }
}
