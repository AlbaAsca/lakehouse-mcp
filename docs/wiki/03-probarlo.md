# Pruébalo tú

Vas a levantar el lakehouse en tu ordenador, crear una tabla, viajar a una versión anterior y ver los ficheros que hay debajo. No necesitas saber nada de datos: si un término te suena raro, está en el [glosario](01-conceptos.md).

**Lo que no vas a ver:** eventos reales fluyendo solos hasta una tabla, ni al agente. Eso aún no existe ([estado](README.md#qué-funciona-hoy)). Por eso aquí la tabla se crea a mano y con datos inventados.

[Volver a la portada](README.md)

## Antes de empezar

- **Docker** con unos 8 GB de memoria asignados. El proyecto se diseñó para 7,6 GB. No hace falta nada más: ni Java, ni Maven, ni make.
- **Git** para clonar.
- **Internet**, para las imágenes y las extensiones de DuckDB. El primer arranque también descarga y compila Iceberg, así que tardará varios minutos.
- **Una terminal.** Los comandos son para Linux, macOS o Git Bash, y es lo que se ha probado. En Windows, usa Git Bash y ejecuta primero `export MSYS_NO_PATHCONV=1`, para que no cambie las rutas de los contenedores. En `cmd` o PowerShell, `./mk` es `.\mk.cmd`, y `cp` es `copy`, pero las comillas de algunos comandos pueden fallar.
- **No hace falta cuenta de Kaggle** para este tutorial.

> **Cómo leer las salidas.** Son copias de lo que se vio al escribir esta wiki, recortadas con `...`. Los identificadores largos cambiarán. Al usar `docker compose run` verás además líneas como `Container ... Creating`: ignóralas.

## Paso 1. Clona el proyecto

**Sin probar:** el comando no se ejecutó para esta wiki, aunque se comprobó que el repositorio responde sin credenciales.

```bash
git clone https://github.com/AlbaAsca/lakehouse-mcp.git
cd lakehouse-mcp
```

Todos los comandos siguientes se lanzan desde esta carpeta.

## Paso 2. Crea tu configuración

```bash
cp .env.example .env
```

Abre `.env` y cambia las contraseñas si quieres. Para probar en local valen las de ejemplo. Este fichero no se sube nunca a git.

## Paso 3. Levanta todo

**Puede terminar con un error que no afecta a este tutorial:** si ves `container lakehouse-mcp-connect-init-1 exited (1)`, el registro del conector se adelantó al arranque de Connect ([T-072](04-operacion.md#connect-init-termina-con-404)). Lanza `docker compose up connect-init` y sigue.

```bash
./mk up
```

Primero construye la imagen [`tools`](componentes/imagen-tools.md), y dentro de ella ejecuta `docker compose up -d --build --wait`. Termina cuando todos los servicios están sanos.

Compruébalo:

```bash
docker compose ps -a --format 'table {{.Service}}\t{{.Status}}'
```
```text
SERVICE             STATUS
connect             Up 4 hours (healthy)
kafka               Up 4 hours (healthy)
lakehouse-init      Exited (0) 4 hours ago
polaris             Up 4 hours (healthy)
polaris-bootstrap   Exited (0) 4 hours ago
postgres            Up 4 hours (healthy)
schema-registry     Up 4 hours (healthy)
seaweedfs           Up 4 hours (healthy)
```

Lo que debes mirar: `healthy` en los servicios, y `Exited (0)` en los de un solo uso, donde 0 significa «terminó bien». Es lo normal, no un fallo.

El stack de esa salida es anterior a T-012. En el tuyo debería aparecer también `connect-init`, con `Exited (0)`. Eso no se ha podido comprobar.

Si `up` falla, ve a [Operación](04-operacion.md).

## Paso 4. Comprueba que las piezas se hablan

```bash
./mk smoke
```

Crea una tabla, escribe una fila, la lee y la borra. Termina así:

```text
schema-registry ok
iceberg sink plugin ok
...
┌───────┬─────────┐
│  id   │    v    │
│ int32 │ varchar │
├───────┼─────────┤
│     1 │ ok      │
└───────┴─────────┘
duckdb -> polaris -> seaweedfs ok
```

## Paso 5. Crea una tabla

Una tabla de eventos inventada, en un *namespace* (una especie de carpeta) llamado `wiki_demo`. El script [`lh.sh`](ejemplos/lh.sh) abre DuckDB ya conectado al catálogo con el nombre `lh`, y ejecuta el SQL que le pases.

```bash
docker compose run --rm -T tools sh docs/wiki/ejemplos/lh.sh "CREATE SCHEMA lh.wiki_demo; CREATE TABLE lh.wiki_demo.eventos (event_time TIMESTAMP, event_type VARCHAR, price DOUBLE); INSERT INTO lh.wiki_demo.eventos VALUES (TIMESTAMP '2024-03-01 10:00:00', 'view', 9.99); SELECT * FROM lh.wiki_demo.eventos;"
```
```text
┌─────────────────────┬────────────┬────────┐
│     event_time      │ event_type │ price  │
│      timestamp      │  varchar   │ double │
├─────────────────────┼────────────┼────────┤
│ 2024-03-01 10:00:00 │ view       │   9.99 │
└─────────────────────┴────────────┴────────┘
```

Acabas de escribir una tabla [Iceberg](componentes/iceberg.md). DuckDB preguntó a [Polaris](componentes/polaris.md) y guardó los datos en [SeaweedFS](componentes/seaweedfs.md). El contenedor de DuckDB ya no existe, y la tabla sigue ahí.

## Paso 6. Añade otra fila y mira las versiones

Cada escritura crea una foto de la tabla, un [snapshot](01-conceptos.md#snapshot).

```bash
docker compose run --rm -T tools sh docs/wiki/ejemplos/lh.sh "INSERT INTO lh.wiki_demo.eventos VALUES (TIMESTAMP '2024-03-01 10:05:00', 'purchase', 9.99); SELECT sequence_number, snapshot_id, operation FROM iceberg_snapshots('lh.wiki_demo.eventos') ORDER BY 1;"
```
```text
┌─────────────────┬─────────────────────┬───────────┐
│ sequence_number │     snapshot_id     │ operation │
│     uint64      │       uint64        │  varchar  │
├─────────────────┼─────────────────────┼───────────┤
│               1 │ 8026702705055517585 │ append    │
│               2 │ 7304583946546332230 │ append    │
└─────────────────┴─────────────────────┴───────────┘
```

Copia tu `snapshot_id` de la fila 1. El tuyo será distinto.

## Paso 7. Viaja al pasado

Sustituye `TU_SNAPSHOT_ID` por el número que copiaste.

```bash
docker compose run --rm -T tools sh docs/wiki/ejemplos/lh.sh "SELECT count(*) AS filas FROM lh.wiki_demo.eventos AT (VERSION => TU_SNAPSHOT_ID); SELECT count(*) AS filas_ahora FROM lh.wiki_demo.eventos;"
```
```text
┌───────┐
│ filas │
│ int64 │
├───────┤
│     1 │
└───────┘
┌─────────────┐
│ filas_ahora │
│    int64    │
├─────────────┤
│           2 │
└─────────────┘
```

La misma tabla, antes y ahora. Es lo que permitirá deshacer un arreglo que salga mal.

## Paso 8. Mira los ficheros

Un lakehouse no es magia: son ficheros.

```bash
docker compose run --rm -T tools sh docs/wiki/ejemplos/lh.sh "SELECT regexp_replace(file, '[0-9a-f-]{20,}', '…', 'g') AS fichero FROM glob('s3://lakehouse/warehouse/wiki_demo/**') ORDER BY 1;"
```
```text
┌─────────────────────────────────────────────────────────────────────┐
│                               fichero                               │
│                               varchar                               │
├─────────────────────────────────────────────────────────────────────┤
│ s3://lakehouse/warehouse/wiki_demo/eventos/data/….parquet           │
│ s3://lakehouse/warehouse/wiki_demo/eventos/data/….parquet           │
│ s3://lakehouse/warehouse/wiki_demo/eventos/metadata/snap….avro      │
│ s3://lakehouse/warehouse/wiki_demo/eventos/metadata/snap….avro      │
│ s3://lakehouse/warehouse/wiki_demo/eventos/metadata/….metadata.json │
│ s3://lakehouse/warehouse/wiki_demo/eventos/metadata/….metadata.json │
│ s3://lakehouse/warehouse/wiki_demo/eventos/metadata/….metadata.json │
│ s3://lakehouse/warehouse/wiki_demo/eventos/metadata/…m0.avro        │
│ s3://lakehouse/warehouse/wiki_demo/eventos/metadata/…m0.avro        │
└─────────────────────────────────────────────────────────────────────┘
```

Hay dos ficheros de datos, uno por `INSERT`. El resto es el índice. [Qué es cada uno](componentes/iceberg.md#cómo-funciona-por-dentro).

Puedes leer esos Parquet directamente, sin el catálogo:

```bash
docker compose run --rm -T tools sh docs/wiki/ejemplos/lh.sh "SELECT event_type, count(*) AS n FROM read_parquet('s3://lakehouse/warehouse/wiki_demo/eventos/data/*.parquet') GROUP BY 1 ORDER BY 1;"
```
```text
┌────────────┬───────┐
│ event_type │   n   │
│  varchar   │ int64 │
├────────────┼───────┤
│ purchase   │     1 │
│ view       │     1 │
└────────────┴───────┘
```

## Paso 9. Limpia

Primero la tabla y el namespace:

```bash
docker compose run --rm -T tools sh docs/wiki/ejemplos/lh.sh "DROP TABLE lh.wiki_demo.eventos; DROP SCHEMA lh.wiki_demo;"
```

Este comando no muestra nada si va bien. Pero [los ficheros siguen en S3](04-operacion.md#drop-table-deja-los-ficheros-en-s3), de modo que hay que borrarlos aparte:

```bash
docker compose exec -T seaweedfs curl -s -o /dev/null -w '%{http_code}\n' -X DELETE 'http://127.0.0.1:8888/buckets/lakehouse/warehouse/wiki_demo?recursive=true'
```
```text
204
```

Comprueba que no queda nada:

```bash
docker compose run --rm -T tools sh docs/wiki/ejemplos/lh.sh "SELECT count(*) AS quedan FROM glob('s3://lakehouse/warehouse/wiki_demo/**');"
```
```text
┌────────┐
│ quedan │
│ int64  │
├────────┤
│      0 │
└────────┘
```

## Y ahora

- Prueba Kafka: [crea un topic, escribe y lee](componentes/kafka.md#cómo-probarlo-tú-mismo).
- Rompe un contrato de datos: [Schema Registry](componentes/schema-registry.md#cómo-probarlo-tú-mismo).
- Para apagar el stack sin perder datos: `./mk down`. Para borrar también los datos: `docker compose down -v`. Ninguno de los dos se ha ejecutado para esta wiki.

## Lo que el repo no te da todavía

| Querrías | Hoy | Alternativa real |
|---|---|---|
| Cargar los datos reales | `./mk seed` no está terminado (T-013), el conector falla (T-072) y el CSV hay que descargarlo con tu cuenta de Kaggle | Crear tablas a mano, como aquí |
| Ver la tabla `ecommerce.events` | No existe hasta completar T-012, T-013 y T-015 | Tabla `wiki_demo` |
| Ver archivos S3 con un cliente estándar | `tools` no trae `aws` | `glob()` en DuckDB |
| Hablar con el agente | No existe (T-034) | Ninguna |
