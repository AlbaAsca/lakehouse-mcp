# Decisiones, alternativas y coste

Cada proyecto es una cadena de renuncias. Aquí se resume qué se eligió y qué cuesta. El razonamiento completo, con las alternativas descartadas y su número, está en [`docs/architecture.md`](../architecture.md): esta página no lo copia, añade lo que se ha observado al construirlo.

[Volver a la portada](README.md)

## La restricción que explica casi todo

Todo debe caber en unos 7,6 GB de memoria de Docker y ejecutarse con un comando. Por eso no hay Flink ni Spark, hay un solo broker, y Trino solo se enciende a demanda.

## Las que ya están construidas

| Decisión | Por qué | Lo que cuesta | Estado |
|---|---|---|---|
| Todo en Docker, incluidas las herramientas | Se clona y funciona, sin instalar JDK ni Maven | Cada comando arranca un contenedor; `tools` monta el socket de Docker, con los permisos que implica | ✅ |
| Sin bind mounts fuera de `tools` | `make up` corre dentro de un contenedor, y sus rutas no existen fuera | Cambiar una configuración exige reconstruir la imagen | ✅ |
| Catálogo en Postgres ([1](../architecture.md#decisiones)) | En memoria se perdía | Un servicio más y un bootstrap de un solo uso | ✅ |
| Conector compilado desde git | No hay zip publicado | Minutos en el primer `up`; dependencia de GitHub | ✅ |
| DuckDB para SQL ([4](../architecture.md#decisiones)) | Trino necesitaría varios GB siempre | Capacidades sobre Iceberg por confirmar (T-014) | ✅ como CLI, ⬜ embebido |
| Avro con compatibilidad `BACKWARD` ([13](../architecture.md#decisiones)) | Encaja con Connect y Schema Registry | Solo se comprueba contra la última versión | ✅ Registry, ⬜ test |
| Muestra de CI sintética ([15](../architecture.md#decisiones)) | La licencia del dataset impide copiarlo | Puede no parecerse a los datos reales (T-010) | ✅ |

### SeaweedFS frente a MinIO

MinIO quedó descartado porque su repositorio comunitario está archivado. SeaweedFS es menos conocido, y arranca varios procesos en uno.

### SeaweedFS sin STS

**En cristiano:** el [credential vending](01-conceptos.md#credential-vending) es como un portero que da llaves temporales a cada visitante. Aquí no hay portero: cada cliente lleva su propia llave permanente.

**Qué implica:** Polaris usa `stsUnavailable: true`; DuckDB, `ACCESS_DELEGATION_MODE 'none'` y un secreto S3; Connect, claves estáticas. Todos comparten `S3_ACCESS_KEY`, así que el permiso de Polaris no limita lo que un cliente puede leer directamente en S3. Comprobado en el tutorial: se leen los Parquet [sin pasar por el catálogo](03-probarlo.md#paso-8-mira-los-ficheros).

**Matiz:** la [wiki de SeaweedFS](https://github.com/seaweedfs/seaweedfs/wiki/Amazon-S3-API) lista operaciones STS en su API. Es decir, «sin STS» es una decisión de configuración de este proyecto, no necesariamente un límite del producto. No se ha probado activarlas.

## Las que aún son diseño

| Decisión | Para qué | Estado |
|---|---|---|
| Sink oficial con evolución de esquema apagada ([3](../architecture.md#decisiones)) | El *schema drift* es un fallo reparable | 🚧 T-012 |
| Iceberg Java API para el catálogo ([5](../architecture.md#decisiones)) | Rollback, snapshots, añadir columna | ⬜ T-017 |
| Compactación con Trino ([6](../architecture.md#decisiones)) | No reimplementar algo delicado | ⬜ T-047 |
| Validar SQL con el parser de DuckDB ([7](../architecture.md#decisiones)) | Mismo dialecto que el motor | ⬜ T-019 |
| Métricas en YAML ([8](../architecture.md#decisiones)) | Sin servicio de más por cuatro métricas | ⬜ T-031 |
| Arreglos de catálogo cerrado con aprobación externa ([9](../architecture.md#decisiones)) | El modelo no puede autoaprobarse | ⬜ T-026 |
| Dos claves de API, enmascarado por vistas ([11 y 12](../architecture.md#decisiones)) | RBAC sin Keycloak | ⬜ T-050, T-054 |
| Muestreo por sesión ([14](../architecture.md#decisiones)) | No romper el embudo | 🚧 T-011, sin subir |
| Evals deterministas con coste acotado ([16 y 17](../architecture.md#decisiones)) | Reproducibles y sin gasto continuo | ⬜ T-056, T-060 |

## Lo que se ha aprendido y la documentación aún no dice

Diferencias entre documentos y realidad, comprobadas el 2 de noviembre de 2026 en `129dee6`. No se han corregido, porque esta wiki solo toca `docs/wiki/`.

| Documento | Dice | Realidad |
|---|---|---|
| [README](../../README.md) | Persistencia del catálogo: «hoy se pierde al reiniciar» y «falta comprobarlo en el stack completo» | T-002 y T-005 cerradas |
| README | «Los módulos Java aún no tienen código ni tests propios» | `producer` y `testkit` los tienen |
| README, [Dockerfile](../../infra/connect/Dockerfile) | Sink 1.12.0 | Se compila esa etiqueta, pero el plugin se anuncia como `1.13.0-SNAPSHOT` |
| [`architecture.md`](../architecture.md#pruebas-qué-puede-verificar-cada-quién) | `./mvnw` «no sirve en el host porque no hay JDK» | [`mvnw`](../../mvnw) lanza Maven dentro de `tools` |
| [`architecture.md`](../architecture.md#riesgos-abiertos) | Riesgo: el bootstrap repetido podría fallar | Sale bien: `already bootstrapped; skipping` |
| `architecture.md` | Time travel con `snapshot_from_id` | Funciona, y también `AT (VERSION => id)`. T-014 debe fijarlo |
| [`architecture.svg`](../img/architecture.svg) | No muestra Postgres ni Trino | Postgres está en el stack; Trino, declarado |
| Panel | T-012 parece casi hecha | El conector se registra, pero su tarea falla al arrancar (T-072) |
| [`smoke.sh`](../../infra/smoke.sh) | «every service answers» | Comprueba Registry y Connect por HTTP, y DuckDB, Polaris y SeaweedFS de punta a punta. Kafka solo por su healthcheck. Además, deja ficheros y el namespace `smoke` |
| [`brief.md`](../brief.md) | README en inglés con Mermaid | Hoy en español y con SVG. Pendiente en T-055 |
