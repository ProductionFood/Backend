package co.edu.corposucre.productionfood.proveedor;

import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.corposucre.productionfood.common.audit.Auditable;
import co.edu.corposucre.productionfood.common.dto.CambiarEstadoRequest;
import co.edu.corposucre.productionfood.common.error.RecursoNoEncontradoException;
import co.edu.corposucre.productionfood.common.pagination.FiltroTexto;
import co.edu.corposucre.productionfood.common.pagination.PageRequest;
import co.edu.corposucre.productionfood.common.pagination.PageResponse;
import co.edu.corposucre.productionfood.proveedor.dto.ActualizarProveedorRequest;
import co.edu.corposucre.productionfood.proveedor.dto.CrearProveedorRequest;
import co.edu.corposucre.productionfood.proveedor.dto.ProveedorResponse;

@Service
public class ProveedorService {

    private static final Map<String, String> ORDEN = Map.of(
        "nombre", "nombre", "id", "idProveedor");

    private final ProveedorRepository proveedorRepository;

    public ProveedorService(ProveedorRepository proveedorRepository) {
        this.proveedorRepository = proveedorRepository;
    }

    @Transactional
    // En las expresiones de detalle los accesores de record se leen como propiedades
    // (#resultado.nombre) y no como llamadas: el contexto SpEL del aspecto no resuelve la llamada.
    @Auditable(accion = "CREAR", tabla = "proveedores",
            detalle = "'Proveedor creado: ' + #resultado.nombre")
    public ProveedorResponse crear(CrearProveedorRequest req) {
        var proveedor = new Proveedor();
        proveedor.setNombre(req.nombre().trim());
        proveedor.setContacto(textoOpcional(req.contacto()));
        proveedor.setTelefono(textoOpcional(req.telefono()));
        proveedor.setCorreo(textoOpcional(req.correo()));
        // Hibernate enviaría NULL para un campo sin asignar y el DEFAULT de la base no
        // aplicaría: el estado se fija de forma explícita.
        proveedor.setEstado(Boolean.TRUE);

        return aResponse(proveedorRepository.saveAndFlush(proveedor));
    }

    @Transactional(readOnly = true)
    public PageResponse<ProveedorResponse> listar(String nombre, Boolean activo,
                                                  int page, int size, String sort) {
        var orden = PageRequest.of(page, size, sort, ORDEN, "nombre,asc");
        var proveedores = proveedorRepository.buscar(
                FiltroTexto.patronPrefijo(nombre), activo, orden.toPageable());
        return PageResponse.of(
                proveedores.getContent().stream().map(ProveedorService::aResponse).toList(),
                orden.page(), orden.size(), proveedores.getTotalElements());
    }

    @Transactional(readOnly = true)
    public ProveedorResponse obtener(Integer id) {
        return aResponse(obtenerEntidad(id));
    }

    private Proveedor obtenerEntidad(Integer id) {
        return proveedorRepository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("el proveedor", id));
    }

    @Transactional
    @Auditable(accion = "EDITAR", tabla = "proveedores",
            detalle = "'Proveedor editado: ' + #resultado.nombre")
    public ProveedorResponse actualizar(Integer id, ActualizarProveedorRequest req) {
        var proveedor = obtenerEntidad(id);
        // El PUT es un reemplazo completo: un valor opcional nulo borra el anterior.
        // El estado no se toca aquí: solo cambia por la operación dedicada.
        proveedor.setNombre(req.nombre().trim());
        proveedor.setContacto(textoOpcional(req.contacto()));
        proveedor.setTelefono(textoOpcional(req.telefono()));
        proveedor.setCorreo(textoOpcional(req.correo()));
        return aResponse(proveedorRepository.saveAndFlush(proveedor));
    }

    @Transactional
    @Auditable(accion = "CAMBIAR_ESTADO", tabla = "proveedores",
            detalle = "'Proveedor ' + #id + ' estado ' + #req.activo")
    public ProveedorResponse cambiarEstado(Integer id, CambiarEstadoRequest req) {
        var proveedor = obtenerEntidad(id);
        // Sin consulta de compras: la regla R-02 se valida en HU-13 y las compras
        // PENDIENTE de un proveedor desactivado siguen su curso.
        proveedor.setEstado(req.activo());
        return aResponse(proveedorRepository.saveAndFlush(proveedor));
    }

    @Transactional(readOnly = true)
    public boolean existeActivo(Integer id) {
        return proveedorRepository.existeActivo(id);
    }

    private static String textoOpcional(String texto) {
        if (texto == null || texto.isBlank()) {
            return null;
        }
        return texto.trim();
    }

    private static ProveedorResponse aResponse(Proveedor proveedor) {
        return new ProveedorResponse(
                proveedor.getIdProveedor(),
                proveedor.getNombre(),
                proveedor.getContacto(),
                proveedor.getTelefono(),
                proveedor.getCorreo(),
                Boolean.TRUE.equals(proveedor.getEstado()));
    }
}
