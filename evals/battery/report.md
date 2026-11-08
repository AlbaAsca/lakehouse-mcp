# Batería de preguntas (sin modelo)

Generado con `python evals/battery/run.py` el 2026-11-08 sobre la muestra sintética de 50 000 filas.

| # | Nivel | Pregunta | Resultado | Detalle |
|---|---|---|---|---|
| Q1 | fácil | ¿Cuántas filas tiene la tabla? | OK | coincide con la referencia |
| Q2 | fácil | ¿Cuántos usuarios distintos hay? | OK | coincide con la referencia |
| Q3 | fácil | ¿Cuántos eventos hay de cada tipo? | OK | coincide con la referencia |
| Q4 | medio | ¿Qué tres categorías ingresan más por compras (precio > 0, con categoría)? | OK | coincide con la referencia |
| Q5 | medio | ¿Qué tres días (UTC) tienen más compras? | OK | coincide con la referencia |
| Q6 | medio | ¿A qué hora del día (UTC) se compra más? | OK | coincide con la referencia |
| Q7 | medio | ¿Qué tres marcas, con al menos 100 vistas, convierten mejor de vista a compra? | OK | coincide con la referencia |
| Q8 | difícil | ¿Cuántos usuarios han comprado dos veces o más? | OK | coincide con la referencia |
| Q9 | difícil | ¿Cuál es la marca de la primera compra de cada usuario que más se repite (top 3)? (función de ventana) | OK | coincide con la referencia |
| Q10 | difícil | ¿Cuántas sesiones tienen vista, carrito y compra (embudo completo)? | OK | coincide con la referencia |
| Q11 | experta | Cuadro de mando de las 5 marcas con más ingresos: embudo, dinero perdido en carritos abandonados, cuota y Pareto acumulado | OK | coincide con la referencia |
| Q12 | experta | Retención por cohorte: de los usuarios cuya primera compra fue cada día (3 primeros), ¿cuántos volvieron a comprar otro día? | OK | coincide con la referencia |
| Q13 | experta | Mediana de minutos entre la primera vista y la primera compra de una sesión (solo sesiones con ambas) | OK | coincide con la referencia |
| Q14 | máxima | Cesta del cliente: ¿qué pares de categorías compra el mismo usuario? Soporte, confianza y lift (mínimo 3 usuarios) | OK | coincide con la referencia |
| Q15 | máxima | Anomalías: los 3 días cuyos ingresos más se alejan de la media de los 7 días anteriores, en desviaciones típicas (z) | OK | coincide con la referencia |
| Q16 | máxima | Visitas reales: partiendo cada usuario en visitas con más de 30 min de inactividad (LAG), ¿cuántas hay, cuántas compran y de cuántos eventos? | OK | coincide con la referencia |

16 de 16 coinciden con la referencia; 0 rechazadas; 0 con resultado distinto.
