-- Reparte en el tiempo los pedidos y devoluciones, que la API siempre registra con la fecha de hoy.
-- Los pedidos entregados y cancelados quedan en los ultimos ~60 dias; los pendientes y en
-- preparacion se mantienen recientes. Las entregas nunca son anteriores al pedido.
UPDATE pedidos SET fecha_pedido = CASE estado
    WHEN 'ENTREGADO'  THEN DATE_SUB(CURDATE(), INTERVAL ((id_pedido * 13) % 58 + 2) DAY)
    WHEN 'CANCELADO'  THEN DATE_SUB(CURDATE(), INTERVAL ((id_pedido * 7) % 55 + 1) DAY)
    ELSE DATE_SUB(CURDATE(), INTERVAL (id_pedido % 3) DAY) END;
UPDATE pedidos SET fecha_entrega = CASE estado
    WHEN 'ENTREGADO' THEN LEAST(CURDATE(), DATE_ADD(fecha_pedido, INTERVAL (1 + id_pedido % 3) DAY))
    WHEN 'CANCELADO' THEN DATE_ADD(fecha_pedido, INTERVAL 1 DAY)
    ELSE fecha_entrega END
 WHERE fecha_entrega IS NOT NULL;
UPDATE devoluciones d
  JOIN detalle_pedido dp ON dp.id_detalle_pedido = d.id_detalle_pedido
  JOIN pedidos p ON p.id_pedido = dp.id_pedido
   SET d.fecha_devolucion = LEAST(CURDATE(), DATE_ADD(p.fecha_pedido, INTERVAL (2 + d.id_devolucion % 4) DAY));
