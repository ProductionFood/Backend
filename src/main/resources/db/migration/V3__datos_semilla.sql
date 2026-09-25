-- =============================================================================
-- V3__datos_semilla.sql  ·  MySQL 8.4 LTS (Amazon RDS)
-- Datos mínimos para que el sistema arranque. Ver 02-CORRECCIONES-DB.md §C-16
-- =============================================================================

START TRANSACTION;

-- -----------------------------------------------------------------------------
-- Roles — ver 03-MATRIZ-ROLES.md
-- Los ids son fijos y se referencian desde el código. No reordenar.
-- -----------------------------------------------------------------------------
INSERT INTO `roles` (`id_rol`, `nombre`, `descripcion`) VALUES
  (1, 'ADMIN',      'Administrador del sistema. Acceso total.'),
  (2, 'PRODUCCION', 'Encargado de producción. Planes, recetas, consumo e inventario.'),
  (3, 'COMPRAS',    'Encargado de compras. Proveedores, compras y recepción de materia prima.'),
  (4, 'VENTAS',     'Encargado de ventas. Clientes, pedidos y devoluciones.'),
  (5, 'CONSULTA',   'Usuario de consulta. Solo lectura sobre los módulos autorizados.');

-- -----------------------------------------------------------------------------
-- Unidades de medida — catálogo base de una panadería
-- -----------------------------------------------------------------------------
INSERT INTO `unidades_medida` (`nombre`, `abreviatura`) VALUES
  ('Kilogramo',     'kg'),
  ('Gramo',         'g'),
  ('Litro',         'L'),
  ('Mililitro',     'mL'),
  ('Unidad',        'und'),
  ('Docena',        'doc'),
  ('Paquete',       'paq'),
  ('Bolsa',         'bol'),
  ('Bandeja',       'bdj');

-- -----------------------------------------------------------------------------
-- Administrador inicial
--
-- ⚠️  ADVERTENCIA DE SEGURIDAD
-- El hash corresponde a la contraseña 'Admin123*'. Es una credencial pública,
-- conocida por cualquiera que lea este repositorio.
--
-- OBLIGATORIO antes de exponer la aplicación fuera de localhost:
--   1. Iniciar sesión con este usuario.
--   2. Crear un administrador real con contraseña propia.
--   3. Desactivar este usuario (estado = 0).
--
-- Está registrado como tarea de cierre en docs/HU-01/tarea-backend.md.
-- Para regenerar el hash:
--   new BCryptPasswordEncoder().encode("<contraseña>")
-- -----------------------------------------------------------------------------
INSERT INTO `usuarios` (`nombre`, `correo`, `password`, `estado`, `id_rol`) VALUES
  ('Administrador Inicial',
   'admin@productionfood.local',
   '$2a$10$oH1H.xBcj219Oay1y98Uv.W0kKpAbBfctm/3ZahhBl8g6pndoRUci',
   1,
   1);

-- -----------------------------------------------------------------------------
-- Registro del acto de siembra en bitácora (id_usuario NULL = sistema, ver C-11)
-- -----------------------------------------------------------------------------
INSERT INTO `bitacora` (`id_usuario`, `accion`, `tabla_afectada`, `detalle`) VALUES
  (NULL, 'SEED', 'usuarios', 'Creación del administrador inicial por script de semilla V3');

COMMIT;
