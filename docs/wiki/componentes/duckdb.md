# DuckDB

**Estado:** ✅ funciona como línea de comandos en [`tools`](imagen-tools.md). 🟡 Embebido en el código del servidor MCP, con tests, pero el servidor aún no arranca ni lo usa.

[Portada](../README.md) · [Glosario](../01-conceptos.md)

## Qué es, en cristiano

Una base de datos que cabe en un programa, como una calculadora muy potente. No es un servidor que haya que mantener encendido: se abre, lee las tablas de donde estén y responde.

## Para qué está aquí y qué pasaría sin él

Es el [motor de consulta](../01-conceptos.md#motor-de-consulta). Convertirá las preguntas del agente en respuestas. La alternativa, [Trino](trino.md), necesita varios GB encendidos siempre. Compararlos es [el benchmark](trino.md), todavía sin medir.

## Cómo se conecta

| Dirección | Quién | Puerto |
|---|---|---|
| Salida | [Polaris](polaris.md): dónde está cada tabla | 8181, `POLARIS_CLIENT_ID`, `POLARIS_CLIENT_SECRET` |
| Salida | [SeaweedFS](seaweedfs.md): los ficheros | 8333, `S3_ACCESS_KEY`, `S3_SECRET_KEY` |
| Entrada | [Servidor MCP](servidor-mcp.md), en el mismo proceso | JDBC, con `LakehouseSession` |

No publica puerto alguno.

## Cómo funciona por dentro

La extensión `iceberg` permite hacer `ATTACH` de un catálogo REST y tratar sus tablas como propias, escritura incluida ([documentación](https://duckdb.org/docs/2.0/core_extensions/iceberg/overview)). `httpfs` aporta la lectura de S3.

Para conectarse necesita dos secretos: uno de tipo `iceberg` para Polaris y otro de tipo `s3` para el almacén. La opción clave es `ACCESS_DELEGATION_MODE`, cuyos [valores](https://duckdb.org/docs/2.0/core_extensions/iceberg/iceberg_options) son `vended_credentials` (por defecto) y `none`.

## Cómo está configurado aquí

- **CLI:** `v2.0.0 (Cyanoptera)`, descargada en [`infra/tools/Dockerfile`](../../../infra/tools/Dockerfile).
- **JDBC:** `duckdb_jdbc` `2.0.0.1` en [`pom.xml`](../../../pom.xml), usada por `testkit` y `mcp-server`.
- **Extensiones observadas:** `iceberg` `4c0c0874c4`, `httpfs` `77392a7207`, `avro` `345c3f4e82`, instaladas desde el repositorio de DuckDB.
- **Conexión:** la misma en [`infra/smoke.sh`](../../../infra/smoke.sh), [`LakehouseStack`](../../../testkit/src/main/java/io/github/albaasca/lakehouse/testkit/LakehouseStack.java) y el script de la wiki [`lh.sh`](../ejemplos/lh.sh).

| Opción | Valor | Por qué |
|---|---|---|
| `ACCESS_DELEGATION_MODE` | `'none'` | [Sin STS, el valor por defecto da error 400](../04-operacion.md#error-400-de-credential-vending) |
| `ENDPOINT` | `seaweedfs:8333` | Dentro de la red de Compose |
| `URL_STYLE` | `'path'` | SeaweedFS sin direcciones por bucket |
| `USE_SSL` | `false` | HTTP en red local |
| `OAUTH2_SCOPE` | `'PRINCIPAL_ROLE:ALL'` | Para que el token lleve los roles del usuario raíz |

### Dentro del servidor MCP

[`LakehouseSession`](../../../mcp-server/src/main/java/io/github/albaasca/lakehouse/mcp/LakehouseSession.java) abre una instancia de DuckDB y reparte varias conexiones. Es de solo lectura: las reparaciones necesitarán otra instancia.

| Ajuste | Por defecto | Qué pasa al superarlo o si falta |
|---|---|---|
| Conexiones | 4 | Quien espera más que el tiempo máximo recibe `SQLTimeoutException` |
| Tiempo máximo por consulta | 30 s | La consulta se corta con `SQLTimeoutException` y la conexión sigue sirviendo |
| `memory_limit` | `512MB` | La consulta falla con `Out of Memory` y la sesión sigue viva |
| `temp_directory` | vacío | No se desborda a disco: sin esto, el límite de memoria solo movería el problema al disco |
| `READ_ONLY` en el `ATTACH` | siempre | Rechaza cualquier escritura, también el `SELECT` de rollback |
| `installExtensions` | `true` | Con `false` no descarga nada: `httpfs`, `avro` e `iceberg` deben estar ya en `extensionDirectory`, o no arranca |

Para los tests, [`SyntheticLoader`](../../../testkit/src/main/java/io/github/albaasca/lakehouse/testkit/SyntheticLoader.java) carga la muestra en una tabla Iceberg sin pasar por Kafka. Los ficheros los escribe DuckDB, no el sink.

Lo demuestra `LakehouseSessionIT`, en `./mk test`.

## Cómo probarlo tú mismo

El tutorial lo hace paso a paso: [crear una tabla](../03-probarlo.md#paso-5-crea-una-tabla). Una comprobación rápida:

```bash
docker compose run --rm -T tools sh docs/wiki/ejemplos/lh.sh "SELECT library_version FROM pragma_version();"
```
```text
┌─────────────────┐
│ library_version │
│     varchar     │
├─────────────────┤
│ v2.0.0          │
└─────────────────┘
```

Lo comprobado para esta wiki sobre una tabla de prueba:

| Qué | Sintaxis | Resultado |
|---|---|---|
| Listar snapshots | `iceberg_snapshots('lh.ns.tabla')` | ✅ |
| Ver ficheros | `iceberg_metadata('lh.ns.tabla')` | ✅ |
| Time travel, la forma elegida ([decisión 5](../../architecture.md#decisiones)) | `... AT (VERSION => id)` | ✅ |
| Time travel, descartada | `iceberg_scan('lh.ns.tabla', snapshot_from_id=id)` | ✅ |

T-014 comprobó además, con tests en `DuckDbIcebergSpikeIT`:

| Qué | Sintaxis | Resultado |
|---|---|---|
| Borrar en tabla particionada | `DELETE FROM t WHERE ...` | ✅ |
| Quitar una de dos filas idénticas | `DELETE ... WHERE filename = ... AND file_row_number = ...` | ✅ |
| Catálogo de solo lectura | `ATTACH ... (TYPE iceberg, READ_ONLY, ...)` | ✅ |
| Rechazar lo que no es `SELECT` | `json_serialize_sql(...)` | ✅ |
| Vista sobre tabla Iceberg | `CREATE TEMP VIEW`, `CREATE VIEW memory.main.v` | ✅, sin `AT` a través de la vista |

**Cuidado:** `SELECT * FROM iceberg_rollback_to_snapshot(...)` cambia la tabla si el catálogo es escribible. Detalle y fuentes en [`spike-duckdb-iceberg.md`](../../spike-duckdb-iceberg.md). Por eso el servidor MCP tendrá dos instancias, y la del modelo solo adjunta el catálogo con `READ_ONLY` ([decisión 20](../../architecture.md#decisiones), ⬜ T-106).

**Sin comprobar:** borrar, hacer rollback, añadir columna o compactar sobre ficheros escritos por el sink real o mientras el sink hace commit, y que Trino lea los ficheros de borrado de DuckDB.

## Qué puede fallar

- **Cada ejecución tarda unos segundos de más.** `tools` se crea desde cero, y `INSTALL` vuelve a descargar las extensiones. Necesita internet.
- **Una lectura de S3 atascada puede tardar más que el tiempo máximo en cortarse.** Sin probar: el test corta una consulta que calcula, no una que espera a la red.
- **`Deprecated implicit conversion of unbound identifiers`.** Pasa el nombre como texto: `iceberg_snapshots('lh.ns.tabla')`, no `iceberg_snapshots(lh.ns.tabla)`.
- **Error 400 al crear una tabla.** Falta `ACCESS_DELEGATION_MODE 'none'`.
- **Fallos al leer ficheros.** Sin probar en este stack. Revisa primero el secreto S3 y el endpoint.

## Para saber más

- [Iceberg en DuckDB 2.0](https://duckdb.org/docs/2.0/core_extensions/iceberg/overview)
- [Catálogos REST](https://duckdb.org/docs/2.0/core_extensions/iceberg/catalogs)
- [Opciones](https://duckdb.org/docs/2.0/core_extensions/iceberg/iceberg_options)
