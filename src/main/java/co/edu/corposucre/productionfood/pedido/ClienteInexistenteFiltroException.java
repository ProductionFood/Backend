package co.edu.corposucre.productionfood.pedido;

import org.springframework.http.HttpStatus;

import co.edu.corposucre.productionfood.common.error.ApiException;

/**
 * El filtro {@code idCliente} del listado de pedidos apunta a un cliente que no existe
 * (HU-25 {@code CLIENTE_INEXISTENTE}, 404).
 *
 * <p>Existe como excepción propia porque el mismo código tiene dos significados según el camino:
 * en el alta y en la edición de HU-16 {@code CLIENTE_INEXISTENTE} es un
 * {@link co.edu.corposucre.productionfood.common.error.ConflictoNegocioException} (409), porque
 * ahí se escribe contra un cliente de referencia; en el filtro del listado no hay escritura y el
 * 404 explica que la página está vacía por un identificador inexistente. Se lanza solo en
 * {@link PedidoService#listar}; los caminos de HU-16 no se tocan.
 *
 * <p>Sigue el patrón de {@code ProveedorInexistenteFiltroException} de compras: el manejador
 * global la serializa como cualquier otra {@link ApiException}.
 */
public class ClienteInexistenteFiltroException extends ApiException {

    public ClienteInexistenteFiltroException(Integer idCliente) {
        super("CLIENTE_INEXISTENTE", HttpStatus.NOT_FOUND,
              String.format("No se encontró el cliente con id %s.", idCliente));
    }
}
