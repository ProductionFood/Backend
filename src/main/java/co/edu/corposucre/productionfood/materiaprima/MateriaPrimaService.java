package co.edu.corposucre.productionfood.materiaprima;

import java.math.BigDecimal;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.corposucre.productionfood.common.audit.Auditable;
import co.edu.corposucre.productionfood.common.dto.CambiarEstadoRequest;
import co.edu.corposucre.productionfood.common.error.ConflictoNegocioException;
import co.edu.corposucre.productionfood.common.error.RecursoNoEncontradoException;
import co.edu.corposucre.productionfood.common.pagination.FiltroTexto;
import co.edu.corposucre.productionfood.common.pagination.PageRequest;
import co.edu.corposucre.productionfood.common.pagination.PageResponse;
import co.edu.corposucre.productionfood.inventario.InventarioRepository;
import co.edu.corposucre.productionfood.materiaprima.dto.ActualizarMateriaPrimaRequest;
import co.edu.corposucre.productionfood.materiaprima.dto.CrearMateriaPrimaRequest;
import co.edu.corposucre.productionfood.materiaprima.dto.MateriaPrimaResponse;
import co.edu.corposucre.productionfood.common.dto.UnidadRefResponse;
import co.edu.corposucre.productionfood.unidadmedida.UnidadMedida;
import co.edu.corposucre.productionfood.unidadmedida.UnidadMedidaRepository;

@Service
public class MateriaPrimaService {

    private static final Map<String, String> ORDEN = Map.of(
        "nombre", "nombre", "stockMinimo", "stockMinimo",
        "costoUnitario", "costoUnitario", "id", "idMateriaPrima");

    private final MateriaPrimaRepository materiaPrimaRepository;
    private final InventarioRepository inventarioRepository;
    private final UnidadMedidaRepository unidadMedidaRepository;
    private final boolean bloquearDesactivacionConRecetas;

    public MateriaPrimaService(MateriaPrimaRepository materiaPrimaRepository,
                               InventarioRepository inventarioRepository,
                               UnidadMedidaRepository unidadMedidaRepository,
                               @Value("${app.materias-primas.bloquear-desactivacion-con-recetas:true}")
                               boolean bloquearDesactivacionConRecetas) {
        this.materiaPrimaRepository = materiaPrimaRepository;
        this.inventarioRepository = inventarioRepository;
        this.unidadMedidaRepository = unidadMedidaRepository;
        this.bloquearDesactivacionConRecetas = bloquearDesactivacionConRecetas;
    }

    @Transactional
    // En las expresiones de detalle de este servicio los accesores de record se leen como
    // propiedades (#resultado.nombre) y no como llamadas (#resultado.nombre()): el contexto
    // SpEL de solo lectura del aspecto no resuelve la llamada y el detalle se perdería en
    // silencio, que es justo lo que la bitácora no debe hacer.
    @Auditable(accion = "CREAR", tabla = "materias_primas",
            detalle = "'Materia prima creada: ' + #resultado.nombre")
    public MateriaPrimaResponse crear(CrearMateriaPrimaRequest req) {
        // La unidad se valida con findById (y no con existsById) para reutilizar la entidad
        // en la respuesta y no hacer una segunda consulta solo para el nombre y la abreviatura.
        var unidad = validarUnidad(req.idUnidad());

        var materiaPrima = new MateriaPrima();
        materiaPrima.setNombre(req.nombre().trim());
        materiaPrima.setIdUnidad(req.idUnidad());
        materiaPrima.setStockMinimo(req.stockMinimo());
        materiaPrima.setCostoUnitario(req.costoUnitario());
        // La columna 'estado' es NOT NULL y con JPA un campo sin asignar se insertaría como
        // NULL: se fija aquí de forma explícita. El DEFAULT 1 de la base es la red de
        // seguridad para las inserciones hechas fuera de la aplicación, no el mecanismo.
        materiaPrima.setEstado(Boolean.TRUE);

        var creado = materiaPrimaRepository.saveAndFlush(materiaPrima);
        // Regla R-01: la fila de inventario se crea en la MISMA transacción que el alta de la
        // materia prima. Si este insert fallara, el saveAndFlush anterior también se revierte
        // y no queda una materia prima «fantasma» sin fila de inventario: sin esa fila, las
        // vistas de stock y los movimientos de HU-10, HU-23 y HU-27 fallarían después.
        inventarioRepository.crearFilaInicial(creado.getIdMateriaPrima());

        return new MateriaPrimaResponse(
                creado.getIdMateriaPrima(),
                creado.getNombre(),
                aUnidadRef(unidad),
                creado.getStockMinimo(),
                creado.getCostoUnitario(),
                Boolean.TRUE.equals(creado.getEstado()),
                // Una materia prima recién creada nace con stock 0 en inventario.
                BigDecimal.ZERO);
    }

    @Transactional(readOnly = true)
    public PageResponse<MateriaPrimaResponse> listar(String nombre, Boolean activo,
                                                     int page, int size, String sort) {
        var orden = PageRequest.of(page, size, sort, ORDEN, "nombre,asc");
        var pagina = materiaPrimaRepository.listarConDetalle(
                FiltroTexto.patronPrefijo(nombre), activo, orden.toPageable());
        return PageResponse.of(
                pagina.getContent().stream().map(MateriaPrimaService::aResponse).toList(),
                orden.page(), orden.size(), pagina.getTotalElements());
    }

    @Transactional(readOnly = true)
    public MateriaPrimaResponse obtener(Integer id) {
        var fila = materiaPrimaRepository.buscarConDetalle(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("la materia prima", id));
        return aResponse(fila);
    }

    @Transactional
    @Auditable(accion = "EDITAR", tabla = "materias_primas",
            detalle = "'Materia prima editada: ' + #resultado.nombre")
    public MateriaPrimaResponse actualizar(Integer id, ActualizarMateriaPrimaRequest req) {
        var materiaPrima = materiaPrimaRepository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("la materia prima", id));
        // El PUT es un reemplazo completo y puede cambiar la unidad: se revalida siempre.
        validarUnidad(req.idUnidad());

        materiaPrima.setNombre(req.nombre().trim());
        materiaPrima.setIdUnidad(req.idUnidad());
        materiaPrima.setStockMinimo(req.stockMinimo());
        materiaPrima.setCostoUnitario(req.costoUnitario());
        // El DTO de actualización no lleva 'estado' a propósito: el estado solo cambia por la
        // operación dedicada de cambio de estado, y el stock vive en inventario y no aquí.
        materiaPrimaRepository.saveAndFlush(materiaPrima);

        // La respuesta se vuelve a leer de la base para que traiga la unidad y el stock
        // actuales y refleje el estado real tras el guardado, igual que en UnidadMedidaService.
        var fila = materiaPrimaRepository.buscarConDetalle(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("la materia prima", id));
        return aResponse(fila);
    }

    @Transactional
    @Auditable(accion = "CAMBIAR_ESTADO", tabla = "materias_primas",
            detalle = "'Materia prima ' + #id + ' estado ' + #req.activo")
    public MateriaPrimaResponse cambiarEstado(Integer id, CambiarEstadoRequest req) {
        var materiaPrima = materiaPrimaRepository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("la materia prima", id));
        // La regla es una advertencia de negocio configurable: desactivar una materia prima
        // que sigue en recetas activas dejaría esas recetas apuntando a un insumo inactivo.
        // Con la comprobación desactivada en configuración la operación se acepta tal cual.
        if (!req.activo() && bloquearDesactivacionConRecetas) {
            var recetas = materiaPrimaRepository.contarRecetasActivas(id);
            if (recetas > 0) {
                throw new ConflictoNegocioException(
                        "MATERIA_PRIMA_EN_USO",
                        "La materia prima aparece en " + recetas + " receta(s) activa(s) y no "
                                + "se puede desactivar. Desactive o elimine esas recetas primero.");
            }
        }
        // Regla R-04: desactivar NO toca el inventario. El historial de movimientos y la
        // cantidad disponible se conservan para que la trazabilidad del kardex no se rompa.
        materiaPrima.setEstado(req.activo());
        materiaPrimaRepository.saveAndFlush(materiaPrima);

        var fila = materiaPrimaRepository.buscarConDetalle(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("la materia prima", id));
        return aResponse(fila);
    }

    @Transactional(readOnly = true)
    public boolean existeActiva(Integer id) {
        return materiaPrimaRepository.existeActiva(id);
    }

    private UnidadMedida validarUnidad(Integer idUnidad) {
        return unidadMedidaRepository.findById(idUnidad)
                .orElseThrow(() -> new ConflictoNegocioException(
                        "UNIDAD_INEXISTENTE", "La unidad de medida indicada no existe."));
    }

    private static UnidadRefResponse aUnidadRef(UnidadMedida unidad) {
        return new UnidadRefResponse(
                unidad.getIdUnidad(), unidad.getNombre(), unidad.getAbreviatura());
    }

    private static MateriaPrimaResponse aResponse(MateriaPrimaConDetalle fila) {
        return new MateriaPrimaResponse(
                fila.getIdMateriaPrima(),
                fila.getNombre(),
                new UnidadRefResponse(
                        fila.getIdUnidad(), fila.getUnidadNombre(), fila.getUnidadAbreviatura()),
                fila.getStockMinimo(),
                fila.getCostoUnitario(),
                Boolean.TRUE.equals(fila.getEstado()),
                fila.getCantidadDisponible());
    }
}
