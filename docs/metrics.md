# Definición de las métricas

Definen en palabras las cuatro métricas de la capa semántica. La verdad de referencia (T-029) y el YAML (T-031) parten de este documento, cada uno por su lado. Si una duda no se resuelve aquí, se corrige el documento, no se interpreta.

## Reglas comunes

Se aplican a las cuatro métricas.

| Tema | Regla | Por qué | Alternativa descartada |
|---|---|---|---|
| **Unidad** | La sesión, identificada solo por `user_session`. `user_id` no interviene. | El camino de compra ocurre dentro de una visita. | Usuario: un mismo usuario que vuelve tres veces y compra una daría una conversión engañosa. |
| **Duplicados** | Filas iguales en las nueve columnas cuentan como una. | Una métrica no debe cambiar por una copia: el origen trae duplicados y `make chaos` (planificado) los inyectará. | Conservarlos: inflaría el dinero. Dos compras reales idénticas en el mismo segundo no se distinguen y cuentan como una. |
| **Sesión nula** | La fila se ignora en las cuatro métricas. | Sin sesión no hay camino ni pedido, y una sola regla permite cuadrar los totales. | Atribuirla por `user_id` y hora: es una suposición. Contarla solo en el dinero: el ticket y las marcas dejarían de cuadrar. |
| **`price` <= 0** | Se ignora en el dinero (ticket y marcas). En el embudo y el abandono cuenta. | El precio erróneo no borra lo que hizo la persona. No hay indicio de que un negativo sea una devolución. | Quitar la fila en todas: una compra real desaparecería del embudo. |
| **Tipos de evento** | Solo `view`, `cart` y `purchase`, en coincidencia exacta. Cualquier otro se ignora. | Son los únicos que definen las etapas. | |
| **Tiempo** | `event_time` tal como está guardado, en UTC. Un día va de 00:00:00 inclusive a 00:00:00 del día siguiente, exclusive. | Es la zona del origen. | Hora local de la tienda: el dataset no la indica. |
| **Ventana** | `from` y `to` son fechas, **ambas incluidas**. `from = to` es un solo día. Si falta una, ese lado queda abierto; sin ninguna, todos los datos. Si `from` es posterior a `to`, no hay datos. | Es lo que se espera al preguntar «del 1 al 3». | Intervalo semiabierto: exige pedir «hasta el día siguiente» para ver un día. |
| **Sin datos** | Si el denominador es 0, el resultado es nulo, nunca 0. | Una caída al 0 % y un día sin datos son cosas distintas. | |
| **Redondeo** | Solo al presentar: tasas de 0 a 1 con 6 decimales, dinero con 2. Al comparar se admite una diferencia de 0,000001 en tasas y 0,01 en dinero. | `price` es de coma flotante, y sumar en otro orden cambia los últimos decimales. | |

El dinero va en la unidad de `price`: el esquema no incluye moneda.

### A qué día pertenece cada cosa

- **Embudo y abandono:** la sesión entera pertenece al día UTC de su **primer evento** `view`, `cart` o `purchase`. Entra en la ventana si ese día cae dentro, y entonces cuentan todos sus eventos, aunque alguno sea posterior a `to`. Si empezó antes de `from`, no entra.
- **Ticket y marcas:** cada compra pertenece al día UTC de la propia compra, porque el dinero se ingresa ese día. Es también lo que cuenta una pregunta como «¿cuánto se vendió ayer?».

Consecuencias:

- En el embudo y el abandono, sumar los conteos de cada día da los del periodo completo. La sesión empezada a las 23:55 y comprada a las 00:01 no aparece como abandono un día y compra suelta al siguiente.
- El valor de un día puede cambiar si llegan eventos posteriores de sus sesiones. Una sesión aún abierta cuenta como abandonada hasta que compre; no hay tiempo de espera.
- El ticket del periodo no es la media de los tickets diarios, ni su denominador es siempre la suma de los diarios: una sesión con compras en dos días cuenta una vez en el periodo y una vez en cada día.

Descartado: filtrar los eventos de la ventana y agrupar después. Es más simple y solo lee las particiones de la ventana, pero parte las sesiones que cruzan medianoche, y los días dejarían de sumar el total. Calcular por sesión obliga a leer fuera de la ventana; es asumible con 1-5 millones de filas.

### Cuánto importa cada caso

Muestra sintética, `testdata/synthetic-events.csv.gz`. El dataset real no estaba disponible al escribir esto, así que **sus proporciones están sin comprobar**.

| Caso | Cantidad |
|---|---|
| Filas / filas distintas | 50.000 / 49.750 |
| Sesiones, sin filas de sesión nula ni copias | 19.800 |
| Sesiones que cruzan medianoche UTC | 44 |
| Filas de compra con sesión nula, contando copias | 9 |
| Filas de compra con `price` <= 0, contando copias | 13 |
| Filas de compra con `brand` nula, contando copias | 330 |
| Sesiones con compra pero sin carrito | 0 |
| Sesiones con más de una compra | 0 |

La muestra no contiene los dos últimos casos, pero las reglas los cubren y el dataset real podría tenerlos.

## Etapa de una sesión

Una sesión llega a una etapa si tiene ese evento o uno de una etapa posterior. No importa el orden de los eventos ni que sean del mismo producto.

| Etapa | La sesión tiene alguno de estos eventos |
|---|---|
| Llega a vista | `view`, `cart` o `purchase` |
| Llega a carrito | `cart` o `purchase` |
| Llega a compra | `purchase` |

Así, cada etapa contiene a la siguiente y ninguna tasa supera 1. Una compra sin carrito registrado sigue siendo una compra.

Descartado:

- **Exigir las tres etapas en orden, con el mismo producto:** dejaría fuera compras reales cuando falte un evento, y «compras» no coincidiría con lo que ve el usuario.
- **Dar el abandono solo sobre sesiones con `cart`:** el abandono y la tasa de carrito a compra parecerían complementarias sin serlo, y esa trampa alcanzaría también al agente.

## Conversión view → cart → purchase

**Qué responde:** de las sesiones de la ventana, cuántas avanzan en cada paso.

| Resultado | Cálculo |
|---|---|
| `view_to_cart` | sesiones que llegan a carrito / sesiones que llegan a vista |
| `cart_to_purchase` | sesiones que llegan a compra / sesiones que llegan a carrito |
| `view_to_purchase` | sesiones que llegan a compra / sesiones que llegan a vista |

Devuelve también los tres conteos de sesiones.

**Ejemplo.** Ventana: del 2024-03-01 al 2024-03-01.

| # | `event_time` (UTC) | `event_type` | `price` | `user_session` |
|---|---|---|---:|---|
| 1 | 2024-03-01 23:50:00 | view | 40,00 | s1 |
| 2 | 2024-03-01 23:55:00 | cart | 40,00 | s1 |
| 3 | 2024-03-02 00:01:00 | purchase | -5,00 | s1 |
| 4 | 2024-03-01 12:00:00 | view | 10,00 | s2 |
| 5 | 2024-03-01 12:05:00 | view | 10,00 | *(nula)* |

- La fila 5 se ignora por no tener sesión.
- s1 empezó el día 1, así que la fila 3 cuenta aunque sea del día 2. Su precio negativo no importa aquí.
- Llegan a vista s1 y s2 (2), a carrito s1 (1), a compra s1 (1).

**Resultado:** `view_to_cart` = 1/2 = 0,5; `cart_to_purchase` = 1/1 = 1; `view_to_purchase` = 1/2 = 0,5.

Con la ventana del 2024-03-02 al 2024-03-02 no hay sesiones: las tres tasas son nulas y los conteos, 0.

## Ticket medio

**Qué responde:** cuánto se gasta de media en cada pedido.

**Compra válida:** evento `purchase`, con sesión, con `price` > 0 y sin contar copias, cuyo día cae en la ventana.

**Cálculo:** suma de `price` de las compras válidas / sesiones distintas con al menos una compra válida.

El dataset no tiene identificador de pedido, así que **un pedido es una sesión con compra**. Varias compras de una sesión forman un solo pedido. Devuelve también los ingresos y el número de pedidos.

Descartado: la media de `price` por evento de compra. Es el precio medio de lo vendido, no lo que gasta un cliente en un pedido.

**Ejemplo.** Ventana: del 2024-03-01 al 2024-03-01.

| # | `event_time` (UTC) | `event_type` | `product_id` | `price` | `user_session` |
|---|---|---|---|---:|---|
| 1 | 2024-03-01 10:00:00 | purchase | 1 | 100,00 | s1 |
| 2 | 2024-03-01 10:00:00 | purchase | 1 | 100,00 | s1 |
| 3 | 2024-03-01 10:05:00 | purchase | 2 | 50,00 | s1 |
| 4 | 2024-03-01 11:00:00 | purchase | 3 | 30,00 | s2 |
| 5 | 2024-03-01 12:00:00 | purchase | 4 | 0,00 | s3 |

- La fila 2 es copia de la 1.
- La fila 5 no es válida por su precio, así que s3 no es un pedido.
- Ingresos: 100 + 50 + 30 = 180. Pedidos: s1 y s2 (2).

**Resultado:** ticket medio = 180 / 2 = 90,00.

## Abandono de carrito

**Qué responde:** de las sesiones que llegan a carrito, cuántas se van sin comprar.

**Cálculo:** sesiones con `cart` y sin `purchase` / sesiones que llegan a carrito.

Siempre se cumple que abandono + `cart_to_purchase` = 1 en la misma ventana, salvo cuando ambos son nulos. Devuelve también los dos conteos.

Se mide por sesión, no por producto: si se añaden dos productos y se compra uno, la sesión no ha abandonado.

**Ejemplo.** Ventana: del 2024-03-01 al 2024-03-01. Todas las filas son de ese día.

| # | `event_time` (UTC) | `event_type` | `product_id` | `user_session` |
|---|---|---|---|---|
| 1 | 2024-03-01 09:00:00 | cart | 1 | s1 |
| 2 | 2024-03-01 09:10:00 | purchase | 1 | s1 |
| 3 | 2024-03-01 10:00:00 | cart | 2 | s2 |
| 4 | 2024-03-01 11:00:00 | purchase | 3 | s3 |
| 5 | 2024-03-01 12:00:00 | cart | 4 | *(nula)* |

- La fila 5 se ignora por no tener sesión.
- Llegan a carrito s1, s2 y s3 (3). s3 compró sin carrito registrado, y cuenta como no abandonada.
- Abandona solo s2 (1).

**Resultado:** abandono = 1/3 = 0,333333, y `cart_to_purchase` = 2/3 = 0,666667.

## Top marcas

**Qué responde:** qué marcas ingresan más en la ventana.

**Cálculo:** sobre las compras válidas (las del [ticket medio](#ticket-medio)), se agrupa por `brand` y se suma `price`.

- **Orden:** de más a menos ingresos, redondeados a 2 decimales. Si empatan, por `brand` de la A a la Z, comparando por punto de código Unicode (las mayúsculas van antes que las minúsculas).
- **Límite:** las 10 primeras por defecto, o las que indique `limit`. Solo esta métrica lo usa.
- **Sin compras válidas:** lista vacía y `revenue_without_brand` = 0.
- **`brand`:** se compara tal cual, sin cambiar mayúsculas. El vacío equivale a nulo.
- **Marca nula:** no entra en el ranking porque no es una marca, pero sus ingresos se devuelven aparte como `revenue_without_brand`, para que los totales cuadren.

Cada marca devuelve `brand`, `revenue`, `purchases` (compras válidas) y `share` (sus ingresos / todos los ingresos válidos, incluidos los que no tienen marca).

Descartado: ordenar por número de compras. Con precios muy distintos, premia lo barato; las compras se devuelven igualmente.

**Ejemplo.** Ventana: del 2024-03-01 al 2024-03-01, `limit` = 2. Todas las filas son compras de ese día.

| # | `event_time` (UTC) | `brand` | `price` | `user_session` |
|---|---|---|---:|---|
| 1 | 2024-03-01 09:00:00 | beta | 120,00 | s1 |
| 2 | 2024-03-01 10:00:00 | alfa | 100,00 | s2 |
| 3 | 2024-03-01 11:00:00 | alfa | 20,00 | s3 |
| 4 | 2024-03-01 12:00:00 | *(nula)* | 500,00 | s4 |
| 5 | 2024-03-01 13:00:00 | gamma | -10,00 | s5 |

- La fila 5 no es válida por su precio, así que gamma no aparece.
- La fila 4 no entra en el ranking, pero suma en el total: 120 + 120 + 500 = 740.
- alfa y beta empatan a 120, y alfa va antes por orden alfabético.

**Resultado:**

| Puesto | `brand` | `revenue` | `purchases` | `share` |
|---:|---|---:|---:|---:|
| 1 | alfa | 120,00 | 2 | 0,162162 |
| 2 | beta | 120,00 | 1 | 0,162162 |

`revenue_without_brand` = 500,00.

## Ambigüedades resueltas por la verdad de referencia (T-029)

Encontradas al calcular los valores sin mirar el YAML. Las cifras de la muestra están en `evals/truth/metrics.json`.

- **`price` vacío en una compra:** no es > 0, así que la compra no es válida para el dinero (ticket y marcas); sigue contando en el embudo y el abandono.
- **Redondeo:** a la mitad hacia arriba (half-up), solo al presentar. El orden de las marcas usa los ingresos ya redondeados a 2 decimales.
- **Primer evento de la sesión:** el de menor `event_time` entre `view`, `cart` y `purchase`; los de otro tipo no la abren.
- **Mayúsculas en `event_type`** (`Purchase`): es otro tipo y se ignora.
- **`share` con ingresos 0:** nulo.
- **Pedidos de varios días:** una sesión con compras válidas en dos días es un pedido en cada día y uno solo en el total.
