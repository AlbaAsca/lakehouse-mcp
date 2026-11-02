# Arquitectura

Alcance en `docs/brief.md`. Aquí solo las decisiones y por qué.

```
CSV -> producer -> Kafka + Schema Registry -> Iceberg sink (Connect) -> SeaweedFS (S3)
                                                    |
                                     Polaris (catálogo REST) -> Postgres
                                                    |
agente Spring AI / Claude Desktop -> MCP -> mcp-server -> DuckDB lectura (READ_ONLY) + DuckDB reparación + Postgres ops
```

## Decisiones

| # | Decisión | Descartado y por qué |
|---|----------|----------------------|
| 1 | **Catálogo Polaris en Postgres** (`relational-jdbc`, 1.8.0). Un servicio de un solo uso con `apache/polaris-admin-tool:1.8.0` (misma versión que el servidor) hace el bootstrap. El esquema `polaris_schema` debe existir antes: lo crea un SQL dentro de una imagen propia de Postgres. | Memoria: pierde el catálogo al reiniciar (404). H2 en fichero: no es lo que se usa en producción. MongoDB: otro servicio sin ventaja. Volver a crear el catálogo al arrancar: oculta el problema y pierde las tablas registradas. |
| 2 | **SeaweedFS sin STS.** Polaris con `stsUnavailable:true`; DuckDB con `ACCESS_DELEGATION_MODE 'none'` y secreto S3 propio; Connect y Trino con claves estáticas. | MinIO: repositorio archivado. Credenciales vendidas: requieren STS. |
| 3 | **Ingesta con el sink oficial de Iceberg en Connect**, con commits cada ~30 s y la evolución automática de esquema apagada: así el *schema drift* es un fallo detectable y reparable. | Flink o Spark Streaming: no caben en 7,6 GB y no aportan nada a la demo. |
| 4 | **DuckDB embebido en `mcp-server`** para todo el SQL. | Trino para todo: necesita varios GB encendidos siempre. |
| 5 | **Todo el catálogo por DuckDB, sin Iceberg Java API en `mcp-server`.** Snapshots con `iceberg_snapshots`; rollback con `CALL iceberg_rollback_to_snapshot`; añadir columna con `ALTER TABLE`, todo comprobado en T-014. Las particiones, con `iceberg_partition_specs`: documentada, sin probar aquí. Si no existe en esta versión, se lee `GET .../tables/{t}` de Polaris por HTTP (T-017). Time travel con `AT (VERSION => id)`. | API Java: la razón era que la documentación no cubría estas operaciones, y ya no es cierto. Añade `iceberg-core`, `iceberg-aws` y posibles conflictos con Spring Boot 4 sin aportar nada aquí. `snapshot_from_id`: exige una función de tabla que el validador prohíbe. `AT (TIMESTAMP => ...)`: su zona horaria está sin verificar. |
| 6 | **Compactar con DuckDB** (`CALL iceberg_rewrite_data_files`, con `min_input_files` explícito), desde la instancia de reparación. **Expirar snapshots: según T-087**, porque DuckDB no tiene función. Si Trino se queda por el benchmark, `expire_snapshots` con Trino. Si se va, un proceso de un solo uso con la API Java de Iceberg, fuera de `mcp-server` (T-105). | Compactar con Trino: 2 GB para algo que DuckDB ya hace, y sin verificar que lea los borrados de DuckDB. Spark: otra imagen pesada. Compactación propia: reimplementa algo delicado. Expirar con `remove-snapshots` del REST de Iceberg: la [API Java](https://iceberg.apache.org/javadoc/1.2.0/org/apache/iceberg/ExpireSnapshots.html) borra los ficheros en el cliente, y no se ha comprobado que el catálogo lo haga; se corre el riesgo de dejar ficheros huérfanos. |
| 7 | **Validar SQL con el parser de DuckDB** (`json_serialize_sql`), con [seis reglas](spike-duckdb-iceberg.md#6-json_serialize_sql-como-validador): una sentencia; recorrer todo el árbol; solo tipos de `from_table` y tablas permitidos, porque una ruta como `'/etc/passwd'` llega como `BASE_TABLE`; una CTE solo vale en su ámbito y se valida su cuerpo; **lista de funciones permitidas**; `at_clause` rechazado. Lo que falte en la lista da un error claro y se añade. | Regex: se esquiva. JSqlParser: otro dialecto, puede discrepar del motor real. Lista de funciones prohibidas: no se sabe qué otras exponen datos del proceso. |
| 8 | **Métricas en YAML dentro de `semantic/`**, biblioteca que usa `mcp-server`. | dbt o Cube: un servicio y un flujo de más para cuatro métricas. SQL improvisado por el modelo: no es reproducible. |
| 9 | **Arreglos de catálogo cerrado.** `propose_fix` guarda acción, parámetros y efecto esperado. La aprobación entra por `POST /fix-proposals/{id}/approvals`, con credencial de admin que el modelo nunca recibe. `apply_fix` rechaza lo no aprobado. | SQL libre del modelo: indefendible. Pedir confirmación al modelo: se la puede dar él solo. |
| 10 | **Postgres también para propuestas y auditoría** (base `ops`, separada de Polaris): ya está en el stack y es transaccional. | Tablas Iceberg: commit lento, sin transacciones entre filas. Ficheros: no hay bind mounts. |
| 11 | **RBAC con dos claves de API** (analyst, admin) por `.env` y Spring Security. | OAuth con Keycloak: RAM y complejidad para una demo local. |
| 12 | **Enmascarado por vistas en `memory.main`.** Analyst solo ve vistas con `user_id` en hash con sal, y el validador le prohíbe las tablas base. Se puede contar y agrupar por usuario sin ver el valor real. Se crean una vez por instancia y las ven todas las conexiones del pool. | Reescribir la salida: se esquiva con `user_id + 0` o un `CAST`. Vista `TEMP`: solo existe en su conexión. Vista en el catálogo: guardaría allí la sal. |
| 13 | **Avro con compatibilidad BACKWARD.** Contratos en `contracts/`; test sin contenedor que rompe el build y test contra Registry real. | JSON Schema: peor encaje con Connect. Solo el Registry: el fallo llegaría en despliegue, no en build. |
| 14 | **Muestreo por `user_session`**, no por fila: un hash determinista mantiene sesiones completas. Con `max-rows`, una primera pasada cuenta filas por cubeta del hash y se toman las cubetas que caben: nunca se pasa del límite y cubre todo el mes. | Por fila: rompe el embudo view→cart→purchase y falsea las métricas. Cortar en las N primeras filas: solo cubre el principio del mes y deja sesiones a medias. |
| 15 | **Muestra de CI sintética**: generador Python solo con biblioteca estándar, semilla fija, valores inventados y fichero `.csv.gz` versionado. Incluye casos sucios y una anomalía plantada. | Extracto real: lo prohíbe la licencia. Generar con Java al vuelo: el tester no podría calcular la verdad sin usar el código del proyecto. |
| 16 | **Puntuación determinista de evals** (número con tolerancia, conjunto esperado, palabras clave en diagnóstico). | Modelo como juez: caro y no reproducible. |
| 17 | **Coste de evals acotado.** PR: sin llamadas a la API, modelo simulado. Subconjunto de ~8 preguntas con modelo barato: cron semanal y manual. Conjunto completo: a mano antes de publicar. Tope de iteraciones, tokens y gasto por ejecución. | Evals en cada push: gasto continuo y PR ajenos sin secret. |
| 18 | **Orden temporal con ventana acotada** (`EventReplay`, 100 000 eventos ya muestreados). Aún no hay `docs/data-profile.md` (T-007), así que no se da por hecho que el CSV esté ordenado. Un evento que llega más tarde de lo que cubre la ventana se entrega sin esperar y se cuenta. | Ordenar todo en memoria: el mes no cabe. Ordenar en disco: pasos y espacio de más sin saber si hace falta. Confiar en el orden del fichero: sin el perfil es una suposición. |
| 19 | **`rewrite-timestamp` es un desplazamiento fijo**: el primer evento es «ahora» y los demás conservan su separación original, también con aceleración. Los días siguen siendo días y el particionado y las métricas diarias no cambian. Con aceleración mayor que 1, los eventos posteriores llevan horas por delante del reloj. | Comprimir los timestamps con la aceleración: la hora coincidiría con la de envío, pero el mes cabría en unas horas y una sola partición diaria. |
| 20 | **Dos instancias de DuckDB en `mcp-server`.** La de lectura solo tiene `lh` con `READ_ONLY`, atiende todo lo que sale del modelo y, sin validador, también rechaza el `SELECT` de rollback. La de reparación es escribible, ejecuta solo plantillas de `FixAction` y mantenimiento con parámetros, nunca SQL del modelo, y se abre al usarla y se cierra al terminar. | Una instancia con dos catálogos: bastaría nombrar el escribible para llegar a él, y el validador volvería a ser la única barrera. Solo validador: un `SELECT` de rollback ya cambió una tabla en T-014. |
| 21 | **`time_travel` es la única vía al pasado, para los dos roles.** `time_travel(table, snapshot_id o instante, sql)`: el servidor comprueba la tabla, crea `CREATE OR REPLACE TEMP VIEW at_snapshot AS SELECT <proyección del rol> FROM lh.ns.t AT (VERSION => id)` y ejecuta `sql` con el validador, donde `at_snapshot` es la única tabla permitida. La proyección de analyst usa la misma expresión de hash que su vista. Un instante con zona obligatoria se convierte en id: último snapshot con `timestamp_ms` anterior o igual. Los id de snapshot los da `describe_table`. | `AT` escrito por el modelo: a través de la vista falla y, sobre la tabla base, saltaría el enmascarado. Sin time travel para analyst: el brief lo pide como herramienta y no hace falta renunciar. Sombrear el nombre de la tabla con la vista temporal: precedencia sin comprobar. |

Los endpoints HTTP propios siguen HATEOAS, en plural y kebab-case.

## Recorrido mínimo: la primera demo

**Estado: planificado, nada de esto funciona aún de punta a punta** (T-086). Objetivo: que el repo público enseñe pronto al agente contestando una pregunta con datos de Iceberg.

```
./mk up -> ./mk seed-sample -> Kafka -> Connect -> tabla Iceberg
./mk ask Q="..." -> agente -> MCP (run_query validado) -> DuckDB -> tabla Iceberg
```

Pregunta de la demo: las cinco marcas con más ingresos por compras. Se contrasta con un cálculo independiente sobre el CSV (T-092).

| Decisión | Descartado y por qué |
|----------|----------------------|
| **Datos sintéticos en la primera demo.** Quien clone el repo la repite sin Kaggle, gasta menos memoria y evita dudas con la licencia. El GIF lo dice claramente. | Mes real: añade T-018 y una cuenta de Kaggle, y solo cambia los nombres de las marcas. Sigue en la demo completa. |
| **Ingesta real por Kafka y Connect**, aunque sea lo más incierto. | `SyntheticLoader` directo a Iceberg: más rápido, pero enseñaría un camino que no es el del diagrama. Sí se usa en los tests de consulta. |
| **Primera versión del agente solo con `run_query`** (T-094). | Esperar a `get_metric`: 4 tarjetas más de capa semántica (T-027, T-029, T-031, T-033). Decisión 8 aplazada, no anulada: T-034 la añade después. |
| **Servidor MCP en el compose en cuanto consulta** (T-093). | Esperar a T-028 tal como estaba: arrastraba T-022, T-023, T-024, T-025 y T-026, ninguna necesaria aquí. |
| **Validador SQL dentro del recorrido** (T-019). | Quitarlo para ir más rápido: un modelo con SQL libre en un repo público contradice el mensaje del proyecto. |
| **Tope de iteraciones desde la primera versión** (T-094); tokens y gasto siguen en T-035. | Dejarlo todo para T-035: la primera ejecución real gastaría sin freno. Además, T-032 pide una clave con límite de gasto. |

La pregunta «¿por qué cayeron las compras?» y el self-healing quedan fuera: siguen siendo el objetivo de la demo completa (T-036, T-042, T-046, T-059).

### Orden

Las ramas A y B no se esperan: los tests de consulta cargan la muestra sin Kafka.

| Tramo | Tarjetas | Dueño |
|-------|----------|-------|
| A. Ingesta | T-090 (decidir permiso) → T-072 → T-012; T-012 + T-013 → T-015; T-072 → T-089 en el stack real | responsable y desarrollador |
| B. Consulta y MCP | T-014 → T-016 → T-106 y T-019; T-016 → T-017; T-016 + T-017 → T-020; T-106 + T-019 + T-020 → T-021 | investigador y desarrollador |
| C. Agente | T-021 → T-093 y T-094 → T-096 | desarrollador |
| D. Comando y referencia | T-091 y T-092 (independientes) | desarrollador y tester |
| E. Demo | T-032 (clave) → T-097 (probar y grabar, [guion](demo-grabacion.md)) → T-098 (README) | responsable y arquitecto |

Estimación: ~390 min de agente en las tarjetas automáticas (T-014, T-015, T-016, T-017, T-019, T-020, T-021, T-091 a T-094, T-096) y ~25 min de la responsable, sin contar T-089 ni lo que quede de T-072, T-012 y T-013. Los tiempos reales hasta ahora van muy por debajo de lo estimado, pero T-012 terminó en 14 min y su conector no funcionaba: la integración es donde puede desviarse.

### Cómo aplica el panel este orden

El runner toma Revisión → Próximo → Backlog y, dentro de cada estado, prioridad e id. El recorrido está en Próximo, así que va antes que el resto de Backlog. `board.py` no cambia prioridad, título ni dependencias de una tarjeta ya creada, pero el panel sí. Propuestas para el consultor:

1. **T-017 a prioridad alta.** Con media, va detrás de todas las alta de Próximo y retiene T-020.
2. **Títulos:** T-034 → «Agente: usar las métricas para preguntas de negocio»; T-028 → «MCP: configuración para Claude Desktop».
3. **Decidir el orden frente a las tarjetas del runner.** Por id, T-015, T-016 y T-019 se adelantan a T-073, T-077, T-080, T-082, T-084 y T-085. Si T-073 (código sin subir) o T-085 (comandos destructivos) deben ir antes, conviene lanzarlas primero.
4. **Opcional, T-055 con dependencia de T-097 en lugar de T-046**, para tener el README en inglés con la primera demo.
5. **Dependencias por las decisiones 5, 6 y 20 (T-101):** T-017 pasa a depender de T-016; T-021, también de T-106; T-047 deja de depender de T-044 y pasa a depender de T-016. **Título:** T-047 → «Mantenimiento: compactar ficheros con DuckDB».

### Qué se puede recortar si falta cupo

Propuesta en orden; decide la responsable. Quitar una tarjeta exige retirarla también de las dependencias de T-046 y T-064 en el panel.

| Orden | Recorte | Qué se pierde |
|-------|---------|---------------|
| 1 | Benchmark: T-049, T-052 y T-044 (decisión en T-087). Compactar ya no usa Trino; expirar pasa a T-105 con la API Java | Una tabla del README y un punto del brief |
| 2 | Claude Desktop: T-028, T-030 | Un segundo cliente; el agente ya demuestra MCP |
| 3 | Pages: T-058, T-062 | Repite lo que ya está en el README y la wiki |
| 4 | Mantenimiento: T-047 y T-105 | Compactación y expiración. Conviene conservar T-045 (añadir una columna), que es barata y vistosa |
| 5 | Chaos: T-039 y T-041 | Se queda en dos fallos de cuatro (duplicados y consumidor caído) |
| 6 | Métricas de herramientas: T-024 | Observabilidad, invisible en la demo |

**No recortar:** el validador (T-019), la aprobación humana (T-026) ni una parte de las evals con referencia independiente (T-048, T-056). Son lo que distingue este proyecto de otra demo de un agente con SQL.

## Pruebas: qué puede verificar cada quién

Los agentes solo ejecutan `./mk test|test-board|evals`, `python`, git de lectura y `docker compose ps|logs`. `./mvnw` no sirve en el host porque no hay JDK.

- **Lo normal:** Testcontainers dentro de `./mk test`, con un kit común (módulo nuevo `testkit`) que levanta imágenes construidas desde `infra/`.
- **Tiempo:** `./mk test` no admite filtros, así que toda la suite debe quedarse en ~10 min. Tests unitarios con surefire, integración con failsafe, contenedores compartidos por módulo.
- **Memoria:** ejecutar `./mk test` con el stack apagado si el test levanta Connect o Trino.
- **Stack completo (`up`, `seed`, `chaos`, `bench`, `demo`):** tarjetas `--manual` de dueño `consultor`, que el runner no ejecuta.

## Memoria (Docker: ~7,6 GB)

| Servicio | Límite aprox. |
|----------|---------------|
| Kafka | 0,8 GB |
| Schema Registry | 0,3 GB |
| SeaweedFS | 0,3 GB |
| Postgres | 0,2 GB |
| Polaris | 0,6 GB |
| Connect | 1,2 GB |
| mcp-server | 1 GB |
| **Total siempre encendido** | **~4,4 GB** |
| Agente o `tools` (a demanda) | 1 GB |
| Trino (solo perfil `bench`) | 2 GB |

Con todo encendido queda ~0,2 GB de margen: durante `bench` conviene parar Connect.

Reparto inicial del 1 GB de `mcp-server`, sin medir: JVM 256 MB, DuckDB de lectura 400 MB y DuckDB de reparación 256 MB, esta solo mientras está abierta.

## Riesgos abiertos

- **DuckDB sobre lo que escribe el sink.** T-014 lo [comprobó](spike-duckdb-iceberg.md) con ficheros escritos por DuckDB. Sin verificar: borrar, hacer rollback, añadir columna y compactar sobre ficheros del sink, o mientras el sink hace commit (T-038, T-039, T-041, T-047).
- **El time travel de la decisión 21.** Cada pieza está comprobada, pero no juntas: vista temporal con `AT`, catálogo `READ_ONLY` y hash, ni con un esquema antiguo. Lo decide un test de T-021. Si falla, analyst se queda sin `time_travel`, con un error claro, y se anota aquí.
- **`READ_ONLY` protege el catálogo, no S3.** La instancia de lectura lleva la misma clave S3 de la decisión 2. Lo que impide leer o escribir ficheros directamente es el validador, hasta que se apliquen los ajustes de seguridad de DuckDB propuestos en [`NOTAS-investigador.md`](NOTAS-investigador.md), que ahora pueden limitarse a esta instancia.
- **Compactar con volumen real:** sin medir. Con 256 MB puede quedarse corta (T-047).
- **Bootstrap repetido:** la documentación de 1.8.0 dice que es idempotente, pero no indica el código de salida. Si no es 0, `./mk up` fallaría la segunda vez.
- **Propiedades de 1.8.0:** la página de JDBC mezcla `quarkus.datasource.username` y `quarkus.datasource.jdbc.username`. Se usa la forma del Admin Tool y el test decide.
- **Imagen de Connect:** se compila desde git y tarda varios minutos la primera vez; cuenta para el tiempo de `./mk test`.
