package co.edu.corposucre.productionfood.cliente;

import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.corposucre.productionfood.cliente.dto.ActualizarClienteRequest;
import co.edu.corposucre.productionfood.cliente.dto.ClienteResponse;
import co.edu.corposucre.productionfood.cliente.dto.CrearClienteRequest;
import co.edu.corposucre.productionfood.common.audit.Auditable;
import co.edu.corposucre.productionfood.common.dto.CambiarEstadoRequest;
import co.edu.corposucre.productionfood.common.error.ConflictoNegocioException;
import co.edu.corposucre.productionfood.common.error.RecursoNoEncontradoException;
import co.edu.corposucre.productionfood.common.pagination.FiltroTexto;
import co.edu.corposucre.productionfood.common.pagination.PageRequest;
import co.edu.corposucre.productionfood.common.pagination.PageResponse;

@Service
public class ClienteService {

    private static final Map<String, String> ORDEN = Map.of(
        "nombre", "nombre", "id", "idCliente");

    private final ClienteRepository clienteRepository;
    private final boolean bloquearDesactivacionConPedidos;

    public ClienteService(ClienteRepository clienteRepository,
                          @Value("${app.clientes.bloquear-desactivacion-con-pedidos:true}")
                          boolean bloquearDesactivacionConPedidos) {
        this.clienteRepository = clienteRepository;
        this.bloquearDesactivacionConPedidos = bloquearDesactivacionConPedidos;
    }

    @Transactional
    // En las expresiones de detalle de este servicio los accesores de record se leen como
    // propiedades (#resultado.nombre) y no como llamadas (#resultado.nombre()): el contexto
    // SpEL de solo lectura del aspecto no resuelve la llamada y el detalle se perdería en
    // silencio, que es justo lo que la bitácora no debe hacer.
    @Auditable(accion = "CREAR", tabla = "clientes",
            detalle = "'Cliente creado: ' + #resultado.nombre")
    public ClienteResponse crear(CrearClienteRequest req) {
        var cliente = new Cliente();
        cliente.setNombre(req.nombre().trim());
        cliente.setContacto(textoOpcional(req.contacto()));
        cliente.setTelefono(textoOpcional(req.telefono()));
        // La columna 'estado' es NOT NULL y con JPA un campo sin asignar se insertaría como
        // NULL: se fija aquí de forma explícita. El DEFAULT 1 de la base es la red de
        // seguridad para las inserciones hechas fuera de la aplicación, no el mecanismo.
        cliente.setEstado(Boolean.TRUE);

        return aResponse(clienteRepository.saveAndFlush(cliente));
    }

    @Transactional(readOnly = true)
    public PageResponse<ClienteResponse> listar(String nombre, Boolean activo,
                                                int page, int size, String sort) {
        var orden = PageRequest.of(page, size, sort, ORDEN, "nombre,asc");
        var clientes = clienteRepository.buscar(
                FiltroTexto.patronPrefijo(nombre), activo, orden.toPageable());
        return PageResponse.of(
                clientes.getContent().stream().map(ClienteService::aResponse).toList(),
                orden.page(), orden.size(), clientes.getTotalElements());
    }

    @Transactional(readOnly = true)
    public ClienteResponse obtener(Integer id) {
        return aResponse(obtenerEntidad(id));
    }

    private Cliente obtenerEntidad(Integer id) {
        return clienteRepository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("el cliente", id));
    }

    @Transactional
    @Auditable(accion = "EDITAR", tabla = "clientes",
            detalle = "'Cliente editado: ' + #resultado.nombre")
    public ClienteResponse actualizar(Integer id, ActualizarClienteRequest req) {
        var cliente = obtenerEntidad(id);
        // El PUT es un reemplazo completo: nombre, contacto y teléfono se sobrescriben
        // siempre, y un contacto o un teléfono nulo borra el valor anterior en lugar de
        // conservarlo.
        cliente.setNombre(req.nombre().trim());
        cliente.setContacto(textoOpcional(req.contacto()));
        cliente.setTelefono(textoOpcional(req.telefono()));
        // El DTO de actualización no lleva 'estado' a propósito: el estado solo cambia por
        // la operación dedicada de cambio de estado.
        return aResponse(clienteRepository.saveAndFlush(cliente));
    }

    @Transactional
    @Auditable(accion = "CAMBIAR_ESTADO", tabla = "clientes",
            detalle = "'Cliente ' + #id + ' estado ' + #req.activo")
    public ClienteResponse cambiarEstado(Integer id, CambiarEstadoRequest req) {
        var cliente = obtenerEntidad(id);
        // La regla es una advertencia de negocio configurable: desactivar a un cliente no
        // cancela los pedidos que ya le pertenecen, solo impide ventas nuevas. Con la
        // comprobación desactivada en configuración la operación se acepta tal cual.
        if (!req.activo() && bloquearDesactivacionConPedidos) {
            if (clienteRepository.contarPedidosEnCurso(id) > 0) {
                throw new ConflictoNegocioException(
                        "CLIENTE_CON_PEDIDOS_ACTIVOS",
                        "El cliente tiene pedidos en curso y no se puede desactivar. "
                                + "Desactive o cancele los pedidos pendientes primero.");
            }
        }
        cliente.setEstado(req.activo());
        return aResponse(clienteRepository.saveAndFlush(cliente));
    }

    private static String textoOpcional(String texto) {
        if (texto == null || texto.isBlank()) {
            return null;
        }
        return texto.trim();
    }

    private static ClienteResponse aResponse(Cliente cliente) {
        return new ClienteResponse(
                cliente.getIdCliente(),
                cliente.getNombre(),
                cliente.getContacto(),
                cliente.getTelefono(),
                Boolean.TRUE.equals(cliente.getEstado()));
    }
}
