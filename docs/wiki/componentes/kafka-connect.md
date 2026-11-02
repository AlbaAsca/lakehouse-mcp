# Kafka Connect y el sink de Iceberg

**Estado:** ⚠️ El worker y el plugin funcionan y el conector `iceberg-sink` se registra, pero la tarea sale `FAILED` con `NoClassDefFoundError: org/apache/kafka/common/utils/AppInfoParser` (T-072). **Hoy no entran datos en Iceberg.**

[Portada](../README.md) · [Glosario](../01-conceptos.md)

## Qué es, en cristiano

El operario entre la cinta y el archivo. Kafka Connect es un programa que mueve datos entre Kafka y otros sistemas sin que escribas código: le das una configuración y un plugin. El *sink* de Iceberg es el plugin que recoge eventos de la cinta y los archiva como tablas.

## Para qué está aquí y qué pasaría sin él

Convierte el flujo de eventos en tablas consultables. Sin él habría que escribir un consumidor propio que resolviera lo difícil: agrupar en ficheros, confirmar en el catálogo y no duplicar nada tras un fallo.

## Cómo se conecta

| Dirección | Quién | Puerto |
|---|---|---|
| Entrada | [Kafka](kafka.md): topic `ecommerce.events` | 9092 interno |
| Entrada | [Schema Registry](schema-registry.md): esquema Avro | 8081 |
| Salida | [SeaweedFS](seaweedfs.md): ficheros de datos | 8333, `S3_ACCESS_KEY`, `S3_SECRET_KEY` |
| Salida | [Polaris](polaris.md): commits | 8181, `POLARIS_CLIENT_ID`, `POLARIS_CLIENT_SECRET` |
| Control | API REST | 8083, publicado |

Connect guarda su estado en tres topics de Kafka: `_connect-configs`, `_connect-offsets` y `_connect-status`.

## Cómo funciona por dentro

Según la [documentación de Iceberg](https://iceberg.apache.org/docs/latest/kafka-connect/), el sink escribe ficheros de datos continuamente, y un coordinador hace el commit en cada intervalo, avisando a través de un topic de control (`control-iceberg` por defecto). La documentación anuncia entrega *exactly-once*, apoyada en las transacciones de Kafka. Aquí no se ha comprobado: lo cubrirá T-015.

```mermaid
flowchart LR
    T[ecommerce.events] --> W[Tarea del sink]
    W -->|Parquet| S[(SeaweedFS)]
    W -->|listo para commit| CT[control-iceberg]
    CT --> CO[Coordinador]
    CO -->|cada 30 s| P[Polaris]
```

## Cómo está configurado aquí

**Worker.** Servicio `connect` en [`compose.yaml`](../../../compose.yaml), imagen construida con [`infra/connect/Dockerfile`](../../../infra/connect/Dockerfile) sobre `confluentinc/cp-kafka-connect:8.4.0`.

| Opción | Valor | Por qué |
|---|---|---|
| `CONNECT_VALUE_CONVERTER` | `AvroConverter` | Los eventos son Avro |
| `CONNECT_PLUGIN_PATH` | incluye `/opt/connectors` | Donde queda el plugin compilado |
| `KAFKA_HEAP_OPTS` | `-Xmx1g` | Presupuesto de memoria |
| `AWS_*` | `S3_REGION`, `S3_ACCESS_KEY`, `S3_SECRET_KEY` | Claves estáticas: [no hay STS](../05-decisiones.md#seaweedfs-sin-sts) |

**Plugin.** No hay zip publicado: la documentación indica que se genera con el build. El Dockerfile clona la etiqueta `apache-iceberg-1.12.0` y ejecuta el `distZip`. Se usa `git clone` y no un tarball [porque el build necesita `.git`](../04-operacion.md#el-conector-de-iceberg-no-se-puede-descargar).

Una rareza comprobada: el plugin se anuncia como `1.13.0-SNAPSHOT`. Sus metadatos (`iceberg-build.properties`) indican `git.tags=apache-iceberg-1.12.0`, es decir, es el código de la etiqueta 1.12.0 con otra etiqueta de versión.

**Conector.** [`connect/iceberg-sink.json`](../../../connect/iceberg-sink.json), registrado por el servicio de un solo uso `connect-init` con [`connect/register.sh`](../../../connect/register.sh).

| Opción | Valor | Por qué |
|---|---|---|
| `iceberg.tables` | `ecommerce.events` | Tabla destino |
| `iceberg.tables.auto-create-enabled` | `true` | Crea la tabla, no el namespace: lo crea `init.sh` |
| `iceberg.tables.evolve-schema-enabled` | `false` | El *schema drift* debe ser un fallo detectable ([decisión 3](../../architecture.md#decisiones)) |
| `iceberg.tables.default-partition-by` | `day(event_time),event_type` | El particionado del brief |
| `iceberg.control.commit.interval-ms` | `30000` | La documentación da 5 minutos por defecto. 30 s dan una demo más viva a cambio de más ficheros |
| `transforms.eventTime` | `TimestampConverter` | El [README de `connect/`](../../../connect/README.md) explica que el conversor entrega `timestamp-micros` como un `long`, y `day()` no podría particionarlo |
| `errors.tolerance`, `errors.deadletterqueue.topic.name` | `all`, `ecommerce.events.dlq` | Los mensajes ilegibles van aparte. Según ese README, un error al escribir sigue parando la tarea |
| `iceberg.catalog.s3.*` | endpoint de SeaweedFS, path-style y claves | Sin credential vending |

## Cómo probarlo tú mismo

```bash
curl -s localhost:8083/
```
```text
{"version":"8.4.0-ccs","commit":"...","kafka_cluster_id":"..."}
```

El plugin está cargado:

```bash
curl -s localhost:8083/connector-plugins
```
```text
[{"class":"org.apache.iceberg.connect.IcebergSinkConnector","type":"sink","version":"1.13.0-SNAPSHOT"}, ...]
```

Conectores registrados. En el stack comprobado, anterior a T-012:

```bash
curl -s localhost:8083/connectors
```
```text
[]
```

Con el stack actual verás `["iceberg-sink"]`. Su estado se consulta en `/connectors/iceberg-sink/status`: hoy el conector sale `RUNNING` y su tarea, `FAILED`.

## Qué puede fallar

- **`connect-init` termina con error.** Si falta una variable de `.env`, [`register.sh`](../../../connect/register.sh) para. Mira `docker compose logs connect-init`.
- **El primer `up` tarda varios minutos.** Compila Iceberg entero, y requiere acceso a GitHub.
- **La tarea pasa a `FAILED`.** Es el estado actual (T-072). El motivo está en el campo `trace` de `/connectors/iceberg-sink/status`.
- **Cambias el esquema y deja de escribir.** Es lo buscado, con la evolución apagada.
- **`up` sigue sin registrar el conector.** Contenedores anteriores a T-012: `./mk up` debe crear `connect-init`.

## Para saber más

- [Iceberg Kafka Connect](https://iceberg.apache.org/docs/latest/kafka-connect/)
