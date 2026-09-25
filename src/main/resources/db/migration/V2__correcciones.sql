-- =============================================================================
-- V2__correcciones.sql
-- Correcciones al esquema base. Ver docs/00-base/02-CORRECCIONES-DB.md
-- Motor: MySQL 8.4 LTS en Amazon RDS
-- Idempotencia: este script NO es idempotente. Ejecutar una sola vez sobre V1.
--
-- SINTAXIS MySQL — dos diferencias frente a MariaDB que rompen el script si se ignoran:
--   1. Las expresiones en DEFAULT van ENTRE PARENTESIS:  DEFAULT (curdate())
--      Sin parentesis, MySQL devuelve un error de sintaxis.
--      Excepcion historica: CURRENT_TIMESTAMP en DATETIME/TIMESTAMP no los lleva.
--   2. int(10) unsigned  ->  int unsigned  (ancho obsoleto desde 8.0.17)
-- =============================================================================

START TRANSACTION;

-- -----------------------------------------------------------------------------
-- C-06 · estado con DEFAULT 1
-- -----------------------------------------------------------------------------
ALTER TABLE `usuarios`
  MODIFY `estado` tinyint(1) NOT NULL DEFAULT 1;

ALTER TABLE `materias_primas`
  MODIFY `estado` tinyint(1) NOT NULL DEFAULT 1;

ALTER TABLE `productos`
  MODIFY `estado` tinyint(1) NOT NULL DEFAULT 1;

ALTER TABLE `recetas`
  MODIFY `estado` tinyint(1) NOT NULL DEFAULT 1;

-- -----------------------------------------------------------------------------
-- C-07 · Fechas automáticas
-- -----------------------------------------------------------------------------
ALTER TABLE `pedidos`
  MODIFY `fecha_pedido` date NOT NULL DEFAULT (curdate());

ALTER TABLE `devoluciones`
  MODIFY `fecha_devolucion` date NOT NULL DEFAULT (curdate());

ALTER TABLE `inventario`
  MODIFY `fecha_actualizacion` datetime NOT NULL
    DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP;

-- -----------------------------------------------------------------------------
-- C-01 · precio_unitario en detalle_pedido
-- El DEFAULT 0 existe solo para permitir el ALTER sobre filas preexistentes.
-- Se retira acto seguido: la aplicación SIEMPRE envía el precio explícito.
-- -----------------------------------------------------------------------------
ALTER TABLE `detalle_pedido`
  ADD COLUMN `precio_unitario` decimal(10,2) NOT NULL DEFAULT 0.00 AFTER `cantidad`;

UPDATE `detalle_pedido` dp
  JOIN `productos` p ON p.id_producto = dp.id_producto
  SET dp.precio_unitario = p.precio
  WHERE dp.precio_unitario = 0.00;

ALTER TABLE `detalle_pedido`
  MODIFY `precio_unitario` decimal(10,2) NOT NULL;

-- -----------------------------------------------------------------------------
-- C-08 · cantidad_real obligatoria
-- -----------------------------------------------------------------------------
UPDATE `consumo_materia_prima` SET `cantidad_real` = 0.000 WHERE `cantidad_real` IS NULL;

ALTER TABLE `consumo_materia_prima`
  MODIFY `cantidad_real` decimal(10,3) NOT NULL;

-- -----------------------------------------------------------------------------
-- C-11 · bitacora.id_usuario nullable (NULL = acción del sistema)
-- -----------------------------------------------------------------------------
ALTER TABLE `bitacora`
  MODIFY `id_usuario` int unsigned NULL;

-- -----------------------------------------------------------------------------
-- C-05 y C-13 · UNIQUE en líneas de detalle
-- Si alguno falla por datos duplicados preexistentes, depurar antes de reintentar.
-- -----------------------------------------------------------------------------
ALTER TABLE `detalle_receta`
  ADD UNIQUE KEY `uk_detalle_receta` (`id_receta`, `id_materia_prima`);

ALTER TABLE `detalle_compra`
  ADD UNIQUE KEY `uk_detalle_compra` (`id_compra`, `id_materia_prima`);

ALTER TABLE `detalle_pedido`
  ADD UNIQUE KEY `uk_detalle_pedido` (`id_pedido`, `id_producto`);

ALTER TABLE `detalle_plan_produccion`
  ADD UNIQUE KEY `uk_detalle_plan` (`id_plan`, `id_producto`);

ALTER TABLE `consumo_materia_prima`
  ADD UNIQUE KEY `uk_consumo_mp` (`id_detalle_plan`, `id_materia_prima`);

-- -----------------------------------------------------------------------------
-- C-14 · CHECK de valores positivos
-- -----------------------------------------------------------------------------
ALTER TABLE `productos`
  ADD CONSTRAINT `ck_productos_precio` CHECK (`precio` >= 0);

ALTER TABLE `materias_primas`
  ADD CONSTRAINT `ck_mp_stock_minimo`  CHECK (`stock_minimo` >= 0),
  ADD CONSTRAINT `ck_mp_costo`         CHECK (`costo_unitario` >= 0);

ALTER TABLE `inventario`
  ADD CONSTRAINT `ck_inventario_no_negativo` CHECK (`cantidad_disponible` >= 0);

ALTER TABLE `inventario_producto_terminado`
  ADD CONSTRAINT `ck_inv_pt_no_negativo` CHECK (`cantidad_disponible` >= 0);

ALTER TABLE `detalle_compra`
  ADD CONSTRAINT `ck_dc_cantidad` CHECK (`cantidad` > 0),
  ADD CONSTRAINT `ck_dc_precio`   CHECK (`precio_unitario` >= 0);

ALTER TABLE `detalle_pedido`
  ADD CONSTRAINT `ck_dp_cantidad` CHECK (`cantidad` > 0),
  ADD CONSTRAINT `ck_dp_precio`   CHECK (`precio_unitario` >= 0);

ALTER TABLE `detalle_receta`
  ADD CONSTRAINT `ck_dr_cantidad` CHECK (`cantidad` > 0);

ALTER TABLE `detalle_plan_produccion`
  ADD CONSTRAINT `ck_dpp_planificada` CHECK (`cantidad_planificada` > 0),
  ADD CONSTRAINT `ck_dpp_producida`   CHECK (`cantidad_producida` >= 0);

ALTER TABLE `consumo_materia_prima`
  ADD CONSTRAINT `ck_cmp_teorica` CHECK (`cantidad_teorica` >= 0),
  ADD CONSTRAINT `ck_cmp_real`    CHECK (`cantidad_real` >= 0);

ALTER TABLE `devoluciones`
  ADD CONSTRAINT `ck_dev_cantidad` CHECK (`cantidad_devuelta` > 0);

ALTER TABLE `lotes`
  ADD CONSTRAINT `ck_lotes_cantidad` CHECK (`cantidad` > 0);

-- -----------------------------------------------------------------------------
-- C-10 · Integridad de lotes
-- Un lote es de un producto terminado O de una materia prima. Nunca ambos, nunca ninguno.
-- -----------------------------------------------------------------------------
ALTER TABLE `lotes`
  ADD CONSTRAINT `ck_lotes_exclusividad` CHECK (
    (`id_producto` IS NOT NULL AND `id_materia_prima` IS NULL)
 OR (`id_producto` IS NULL     AND `id_materia_prima` IS NOT NULL)
  ),
  ADD CONSTRAINT `ck_lotes_fechas` CHECK (`fecha_vencimiento` >= `fecha_produccion`);

-- -----------------------------------------------------------------------------
-- C-12 · Estados canónicos
-- -----------------------------------------------------------------------------
UPDATE `compras`            SET `estado` = upper(replace(`estado`, ' ', '_'));
UPDATE `pedidos`            SET `estado` = upper(replace(`estado`, ' ', '_'));
UPDATE `devoluciones`       SET `estado` = upper(replace(`estado`, ' ', '_'));
UPDATE `planes_produccion`  SET `estado` = upper(replace(`estado`, ' ', '_'));

ALTER TABLE `compras`
  ADD CONSTRAINT `ck_compras_estado`
  CHECK (`estado` IN ('PENDIENTE','RECIBIDA','CANCELADA'));

ALTER TABLE `pedidos`
  ADD CONSTRAINT `ck_pedidos_estado`
  CHECK (`estado` IN ('PENDIENTE','EN_PREPARACION','ENTREGADO','CANCELADO'));

ALTER TABLE `devoluciones`
  ADD CONSTRAINT `ck_devoluciones_estado`
  CHECK (`estado` IN ('PENDIENTE','APROBADA','RECHAZADA'));

ALTER TABLE `planes_produccion`
  ADD CONSTRAINT `ck_planes_estado`
  CHECK (`estado` IN ('PLANIFICADO','EN_PROCESO','COMPLETADO','CANCELADO'));

-- -----------------------------------------------------------------------------
-- C-03 · Trazabilidad lote ↔ compra
-- Evita la doble suma al inventario cuando un lote nace de una compra ya recibida.
-- -----------------------------------------------------------------------------
ALTER TABLE `lotes`
  ADD COLUMN `id_detalle_compra` int unsigned DEFAULT NULL AFTER `id_materia_prima`,
  ADD KEY `idx_lotes_detalle_compra` (`id_detalle_compra`),
  ADD CONSTRAINT `lotes_ibfk_3` FOREIGN KEY (`id_detalle_compra`)
    REFERENCES `detalle_compra` (`id_detalle_compra`) ON DELETE NO ACTION ON UPDATE NO ACTION;

-- -----------------------------------------------------------------------------
-- C-04 · Kardex de materias primas
-- -----------------------------------------------------------------------------
CREATE TABLE `movimientos_inventario_mp` (
  `id_movimiento`     bigint unsigned NOT NULL AUTO_INCREMENT,
  `id_materia_prima`  int unsigned NOT NULL,
  `tipo_movimiento`   varchar(30) NOT NULL,
  `cantidad`          decimal(10,3) NOT NULL,
  `saldo_anterior`    decimal(10,2) NOT NULL,
  `saldo_posterior`   decimal(10,2) NOT NULL,
  `tabla_origen`      varchar(50)  DEFAULT NULL,
  `id_origen`         int unsigned DEFAULT NULL,
  `observacion`       varchar(255) DEFAULT NULL,
  `id_usuario`        int unsigned DEFAULT NULL,
  `fecha`             datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id_movimiento`),
  KEY `idx_mov_mp_materia` (`id_materia_prima`, `fecha`),
  KEY `idx_mov_mp_origen`  (`tabla_origen`, `id_origen`),
  CONSTRAINT `mov_mp_ibfk_1` FOREIGN KEY (`id_materia_prima`)
    REFERENCES `materias_primas` (`id_materia_prima`),
  CONSTRAINT `mov_mp_ibfk_2` FOREIGN KEY (`id_usuario`)
    REFERENCES `usuarios` (`id_usuario`),
  CONSTRAINT `ck_mov_mp_tipo` CHECK (`tipo_movimiento` IN (
    'ENTRADA_COMPRA','ENTRADA_LOTE','SALIDA_PRODUCCION',
    'AJUSTE_POSITIVO','AJUSTE_NEGATIVO','BAJA_VENCIMIENTO')),
  CONSTRAINT `ck_mov_mp_cantidad` CHECK (`cantidad` > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- -----------------------------------------------------------------------------
-- C-04 · Kardex de producto terminado (por lote)
-- -----------------------------------------------------------------------------
CREATE TABLE `movimientos_inventario_pt` (
  `id_movimiento`   bigint unsigned NOT NULL AUTO_INCREMENT,
  `id_lote`         int unsigned NOT NULL,
  `tipo_movimiento` varchar(30) NOT NULL,
  `cantidad`        decimal(10,2) NOT NULL,
  `saldo_anterior`  decimal(10,2) NOT NULL,
  `saldo_posterior` decimal(10,2) NOT NULL,
  `tabla_origen`    varchar(50)  DEFAULT NULL,
  `id_origen`       int unsigned DEFAULT NULL,
  `observacion`     varchar(255) DEFAULT NULL,
  `id_usuario`      int unsigned DEFAULT NULL,
  `fecha`           datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id_movimiento`),
  KEY `idx_mov_pt_lote`   (`id_lote`, `fecha`),
  KEY `idx_mov_pt_origen` (`tabla_origen`, `id_origen`),
  CONSTRAINT `mov_pt_ibfk_1` FOREIGN KEY (`id_lote`) REFERENCES `lotes` (`id_lote`),
  CONSTRAINT `mov_pt_ibfk_2` FOREIGN KEY (`id_usuario`) REFERENCES `usuarios` (`id_usuario`),
  CONSTRAINT `ck_mov_pt_tipo` CHECK (`tipo_movimiento` IN (
    'ENTRADA_PRODUCCION','SALIDA_VENTA','ENTRADA_DEVOLUCION',
    'AJUSTE_POSITIVO','AJUSTE_NEGATIVO','BAJA_VENCIMIENTO')),
  CONSTRAINT `ck_mov_pt_cantidad` CHECK (`cantidad` > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- -----------------------------------------------------------------------------
-- C-15 · Índices para los filtros exigidos por las HU
-- Nota: aceleran LIKE 'texto%' (prefijo), no LIKE '%texto%'.
-- -----------------------------------------------------------------------------
ALTER TABLE `clientes`           ADD KEY `idx_clientes_nombre`    (`nombre`);
ALTER TABLE `proveedores`        ADD KEY `idx_proveedores_nombre` (`nombre`);
ALTER TABLE `materias_primas`    ADD KEY `idx_mp_nombre`          (`nombre`);
ALTER TABLE `productos`          ADD KEY `idx_productos_nombre`   (`nombre`);
ALTER TABLE `usuarios`           ADD KEY `idx_usuarios_nombre`    (`nombre`);
ALTER TABLE `compras`            ADD KEY `idx_compras_fecha`      (`fecha`, `estado`);
ALTER TABLE `pedidos`            ADD KEY `idx_pedidos_fecha`      (`fecha_pedido`, `estado`);
ALTER TABLE `planes_produccion`  ADD KEY `idx_planes_fecha`       (`fecha_produccion`, `estado`);
ALTER TABLE `lotes`              ADD KEY `idx_lotes_vencimiento`  (`fecha_vencimiento`);
ALTER TABLE `bitacora`           ADD KEY `idx_bitacora_tabla`     (`tabla_afectada`, `fecha`);
ALTER TABLE `devoluciones`       ADD KEY `idx_dev_estado`         (`estado`, `fecha_devolucion`);

-- -----------------------------------------------------------------------------
-- C-02 · Vista de stock de producto terminado agregado por producto
-- Excluye lotes vencidos: producto caducado no es stock vendible.
-- -----------------------------------------------------------------------------
CREATE OR REPLACE SQL SECURITY INVOKER VIEW `v_stock_producto_terminado` AS
SELECT
    p.id_producto,
    p.nombre                                  AS nombre_producto,
    p.precio,
    u.abreviatura                             AS unidad,
    COALESCE(SUM(ipt.cantidad_disponible), 0) AS cantidad_disponible,
    COUNT(DISTINCT l.id_lote)                 AS lotes_activos,
    MIN(l.fecha_vencimiento)                  AS proximo_vencimiento
FROM productos p
    JOIN unidades_medida u ON u.id_unidad = p.id_unidad
    LEFT JOIN lotes l
           ON l.id_producto = p.id_producto
          AND l.fecha_vencimiento >= curdate()
    LEFT JOIN inventario_producto_terminado ipt
           ON ipt.id_lote = l.id_lote
          AND ipt.cantidad_disponible > 0
GROUP BY p.id_producto, p.nombre, p.precio, u.abreviatura;

-- -----------------------------------------------------------------------------
-- C-02 · Lotes disponibles en orden FEFO (First Expired, First Out)
-- El servicio de ventas consume de esta vista en orden para descontar stock.
-- -----------------------------------------------------------------------------
CREATE OR REPLACE SQL SECURITY INVOKER VIEW `v_lotes_disponibles_fefo` AS
SELECT
    l.id_producto,
    l.id_lote,
    l.codigo_lote,
    l.fecha_vencimiento,
    ipt.id_inv_producto,
    ipt.cantidad_disponible,
    DATEDIFF(l.fecha_vencimiento, curdate()) AS dias_para_vencer
FROM lotes l
    JOIN inventario_producto_terminado ipt ON ipt.id_lote = l.id_lote
WHERE l.id_producto IS NOT NULL
  AND ipt.cantidad_disponible > 0
  AND l.fecha_vencimiento >= curdate();
-- NOTA: MySQL ignora el ORDER BY declarado dentro de una vista. El orden FEFO
-- se aplica en la consulta que la usa:
--   SELECT * FROM v_lotes_disponibles_fefo
--    WHERE id_producto = ? ORDER BY fecha_vencimiento ASC, id_lote ASC;

-- -----------------------------------------------------------------------------
-- HU-10 / HU-27 · Inventario de materia prima con estado de stock
-- -----------------------------------------------------------------------------
CREATE OR REPLACE SQL SECURITY INVOKER VIEW `v_inventario_materia_prima` AS
SELECT
    mp.id_materia_prima,
    mp.nombre,
    u.abreviatura                            AS unidad,
    mp.stock_minimo,
    mp.costo_unitario,
    mp.estado,
    COALESCE(i.cantidad_disponible, 0)       AS cantidad_disponible,
    i.fecha_actualizacion,
    CASE
        WHEN COALESCE(i.cantidad_disponible, 0) = 0             THEN 'AGOTADO'
        WHEN COALESCE(i.cantidad_disponible, 0) < mp.stock_minimo THEN 'BAJO'
        ELSE 'OK'
    END                                      AS estado_stock,
    GREATEST(mp.stock_minimo - COALESCE(i.cantidad_disponible, 0), 0) AS faltante
FROM materias_primas mp
    JOIN unidades_medida u ON u.id_unidad = mp.id_unidad
    LEFT JOIN inventario i ON i.id_materia_prima = mp.id_materia_prima;

COMMIT;
