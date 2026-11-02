# Qué hace de verdad DuckDB con Iceberg

Comprobación de T-014, 2 de noviembre de 2026.

**Conclusión:** todo lo que el proyecto necesita de DuckDB funciona a través de Polaris sin STS, incluido quitar una sola copia de un duplicado exacto. Hay dos sorpresas que obligan a construir con cuidado:

1. **Un `SELECT` puede cambiar datos.** `SELECT * FROM iceberg_rollback_to_snapshot(...)` da marcha atrás a la tabla y el analizador lo acepta como lectura normal.
2. **Las vistas no admiten time travel.** Un analista que solo ve vistas enmascaradas no puede consultar un snapshot antiguo con `AT`.

Además, varias decisiones de `docs/architecture.md` se pueden simplificar: ver [Efecto en las decisiones](#efecto-en-las-decisiones).

**Entorno:** DuckDB `v2.0.0` (`duckdb_jdbc` 2.0.0.1), extensión `iceberg` `4c0c0874c4`, `httpfs` `77392a7207`, Polaris 1.8.0 y SeaweedFS 4.51, con `ACCESS_DELEGATION_MODE 'none'`.

**Cómo leer este documento:**

- **Comprobado** significa que lo demuestra un test de `testkit/src/test/java/io/github/albaasca/lakehouse/testkit/DuckDbIcebergSpikeIT.java`, que corre en `./mk test`. Se indica el test entre paréntesis.
- **Documentado** significa que lo dice la documentación, sin haberlo probado aquí.
- **No verificado** significa que es una suposición o que nadie lo ha probado todavía.

## Respuestas

### 1. INSERT en tabla particionada por `day(ts), event_type`

**Comprobado: funciona.** Escribe un fichero por partición, y el día queda codificado en la ruta (`day_ts_2=20454/identity_event_type_3=cart/...`). (`insertWritesOneFilePerPartition`)

Sintaxis: `CREATE TABLE ... PARTITIONED BY (day(ts), event_type)`.

### 2. Time travel

**Comprobado: funcionan todas las formas sobre una tabla del catálogo**, y devuelven filas ya borradas en la versión actual. (`timeTravelWorksWithAtClauseAndWithSnapshotFromId`)

| Forma | ¿Funciona? | ¿Pasa el validador de la decisión 7? |
|---|---|---|
| `t AT (VERSION => id)` | Sí | Sí: es una tabla con `at_clause` |
| `t AT (TIMESTAMP => TIMESTAMP '...')` | Sí | Sí |
| `iceberg_scan('lh.ns.t', snapshot_from_id = id)` | Sí | No: es una función de tabla |

**Recomendación: usar `AT (VERSION => id)`.** Es la forma documentada para catálogos adjuntos y no obliga a abrir una excepción en la regla de «ninguna función de tabla».

Un id inexistente da un error claro: `Could not find snapshot with id 12345`.

**No verificado:** cómo interpreta `AT (TIMESTAMP => ...)` la zona horaria. Los tests corren en UTC.

### 3. `iceberg_snapshots` e `iceberg_metadata`

**Comprobado: funcionan los dos.** (`snapshotsAndMetadataDescribeEachCommit`)

- `iceberg_snapshots` devuelve más columnas de las documentadas: `sequence_number, snapshot_id, parent_id, timestamp_ms, manifest_list, operation, summary`. `operation` distingue `append` de `delete`, y `summary` trae los totales, algo útil para `pipeline_health`.
- `iceberg_metadata` devuelve `content` = `DATA` o `POSITION_DELETES`, además de `file_path` y `record_count`.
- La documentación avisa de un fallo de `iceberg_metadata` con su tabla de ejemplo `lineitem_iceberg`. Aquí no se ha reproducido.
- Hay otra función muy cómoda, `iceberg_files`, con `content`, `partition`, `record_count` y `referenced_data_file`.
- Por JDBC funcionan el nombre sin comillas y como texto. La [wiki](wiki/componentes/duckdb.md) avisa de un mensaje de obsolescencia sin comillas, así que conviene pasarlo como texto.

### 4. DELETE y duplicados exactos

**Comprobado: se puede borrar solo una de dos filas idénticas, sin reescribir la partición.**

La columna se llama **`filename`, no `file_path`** como suponía la tarjeta. `file_path` da error de binder en la tabla; solo existe en `iceberg_metadata` e `iceberg_files`. `rowid` tampoco existe.

Sentencia para quitar duplicados, sin saber en qué ficheros están las copias (`windowFunctionKeepsOneCopyOfEachDuplicate`):

```sql
DELETE FROM t WHERE (filename, file_row_number) IN (
  SELECT filename, file_row_number FROM (
    SELECT filename, file_row_number, row_number() OVER (
      PARTITION BY <todas las columnas> ORDER BY filename, file_row_number) AS copy
    FROM t)
  WHERE copy > 1);
```

| Caso | Resultado | Test |
|---|---|---|
| Borrado por rango de una partición | Solo caen sus filas | `deleteByPartitionRangeLeavesTheOtherPartitions` |
| Dos copias en el mismo fichero | Queda una | `removesOneOfTwoIdenticalRowsInTheSameFile` |
| Dos copias en ficheros distintos | Queda una | `removesOneOfTwoIdenticalRowsInDifferentFiles` |
| Dos borrados seguidos sobre el mismo fichero | Se acumulan (dos ficheros de borrado) | `repeatedDeletesOnTheSameFileAccumulate` |
| Tabla creada por la API del catálogo, con `timestamptz` y sin propiedades de DuckDB | Igual | `deletesInATableCreatedThroughTheCatalogApi` |

**Alternativa, no necesaria:** reescribir la partición en una transacción funciona, pero deja dos snapshots, y el intermedio tiene la partición vacía para quien viaje a él. (`rewritingAPartitionInATransactionLeavesTwoSnapshots`)

**No verificado, y debe cubrirlo T-038:**

- Borrar sobre ficheros escritos de verdad por el sink, no por DuckDB. La tabla por API se parece, pero sus ficheros los escribió DuckDB.
- Borrar mientras el sink hace commit. No se sabe si hay conflicto ni cómo se manifiesta.

### 5. ATTACH de solo lectura

**Comprobado: admite `READ_ONLY`, aunque la página de opciones no lo mencione.** (`readOnlyAttachReadsAndRefusesEveryWrite`)

```sql
ATTACH 'lakehouse' AS ro (TYPE iceberg, READ_ONLY, URI '...', SECRET ..., ACCESS_DELEGATION_MODE 'none')
```

- Lee, y también viaja en el tiempo.
- Rechaza `INSERT`, `DELETE`, `UPDATE`, `DROP`, `CREATE`, `ALTER` y los `CALL` que escriben, con `attached in read-only mode`.
- **También rechaza el `SELECT` de rollback**: es la barrera buena contra la primera sorpresa.
- Una conexión abierta ve los commits posteriores de otra instancia, sin volver a adjuntar. (`readOnlyAttachSeesLaterCommitsFromAnotherInstance`)

### 6. `json_serialize_sql` como validador

**Comprobado: rechaza lo que no es `SELECT`, pero deja pasar bastante que el validador debe parar él mismo.**

Con `"error":true` y `Only SELECT statements can be serialized to json!` rechaza `DROP`, `INSERT`, `DELETE`, `UPDATE`, `CREATE` (también `CREATE ... AS SELECT`), `COPY`, `ATTACH`, `SET`, `PRAGMA`, `CALL`, `INSTALL`, `LOAD`, `EXPLAIN` y `PIVOT`. (`serializerRejectsWhatIsNotASelect`)

Devuelven `"error":false` y hay que detectarlos en el árbol (`serializerAcceptsThemAndTheNodeGivesThemAway`):

| Consulta | Qué mirar en el JSON |
|---|---|
| `read_csv`, `glob`, `read_text`, `query`, `query_table`, `iceberg_*` | `"type":"TABLE_FUNCTION"`, también dentro de subconsultas, `UNION`, `LATERAL` y CTE |
| `SELECT * FROM '/etc/passwd'`, `SELECT * FROM 's3://b/x.parquet'` | **`BASE_TABLE`** con `table_name` igual a la ruta. No es función de tabla: solo lo para la lista de tablas permitidas |
| `SELECT 1; SELECT 2` | Dos elementos en `statements` |
| `DESCRIBE x`, `SUMMARIZE x`, `SHOW TABLES` | `from_table` de tipo `SHOW_REF` |
| `SELECT getenv('HOME')` | Función escalar con `function_name`; no aparece en `from_table` |
| `t AT (VERSION => 1)` | `at_clause` no nulo |

**Reglas que debe cumplir el validador (T-019):**

1. Exactamente un elemento en `statements`.
2. Recorrer todo el árbol, no solo el `from_table` de arriba. La raíz puede ser `SET_OPERATION_NODE`, sin `select_list` propia.
3. Lista **de permitidos**, no de prohibidos: tipos de `from_table` (`BASE_TABLE`, `JOIN`, `SUBQUERY`, `EMPTY`...) y tablas conocidas.
4. Un nombre de CTE aparece como `BASE_TABLE`. Hay que admitirlo solo si está en un `cte_map` de su ámbito, y validar también el cuerpo de esa CTE.
5. Lista de funciones escalares permitidas, o como mínimo prohibir `getenv`, `getvariable` y `current_setting`. **No verificado:** qué otras funciones escalares exponen datos del proceso.
6. `at_clause` rechazado en `run_query`: el time travel va por su propia herramienta.

**Por qué la regla 3 es de seguridad y no de estilo:** un `SELECT` en un catálogo escribible ejecutó el rollback. (`aSelectCanRollBackATableWhenTheCatalogIsWritable`)

### 7. Vistas sobre tablas Iceberg (decisión 12)

**Comprobado: la vista temporal que hashea `user_id` funciona**, también sobre el catálogo de solo lectura. Agrupar y contar por usuario da lo mismo que sobre la tabla, y el filtro llega al `Iceberg Scan`. (`temporaryViewHashesTheUserAndKeepsGroupsOverAReadOnlyCatalog`)

Tres matices:

| Hecho comprobado | Consecuencia | Test |
|---|---|---|
| Una vista `TEMP` solo existe en **su conexión**, no en una hermana creada con `duplicate()` | Con un pool, hay que crearla al abrir cada conexión | `temporaryViewBelongsToItsConnectionAndALocalViewToTheInstance` |
| `CREATE VIEW memory.main.v` la ven todas las conexiones de la instancia, y funciona aunque el catálogo sea de solo lectura | **Es la opción recomendada** para un pool | el mismo |
| `SELECT * FROM vista AT (VERSION => id)` falla: `Catalog type does not support time travel` | Sin time travel directo para el analista. Se puede fijar: `CREATE TEMP VIEW v AS SELECT ... FROM t AT (VERSION => id)` | `viewsCannotTimeTravelButCanBePinnedToASnapshot` |

También se pueden crear vistas en el propio catálogo (`CREATE VIEW lh.ns.v`). (`viewInTheCatalogIsAlsoPossible`) **No se recomienda para el enmascarado**: la sal quedaría guardada en la definición de la vista, en el catálogo. Eso es razonamiento, no una prueba.

## Fuera de las preguntas

Se probó porque afecta a las decisiones 5 y 6, y a T-087 (quitar Trino):

| Operación | Resultado | Test |
|---|---|---|
| Rollback: `CALL iceberg_rollback_to_snapshot('t', id)` | **Comprobado: funciona** | `rollbackThroughDuckDb` |
| Añadir columna: `ALTER TABLE t ADD COLUMN c VARCHAR` | **Comprobado: funciona**; las filas viejas dan `NULL` y el snapshot viejo se sigue leyendo | `addColumnThroughDuckDbKeepsOldRows` |
| Compactar: `CALL iceberg_rewrite_data_files('t', min_input_files => 2)` | **Comprobado: funciona con un matiz**. Tres ficheros pasan a uno, aplica el borrado y conserva el time travel, pero el fichero de borrado sigue registrado | `compactionThroughDuckDbAppliesDeletesAndMergesFiles` |
| Expirar snapshots | **Comprobado: no hay función** con `expire` en el nombre | `noFunctionExpiresSnapshots` |

Dos avisos sobre la compactación:

- **Por defecto no hace nada con menos de 5 ficheros candidatos por partición.** Devuelve `0|0|0` sin error, algo fácil de confundir con un fallo.
- **La documentación va por delante de la extensión instalada.** Anuncia cinco columnas de resultado, con `removed_delete_files_count`, y esta versión devuelve tres: `rewritten_data_files, added_data_files, rewritten_bytes`.

**No verificado:** que Trino lea bien los ficheros de borrado de DuckDB, sobre lo que se apoya la decisión 6. Tampoco se ha medido la compactación con un volumen real.

## Trampas del driver

- **Un error cierra el `Statement` JDBC**, aunque no la conexión; las llamadas siguientes dan `Statement was closed`. Conviene un `Statement` nuevo por consulta (T-016). (`aFailedStatementClosesItsJdbcStatementButNotTheConnection`)
- **`executeUpdate` devuelve el número de filas** de un `DELETE`. **No verificado:** qué hace `executeQuery` con una escritura; los tests no lo usan para eso.
- **`jdbc:duckdb:` abre una base nueva en cada `getConnection`**, sin secretos ni catálogos. Para compartirla hace falta `duplicate()`. Sin test propio: se deduce de que cada conexión de los tests adjunta `lh` sin chocar con las demás, y `DETACH lh` en una no afecta a las otras.

## Efecto en las decisiones

Ninguna decisión queda invalidada en lo esencial, pero cuatro necesitan revisión. **El arquitecto decide**; aquí solo se aportan los hechos.

| Decisión | Dice hoy | Hecho comprobado | Propuesta |
|---|---|---|---|
| 5 | Time travel con `snapshot_from_id` | Funciona, pero exige una función de tabla que el validador prohíbe | Usar `AT (VERSION => id)` |
| 5 | Iceberg Java API porque la documentación «no cubre rollback, expiración ni compactación» | La documentación de 2.0 cubre rollback y compactación; rollback, columnas y snapshots funcionan por DuckDB | Valorar quitar esa dependencia de `mcp-server`, salvo lo que falte para expirar |
| 6 | Compactar y expirar con Trino | DuckDB compacta; expirar sigue sin solución en DuckDB | Si T-087 quita Trino, hace falta otra vía solo para expirar |
| 7 | Validar con `json_serialize_sql` | Válido, con las seis reglas de arriba | Añadir las reglas |
| 12 | Vistas para enmascarar | Funcionan, con los tres matices de arriba | Vista en `memory.main` y decidir qué hace el analista con el time travel |

**Propuesta nueva para la arquitectura:** dos instancias de DuckDB dentro de `mcp-server`. Las consultas del agente van a una que **solo tiene el catálogo `READ_ONLY`**, y las reparaciones aprobadas van a otra escribible. Así, si el validador falla, el `SELECT` de rollback sigue rechazado.

## Fuentes

Consultadas el 2 de noviembre de 2026:

- [Iceberg en DuckDB 2.0](https://duckdb.org/docs/2.0/core_extensions/iceberg/overview.html): sintaxis de `AT` y de `snapshot_from_id`.
- [Writing to Iceberg](https://duckdb.org/docs/2.0/core_extensions/iceberg/writing_to_iceberg.html): `DELETE` en tablas particionadas, solo merge-on-read.
- [Iceberg Functions](https://duckdb.org/docs/2.0/core_extensions/iceberg/iceberg_functions.html): rollback, compactación y `min_input_files`.
- [Iceberg Options](https://duckdb.org/docs/2.0/core_extensions/iceberg/iceberg_options.html): opciones de `ATTACH`; no menciona `READ_ONLY`.
- [SQL to and from JSON](https://duckdb.org/docs/2.0/data/json/sql_to_and_from_json.html): `json_serialize_sql`; no dice qué hace con lo que no es `SELECT`.
