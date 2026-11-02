# Arquitectura

Alcance en `docs/brief.md`. Aquí solo las decisiones y por qué.

```
CSV -> producer -> Kafka + Schema Registry -> Iceberg sink (Connect) -> SeaweedFS (S3)
                                                    |
                                     Polaris (catálogo REST) -> Postgres
                                                    |
agente Spring AI / Claude Desktop -> MCP -> mcp-server -> DuckDB (SQL) + Iceberg API (catálogo) + Postgres ops
```

## Decisiones

| # | Decisión | Descartado y por qué |
|---|----------|----------------------|
| 1 | **Catálogo Polaris en Postgres** (`relational-jdbc`, 1.8.0). Un servicio de un solo uso con `apache/polaris-admin-tool:1.8.0` (misma versión que el servidor) hace el bootstrap. El esquema `polaris_schema` debe existir antes: lo crea un SQL dentro de una imagen propia de Postgres. | Memoria: pierde el catálogo al reiniciar (404). H2 en fichero: no es lo que se usa en producción. MongoDB: otro servicio sin ventaja. Volver a crear el catálogo al arrancar: oculta el problema y pierde las tablas registradas. |
| 2 | **SeaweedFS sin STS.** Polaris con `stsUnavailable:true`; DuckDB con `ACCESS_DELEGATION_MODE 'none'` y secreto S3 propio; Connect y Trino con claves estáticas. | MinIO: repositorio archivado. Credenciales vendidas: requieren STS. |
| 3 | **Ingesta con el sink oficial de Iceberg en Connect**, con commits cada ~30 s y la evolución automática de esquema apagada: así el *schema drift* es un fallo detectable y reparable. | Flink o Spark Streaming: no caben en 7,6 GB y no aportan nada a la demo. |
| 4 | **DuckDB embebido en `mcp-server`** para todo el SQL. | Trino para todo: necesita varios GB encendidos siempre. |
| 5 | **Iceberg Java API para operaciones de catálogo** (esquema, snapshots, rollback, añadir columna). DuckDB solo lee y borra filas; time travel con `snapshot_from_id`. | Todo por DuckDB: la documentación no cubre rollback, expiración ni compactación. |
| 6 | **Compactación y expiración con Trino** (`ALTER TABLE ... EXECUTE optimize / expire_snapshots`), encendido a demanda en el perfil `bench`. Reutiliza lo que ya hay para el benchmark. | Spark: otra imagen pesada para una tarea. Compactación propia con la API de Iceberg: reimplementa algo delicado, sobre todo con ficheros de borrado posicional, que es lo que escribe DuckDB. |
| 7 | **Validar SQL con el parser de DuckDB** (`json_serialize_sql`): solo SELECT, una sentencia, solo tablas conocidas y ninguna función de tabla (`read_csv`, `glob`...). | Regex: se esquiva. JSqlParser: otro dialecto, puede discrepar del motor real. |
| 8 | **Métricas en YAML dentro de `semantic/`**, biblioteca que usa `mcp-server`. | dbt o Cube: un servicio y un flujo de más para cuatro métricas. SQL improvisado por el modelo: no es reproducible. |
| 9 | **Arreglos de catálogo cerrado.** `propose_fix` guarda acción, parámetros y efecto esperado. La aprobación entra por `POST /fix-proposals/{id}/approvals`, con credencial de admin que el modelo nunca recibe. `apply_fix` rechaza lo no aprobado. | SQL libre del modelo: indefendible. Pedir confirmación al modelo: se la puede dar él solo. |
| 10 | **Postgres también para propuestas y auditoría** (base `ops`, separada de Polaris): ya está en el stack y es transaccional. | Tablas Iceberg: commit lento, sin transacciones entre filas. Ficheros: no hay bind mounts. |
| 11 | **RBAC con dos claves de API** (analyst, admin) por `.env` y Spring Security. | OAuth con Keycloak: RAM y complejidad para una demo local. |
| 12 | **Enmascarado por vistas.** Analyst solo ve vistas con `user_id` en hash con sal, y el validador le prohíbe las tablas base. Se puede contar y agrupar por usuario sin ver el valor real. | Reescribir la salida: se esquiva con `user_id + 0` o un `CAST`. |
| 13 | **Avro con compatibilidad BACKWARD.** Contratos en `contracts/`; test sin contenedor que rompe el build y test contra Registry real. | JSON Schema: peor encaje con Connect. Solo el Registry: el fallo llegaría en despliegue, no en build. |
| 14 | **Muestreo por `user_session`**, no por fila: un hash determinista mantiene sesiones completas. Con `max-rows`, una primera pasada cuenta filas por cubeta del hash y se toman las cubetas que caben: nunca se pasa del límite y cubre todo el mes. | Por fila: rompe el embudo view→cart→purchase y falsea las métricas. Cortar en las N primeras filas: solo cubre el principio del mes y deja sesiones a medias. |
| 15 | **Muestra de CI sintética**: generador Python solo con biblioteca estándar, semilla fija, valores inventados y fichero `.csv.gz` versionado. Incluye casos sucios y una anomalía plantada. | Extracto real: lo prohíbe la licencia. Generar con Java al vuelo: el tester no podría calcular la verdad sin usar el código del proyecto. |
| 16 | **Puntuación determinista de evals** (número con tolerancia, conjunto esperado, palabras clave en diagnóstico). | Modelo como juez: caro y no reproducible. |
| 17 | **Coste de evals acotado.** PR: sin llamadas a la API, modelo simulado. Subconjunto de ~8 preguntas con modelo barato: cron semanal y manual. Conjunto completo: a mano antes de publicar. Tope de iteraciones, tokens y gasto por ejecución. | Evals en cada push: gasto continuo y PR ajenos sin secret. |
| 18 | **Orden temporal con ventana acotada** (`EventReplay`, 100 000 eventos ya muestreados). Aún no hay `docs/data-profile.md` (T-007), así que no se da por hecho que el CSV esté ordenado. Un evento que llega más tarde de lo que cubre la ventana se entrega sin esperar y se cuenta. | Ordenar todo en memoria: el mes no cabe. Ordenar en disco: pasos y espacio de más sin saber si hace falta. Confiar en el orden del fichero: sin el perfil es una suposición. |
| 19 | **`rewrite-timestamp` es un desplazamiento fijo**: el primer evento es «ahora» y los demás conservan su separación original, también con aceleración. Los días siguen siendo días y el particionado y las métricas diarias no cambian. Con aceleración mayor que 1, los eventos posteriores llevan horas por delante del reloj. | Comprimir los timestamps con la aceleración: la hora coincidiría con la de envío, pero el mes cabría en unas horas y una sola partición diaria. |

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
| B. Consulta y MCP | T-014 → T-016 → T-019; T-017; T-016 + T-017 → T-020; T-019 + T-020 → T-021 | investigador y desarrollador |
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

### Qué se puede recortar si falta cupo

Propuesta en orden; decide la responsable. Quitar una tarjeta exige retirarla también de las dependencias de T-046 y T-064 en el panel.

| Orden | Recorte | Qué se pierde |
|-------|---------|---------------|
| 1 | Benchmark: T-049, T-052 (decisión en T-087). T-044 sigue si se mantiene la compactación con Trino | Una tabla del README y un punto del brief |
| 2 | Claude Desktop: T-028, T-030 | Un segundo cliente; el agente ya demuestra MCP |
| 3 | Pages: T-058, T-062 | Repite lo que ya está en el README y la wiki |
| 4 | Mantenimiento: T-047, y T-044 si ya no hay benchmark | Compactación y expiración. Conviene conservar T-045 (añadir una columna), que es barata y vistosa |
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

## Riesgos abiertos

- **Capacidades reales de DuckDB 2.0 sobre Iceberg** (borrado en tablas particionadas, sintaxis de time travel, ATTACH de solo lectura): la documentación no lo cubre todo. Hay una tarjeta de comprobación antes de construir encima, y de ella depende cómo se quitan duplicados.
- **Bootstrap repetido:** la documentación de 1.8.0 dice que es idempotente, pero no indica el código de salida. Si no es 0, `./mk up` fallaría la segunda vez.
- **Propiedades de 1.8.0:** la página de JDBC mezcla `quarkus.datasource.username` y `quarkus.datasource.jdbc.username`. Se usa la forma del Admin Tool y el test decide.
- **Imagen de Connect:** se compila desde git y tarda varios minutos la primera vez; cuenta para el tiempo de `./mk test`.
