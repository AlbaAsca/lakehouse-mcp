# Trino

**Estado:** ⬜ planificado. El servicio está declarado en `compose.yaml`, pero sin catálogo configurado: hoy no sabría leer las tablas. No se ha arrancado para esta wiki. Tarjetas T-044, T-047, T-049 y T-052.

[Portada](../README.md) · [Glosario](../01-conceptos.md)

## Qué es, en cristiano

Si [DuckDB](duckdb.md) es una calculadora potente, Trino es una oficina entera de calculistas: un motor de SQL que reparte el trabajo entre máquinas. Es más grande y hay que tenerlo encendido.

## Para qué estará aquí y qué pasaría sin él

Dos encargos puntuales, con la memoria justa para encenderlo solo a demanda:

1. **Mantenimiento:** compactar ficheros y expirar snapshots ([decisión 6](../../architecture.md#decisiones)).
2. **Benchmark:** comparar las mismas consultas en DuckDB y Trino ([brief](../../brief.md#funcionalidades-clave)).

Sin él, la compactación habría que reimplementarla o traer Spark.

## Cómo se conectará

| Dirección | Quién | Puerto |
|---|---|---|
| Entrada | `make bench`, tareas de mantenimiento | 8080, perfil `bench` |
| Salida | [Polaris](polaris.md) | 8181 (previsto) |
| Salida | [SeaweedFS](seaweedfs.md) | 8333, claves estáticas (previsto) |

## Cómo funcionará por dentro

La [documentación de Trino](https://trino.io/docs/current/connector/iceberg.html) permite usar un catálogo REST con `iceberg.catalog.type=rest`, y ofrece:

```sql
ALTER TABLE test_table EXECUTE optimize(file_size_threshold => '128MB');
ALTER TABLE test_table EXECUTE expire_snapshots(retention_threshold => '7d');
```

`expire_snapshots` no admite un umbral menor que `iceberg.expire-snapshots.min-retention`, 7 días por defecto. Es relevante para una demo. Son sintaxis de la documentación, sin ejecutar aquí.

## Cómo está configurado aquí

Servicio `trino` en [`compose.yaml`](../../../compose.yaml): imagen `trinodb/trino:484`, perfil `bench` y puerto 8080. Nada más: ni catálogo, ni volumen, ni límite de memoria. Según el [presupuesto](../../architecture.md#memoria-docker-76-gb), son 2 GB y conviene parar Connect mientras tanto.

## Cómo probarlo tú mismo

Comprobado que el perfil existe:

```bash
docker compose config --profiles
```
```text
bench
tools
```

`./mk bench` responde `not implemented yet`. **Sin probar:** `docker compose --profile bench up -d trino`.

## Qué puede fallar

Riesgos de diseño: memoria justa con todo encendido, y una decisión pendiente sobre cómo compactar ficheros de borrado posicional, que son los que escribe DuckDB.

## Para saber más

- [Conector Iceberg de Trino](https://trino.io/docs/current/connector/iceberg.html)
