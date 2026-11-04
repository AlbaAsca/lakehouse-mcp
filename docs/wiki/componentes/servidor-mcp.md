# Servidor MCP

**Estado:** 🟡 parcial. Ya es una aplicación con cuatro herramientas, `list_tables`, `describe_table`, `run_query` y `time_travel`, probadas con un cliente MCP real. Usan la [conexión con DuckDB y la lectura del catálogo](duckdb.md#dentro-del-servidor-mcp) y el [validador de SQL](#el-validador-de-sql). Es un servicio de `compose.yaml` (T-093); su imagen está probada en contenedores, pero `./mk up` con ese servicio está sin probar. Aún no tiene claves de API (T-050). Tarjetas T-021 a T-028.

[Portada](../README.md) · [Glosario](../01-conceptos.md)

## Qué es, en cristiano

El mostrador de atención. El agente de IA no entra al almacén: pide cosas en el mostrador, de una lista cerrada («dime qué tablas hay», «calcula esta métrica»). El mostrador comprueba si la petición es válida y quién la hace. Esa lista la define [MCP](../01-conceptos.md#mcp).

## Para qué estará aquí y qué pasaría sin él

Limita lo que puede hacer un modelo. Sin él, el agente necesitaría acceso directo al SQL y a las credenciales, y un error podría leer o romper cualquier cosa.

## Cómo se conectará

| Dirección | Quién | Puerto |
|---|---|---|
| Entrada | [Agente](agente.md), Claude Desktop | HTTP 8090, ruta `/mcp`. Desde la red del compose, `mcp-server:8090`; desde tu ordenador, `localhost:8090` |
| Salida | [DuckDB](duckdb.md), embebido: una instancia de lectura y otra de reparación | mismo proceso |
| Salida | [Polaris](polaris.md), a través de DuckDB | 8181 |
| Salida | [Postgres](postgres.md), base `ops` | 5432 |
| Salida | [Kafka](kafka.md), para medir lag | 9092 |

## Cómo funcionará por dentro

Las herramientas previstas, del [brief](../../brief.md#servidor-mcp-tools):

| Herramienta | Qué hará | Tarjeta |
|---|---|---|
| `list_tables`, `describe_table` | Hecho: tablas, esquema, particiones y snapshots | T-020 |
| `run_query` | Hecho: solo `SELECT`, validado y con límite de filas | T-019, T-021 |
| `time_travel` | Hecho: consulta sobre un snapshot anterior. Aún sin enmascarado para analyst | T-021, T-054 |
| `pipeline_health` | Lag, frescura y último snapshot | T-022 |
| `data_quality_check` | Nulos, duplicados, rangos y volumen | T-025 |
| `propose_fix`, `apply_fix` | Propone y aplica un arreglo solo con aprobación humana | T-026 |
| `get_metric` | Métricas de la [capa semántica](capa-semantica.md) | T-033 |

Reglas de diseño, en [`docs/architecture.md`](../../architecture.md#decisiones): validar SQL con el parser de DuckDB (7), arreglos de catálogo cerrado cuya aprobación llega por `POST /fix-proposals/{id}/approvals` con una credencial que el modelo nunca recibe (9), roles por clave de API (11), enmascarado de `user_id` por vistas (12), dos instancias de DuckDB (20) y `time_travel` como única vía al pasado, también para analyst (21).

### Cómo responde una herramienta

Es el patrón que seguirán las demás (`CatalogTools` y `ToolResults`):

- **Se declara con `@McpTool`** y se sirve por Streamable HTTP. Solo se ofrecen herramientas: ni recursos, ni prompts, ni autocompletado.
- **La respuesta es JSON como texto.** Spring AI tiene además `structuredContent`, pero su cliente solo pasa `content` al modelo: con la otra forma el [agente](agente.md) recibiría una respuesta vacía. Los nombres de los campos son el contrato.
- **Un error es una respuesta que el modelo puede leer**, con `isError`, no un fallo de la llamada. Si no existe la tabla, lo dice con su nombre; si falta un argumento, lo dice el SDK al comprobar el esquema de entrada. Si falla el catálogo, el detalle va al log y el modelo solo sabe que el problema está en el servidor.
- **`describe_table` da los 20 snapshots más recientes**, del más antiguo al más nuevo, y `snapshotCount` dice cuántos hay. El conector confirma cada 30 s, así que una tabla puede guardar miles.

### Leer datos: `run_query` y `time_travel`

Las dos contestan `columns`, `rows` y `truncated`, que avisa de que había más filas que el límite. `maxRows` va de 1 a 1.000 y, si no se indica, vale 100. Las dos pasan por el validador y por la instancia de DuckDB que no puede escribir.

- **`run_query(sql, maxRows)`** lee los datos de hoy. Las tablas se escriben enteras, `lh.<namespace>.<tabla>`, y solo valen las que existen en ese momento.
- **`time_travel(namespace, table, sql, snapshotId o instant, maxRows)`** lee el pasado. El modelo elige tabla y versión, y su `SELECT` solo puede leer de `at_snapshot`, una vista que monta el servidor. La respuesta añade `snapshotId` y `committedAt`, para saber qué versión se leyó.
- **Un instante lleva zona obligatoria** (`2026-03-01T10:00:00Z`) y se convierte en el último snapshot confirmado antes o en ese momento. El servidor siempre consulta por id: `AT (TIMESTAMP => ...)` no se usa porque su zona horaria está sin verificar.
- **La vista vive en una sola conexión**, reservada durante toda la llamada y borrada al terminar, así que varias llamadas a la vez no se mezclan. El id se comprueba contra los snapshots de esa tabla: el de otra tabla da «No snapshot».
- **Un snapshot con un esquema anterior responde con las columnas de entonces**: pedir una columna añadida después da error. Comprobado con ficheros escritos por DuckDB; con ficheros del sink, sin probar.
- **Los errores del SQL llegan al modelo solo si puede arreglarlos**: lo que rechaza el validador, y los `Binder Error`, `Conversion Error` y `Out of Range Error` de DuckDB. Cualquier otro fallo puede llevar rutas del almacén, así que va al log.
- **Las celdas se devuelven para que no cambien según la máquina.** Una fecha sin zona sale como texto tal cual, y los enteros grandes no pierden dígitos. Las listas y estructuras salen como texto.

Por ahora, sin claves de API, ambas leen las tablas base con `user_id` sin enmascarar. Eso cambia con los roles (T-050, T-054).

### El validador de SQL

`SqlGuard` es el portero de `run_query` y `time_travel`: le pide a DuckDB que analice la consulta, sin ejecutarla, y recorre el resultado entero. Solo deja pasar lo que está en sus listas, así que lo que no conoce se rechaza.

| Regla | Qué rechaza | Ejemplo |
|---|---|---|
| `NOT_A_SELECT` | Lo que no es una lectura | `DROP`, `COPY`, `SET` |
| `NOT_ONE_STATEMENT` | Cero sentencias o varias | `SELECT 1; SELECT 2` |
| `NODE` | Funciones de tabla, `DESCRIBE`, `PIVOT`, CTE recursivas, parámetros y lambdas | `read_csv('/etc/passwd')` |
| `TABLE` | Tablas que no están en la lista que recibe, también rutas | `FROM '/etc/passwd'`, `duckdb_views` |
| `FUNCTION` | Funciones fuera de la lista `SqlGuard.FUNCTIONS` | `getenv('HOME')` |
| `TIME_TRAVEL` | `AT (...)`: el pasado solo se consulta con `time_travel` | `t AT (VERSION => 1)` |
| `SYNTAX` | Lo que DuckDB no entiende, y lo que pasa de 4.000 caracteres | `SELECT FROM` |

Seis cosas que conviene saber:

- **La longitud se comprueba antes de preguntar a DuckDB.** Un anidamiento excesivo no da un error: desborda la pila y tumba el proceso entero. Comprobado con el peor caso, `FROM a,a,a...`: con la pila de 1 MB de la JVM aguanta 16.000 caracteres y cae en 32.000; con una de 256 KB, aguanta 6.000 y cae en 8.000. Por eso el límite se cuenta en Java y es de 4.000.
- **La profundidad también tiene límite, porque ejecutar gasta más pila que analizar.** Esas cifras solo cubren el análisis. Al ejecutarla de verdad, una consulta aceptada con 400 tablas en el `FROM` tumbaba el proceso con la pila de 1 MB. Por eso el árbol de la consulta no puede pasar de 80 niveles (`MAX_DEPTH`): caben unas 70 tablas unidas, 35 `EXCEPT` o 23 subconsultas, funciones u operadores encadenados. Lo que más pila gasta son las cadenas de `EXCEPT` e `INTERSECT`: con 100 niveles caían con 512 KB y aguantaban con 768 KB. Con 80, todas las formas medidas aguantan con 384 KB, así que la pila de 1 MB deja más de 2,5 veces de margen; el test las ejecuta con 512 KB, cada tanda en su propia JVM para que una caída no se lleve por delante toda la pasada. Cada referencia a una CTE suma la profundidad de su cuerpo, de modo que caben 13 CTE simples en cadena; varias CTE pequeñas no pueden sumar lo que no cabría en una sola. Pasarse da el error «The query is too complex to validate».
- **Hay funciones de texto que se saltan el límite de memoria.** `lpad`, `rpad`, `replace` y `regexp_replace` construyen una celda de cualquier tamaño: `replace` anidado llegó a 4,8 GB con `memory_limit` de 256 MB, aunque solo se pidiera su longitud. Por eso no están en la lista. `||` sí se frena: da un error de memoria, pero no a tiempo: con `memory_limit` de 256 MB, el proceso llegó a ocupar 5 GB antes (riesgo abierto, T-117).

- **Una CTE no protege su propio nombre.** Comprobado: en `WITH t AS (SELECT * FROM t) SELECT * FROM t`, el `t` de dentro es la tabla real, y podría ser una ruta. Por eso cada CTE solo ve las anteriores a ella. Los nombres de CTE se comparan distinguiendo mayúsculas: `WITH A ... FROM a` se rechaza aunque sea inofensivo.
- **Lo que se ejecuta no es el texto original.** La consulta se reconstruye a partir del árbol validado, se envuelve con el límite de filas y se valida otra vez. Pide una fila más que el límite para saber si hubo recorte (`truncated`). Si una consulta necesita ampliar la lista, el error dice qué función falta.
- **Sigue siendo la única barrera ante los ficheros.** Mientras no se haga T-115, un fallo aquí daría acceso a los ficheros del contenedor.

## Cómo está configurado

Dependencias en el `pom.xml`: `spring-ai-starter-mcp-server-webmvc`, `duckdb_jdbc`, Actuator y Spring Security. Spring AI 2.0.2.

Variables de `mcp-server/src/main/resources/application.yaml`:

| Variable | Por defecto |
|---|---|
| `POLARIS_CLIENT_ID`, `POLARIS_CLIENT_SECRET`, `S3_ACCESS_KEY`, `S3_SECRET_KEY`, `S3_REGION` | Ninguno: son obligatorias |
| `POLARIS_CATALOG`, `POLARIS_URI`, `S3_ENDPOINT` | `lakehouse`, `http://polaris:8181`, `seaweedfs:8333` |
| `MCP_SERVER_ADDRESS`, `MCP_SERVER_PORT` | `127.0.0.1`, `8090` |
| `DUCKDB_EXTENSION_DIRECTORY` | Vacío: el directorio de DuckDB, descargando las extensiones. La imagen pone `/opt/duckdb-extensions`: solo carga de ahí y no descarga nada |

**Hoy no pide autenticación.** Spring Security está abierto a propósito hasta las claves de T-050. Por eso, fuera de un contenedor, escucha solo en `127.0.0.1`. El compose pone `MCP_SERVER_ADDRESS=0.0.0.0` para que lleguen otros servicios, así que lo alcanza cualquier contenedor de su red, pero publica el puerto solo en el `127.0.0.1` de tu ordenador: desde otra máquina no se llega.

Sin la conexión con el catálogo el servidor no arranca, porque ninguna herramienta podría contestar (lo comprueba `McpServerEdgeIT`). **Si falta una variable obligatoria, hoy no avisa** (T-154): usa como valor el texto `${VARIABLE}`. Sin las de `S3_*` arranca y contesta sobre el catálogo; leer datos está sin probar en ese estado.

### La imagen

[`infra/mcp-server/Dockerfile`](../../../infra/mcp-server/Dockerfile) compila el servidor dentro de Docker, con la raíz del repositorio como contexto. Un `.dockerignore` en la raíz deja fuera `.env`, `.git` y `target/`.

- **Las extensiones de DuckDB vienen dentro.** Las instala al construir `PreloadExtensions.java`, con el mismo driver que usa el servidor, para que coincida la versión. Arrancar no descarga nada.
- **La salud la mira la propia imagen**: `HEALTHCHECK` contra `/actuator/health`. Solo mira que el proceso conteste: no comprueba el catálogo después de arrancar (T-158 propone cambiarlo si se ve útil).
- **Memoria.** `-Xmx256m` de los 1 GB del [presupuesto](../../architecture.md#memoria-docker-76-gb); DuckDB va fuera del heap, con `memory_limit` de 512 MB. El reparto prevé 400 MB para lectura: no se ha cambiado. El compose no pone límite al contenedor; el test sí lo arranca limitado a 1 GB, con una carga mínima, y aguanta. Bajo carga real no se ha medido.

## Cómo probarlo tú mismo

Con el stack levantado (**sin probar**: `./mk up` con este servicio aún no se ha ejecutado), `localhost:8090/mcp` es la dirección para un cliente MCP y la salud está en:

```bash
curl http://localhost:8090/actuator/health
```

El test de la imagen la construye, la arranca como el compose (en contenedores, sin el resto del stack) y comprueba salud, herramientas y una consulta:

```bash
./mk test MODULE=mcp-server MAVEN_ARGS="-Dit.test=McpServerImageIT -Dtest=McpServerApplicationTest -Dsurefire.failIfNoSpecifiedTests=false"
```

El test de la aplicación levanta el catálogo en contenedores, arranca la aplicación entera y le habla con un cliente MCP real:

```bash
./mk test MODULE=mcp-server MAVEN_ARGS="-Dit.test=McpServerIT -Dtest=CatalogToolsTest -Dsurefire.failIfNoSpecifiedTests=false"
```

Para `run_query` y `time_travel`:

```bash
./mk test MODULE=mcp-server MAVEN_ARGS="-Dit.test=QueryToolsIT -Dtest=QueryToolsTest -Dsurefire.failIfNoSpecifiedTests=false"
```

## Qué puede fallar

Riesgos de diseño conocidos, no observados:

- **Un fallo del validador.** La instancia `READ_ONLY` protege el catálogo, pero el validador seguiría siendo la única barrera entre una consulta y los ficheros del contenedor o de S3. [`NOTAS-investigador.md`](../../NOTAS-investigador.md) propone una segunda barrera con los ajustes de seguridad de DuckDB (T-115).
- **DuckDB sobre ficheros del sink.** [T-014](../../spike-duckdb-iceberg.md) lo comprobó con ficheros escritos por DuckDB; sobre los del sink falta probarlo.

## Para saber más

- [Model Context Protocol](https://modelcontextprotocol.io/docs/getting-started/intro)
- [Servidor MCP en Spring AI](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html)
