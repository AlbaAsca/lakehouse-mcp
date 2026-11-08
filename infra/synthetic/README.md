# Muestra sintética para CI

La licencia del dataset real («© Original Authors») impide subir un extracto, así que CI y los tests usan eventos inventados con el mismo esquema: `testdata/synthetic-events.csv.gz`. Ningún valor sale del CSV real: el generador no lee ningún fichero.

Regenerarla (solo biblioteca estándar):

```
python infra/synthetic/generate.py --seed 42 --rows 50000 --out testdata/synthetic-events.csv.gz
```

Misma semilla y mismas filas dan el mismo fichero byte a byte en una misma máquina. Entre máquinas lo garantizado es el contenido descomprimido, porque los bytes de gzip pueden cambiar con la versión de zlib; por eso el test compara el fichero versionado descomprimido.

## Contenido

50.000 filas ordenadas por `event_time`, del 2024-03-01 al 2024-03-14 (14 días, fuera del periodo del dataset real). Formato como el origen: `2024-03-01 00:00:00 UTC`; los nulos son campos vacíos.

| Campo | Valores inventados |
|---|---|
| `product_id`, `user_id` | por encima de 900.000.000 |
| `category_id` | por encima de 9.000.000.000.000.000.000 |
| `brand` | prefijo `synth-` |
| `category_code` | prefijo `synth.` |
| `user_session` | UUID aleatorio |

Cada sesión es coherente: 1-3 vistas y, a veces, carrito y compra del último producto visto.

## Casos sucios

Cantidades de la muestra versionada, contando duplicados:

| Caso | Filas | Cómo se genera |
|---|---|---|
| `category_code` nulo | 16.853 | categorías sin código, fijo por producto (≈34 %; real 32 %) |
| `brand` nulo | 7.261 | productos sin marca, fijo por producto (≈15 %; real 14 %) |
| `user_session` nulo | 50 | solo en el último evento de una sesión |
| `price` 0 | 100 | por evento: 1 de cada 500 filas |
| `price` negativo | 101 | por evento: 1 de cada 500 filas |
| duplicados exactos | 250 copias | 1 de cada 200 filas; 49.750 filas distintas |

Con otro tamaño se mantienen las proporciones de los casos por evento. Mínimo: 1.000 filas, para que aparezcan todos.

## Caída plantada

**El 2024-03-10 casi no se compra la marca `synth-nimbora`.** Es lo que el agente tiene que encontrar y explicar.

La causa simulada es un fallo al pagar esa marca: ese día se ve y se añade al carrito con normalidad, pero la probabilidad de comprar lo que hay en el carrito pasa del 80 % al 8 %. Solo afecta a esa marca (un 48 % de las vistas) y ese día.

| Eventos, sin quitar duplicados | 2024-03-10 | Resto de días (mín-máx) |
|---|---:|---:|
| Compras de `synth-nimbora` | 3 | 21-41 |
| Compras de las demás marcas | 47 | 28-50 |
| Compras totales | 50 | 53-86 |
| Vistas de `synth-nimbora` | 1.418 | 1.307-1.449 |
| Carritos de `synth-nimbora` | 37 | 23-49 |

## Proporciones frente al perfil real

Calibradas con los agregados de `docs/data-profile.md` (sin valores individuales); `tests/test_synthetic.py` exige estar a ±5 puntos: vistas ≈96 % (real 96,07), carritos ≈2,4 % (2,18), compras ≈1,9 % (1,75). Con tan pocas compras la caída plantada es más fina que antes, de ahí el mayor peso de `synth-nimbora`.

Estas cifras describen la muestra, no son la verdad de referencia de las evals: esa se calcula aparte sobre el CSV. Los tests están en `tests/test_synthetic.py`.

La verdad de referencia de los chequeos de calidad (nulos por columna, duplicados, `price` <= 0 y filas por día y tipo de evento) está en `evals/truth/quality.json`. Si cambia la muestra, se regenera con `python evals/truth/compute_quality_truth.py`; `tests/test_quality_truth.py` falla mientras no esté al día.
