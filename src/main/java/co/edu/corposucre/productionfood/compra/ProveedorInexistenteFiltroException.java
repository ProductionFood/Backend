package co.edu.corposucre.productionfood.compra;

import org.springframework.http.HttpStatus;

import co.edu.corposucre.productionfood.common.error.ApiException;

/**
 * El filtro {@code idProveedor} del historial de compras apunta a un proveedor que no existe
 * (HU-15 §5 {@code PROVEEDOR_INEXISTENTE}, 404).
 *
 * <p>Existe como excepción propia del módulo porque el mismo código tiene dos significados
 * distintos según el camino: en el alta y en la edición de HU-13 {@code PROVEEDOR_INEXISTENTE}
 * se lanza como {@link co.edu.corposucre.productionfood.common.error.ConflictoNegocioException}
 * (409), porque ahí el cliente intenta escribir contra un proveedor de referencia; en el filtro
 * del historial, en cambio, no hay escritura y el 404 explica mejor que la página de resultados
 * está vacía por un identificador inexistente. Esta clase se lanza solo en
 * {@link CompraService#listar}; los caminos de HU-13 no se tocan.
 *
 * <p>El mensaje sigue el patrón del 404 propio del módulo
 * ({@link CompraInexistenteException}); el manejador global la serializa igual que cualquier
 * otra {@link ApiException}.
 */
public class ProveedorInexistenteFiltroException extends ApiException {

    public ProveedorInexistenteFiltroException(Integer idProveedor) {
        super("PROVEEDOR_INEXISTENTE", HttpStatus.NOT_FOUND,
              String.format("No se encontró el proveedor con id %s.", idProveedor));
    }
}
