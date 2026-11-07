# Mantenimiento: expirar snapshots

**Estado:** ✅ existe y tiene tests. **Sin probar:** contra el stack completo con datos reales.

## Para qué está aquí

Cada escritura en una tabla Iceberg crea un *snapshot* (una foto de la tabla). Con el tiempo se acumulan y ocupan disco. DuckDB no sabe borrar las viejas, así que lo hace un proceso aparte, de un solo uso: arranca, expira lo antiguo y termina ([decisión 6](../../architecture.md#decisiones)).

## Cómo se usa

Argumentos: `--table <espacio.tabla>` y, opcionalmente, `--retention <duración ISO-8601>` (por defecto `P7D`, siete días) o `--older-than <instante ISO-8601>`.

Códigos de salida: **0** todo bien, **1** fallo o ficheros que no se pudieron borrar, **2** argumentos o configuración incorrectos.

## Técnico

Código en [`maintenance/`](../../maintenance/src/main/java/io/github/albaasca/lakehouse/maintenance/): `ExpireSnapshotsCommand` (argumentos y conexión al catálogo REST de Polaris) y `SnapshotExpirer` (usa la API Java de Iceberg). Por qué no Trino: [decisiones](../05-decisiones.md#por-qué-no-hay-trino-ni-benchmark).
