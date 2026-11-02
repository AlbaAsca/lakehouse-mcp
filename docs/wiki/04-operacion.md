# Operación y diagnóstico

Qué mirar cuando algo no va. Primero las herramientas, luego los síntomas y sus remedios. Los problemas de esta página ocurrieron de verdad, salvo los marcados «sin probar».

[Volver a la portada](README.md)

## Mirar el estado

```bash
docker compose ps -a                         # estado de cada servicio
docker compose logs --tail 50 connect        # últimas líneas de un servicio
docker compose logs lakehouse-init           # qué hizo un servicio de un solo uso
./mk smoke                                   # prueba de punta a punta
```

Se lee así: `healthy` es sano, `Exited (0)` en un servicio de un solo uso es lo normal, y `unhealthy` o `Exited (1)` son un problema.

| Pieza | Comprobación rápida | Respuesta sana |
|---|---|---|
| Kafka | `docker compose exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list` | Lista de topics, con `_schemas` y `_connect-*` |
| Schema Registry | `curl -s localhost:8081/subjects` | `[]` o una lista |
| Connect | `curl -s localhost:8083/connectors` | Lista de conectores |
| Polaris | `curl -s localhost:8182/q/health/ready` | `"status": "UP"` |
| Postgres | `docker compose exec -T postgres psql -U polaris -d polaris -c '\dn'` | Incluye `polaris_schema` |
| SeaweedFS | `curl -s -o /dev/null -w '%{http_code}\n' localhost:8333/` | `403`: responde y pide credenciales |

Salidas que parecen errores y no lo son:

- `catalog: 409` en `lakehouse-init`: el catálogo ya existía.
- `Realm 'POLARIS' is already bootstrapped; skipping.` en `polaris-bootstrap`.
- `make: *** [...] Error 1` tras `not implemented yet`: el objetivo aún no existe.

## Síntomas y remedios

### Docker no responde

**Síntoma:** cualquier comando `docker` se queda colgado o da un error que menciona `dockerDesktopLinuxEngine`.

**Causa:** Docker Desktop está parado o bloqueado. En el PC de la responsable ocurre a menudo.

**Remedio:** reinicia Docker Desktop y vuelve a intentarlo, sin insistir en bucle. El [`CLAUDE.md`](../../CLAUDE.md) menciona `docker-start.ps1`: **ese script es del entorno de la responsable y no está en este repo**. Si clonas el proyecto, no lo tendrás ni lo necesitas.

### El conector de Iceberg no se puede descargar

**Síntoma:** buscas un zip del conector y no existe, o el build falla si compilas desde un tarball.

**Causa:** la [documentación](https://iceberg.apache.org/docs/latest/kafka-connect/) indica que el archivo se genera con el build de Iceberg. Y ese build lee la versión y sus propiedades de `.git`.

**Remedio ya aplicado:** [`infra/connect/Dockerfile`](../../infra/connect/Dockerfile) hace `git clone --depth 1` de la etiqueta. Consecuencia: el primer `up` tarda minutos y necesita GitHub.

### Las imágenes Confluent no traen curl

**Síntoma:** un healthcheck con `curl` deja `schema-registry` o `connect` siempre `unhealthy`.

**Comprobado:** en ambas imágenes, `curl` no está.

**Remedio ya aplicado:** el healthcheck abre el puerto con bash: `exec 3<>/dev/tcp/localhost/8081`. Solo comprueba que el puerto acepta conexiones, no que el servicio esté listo. Por eso [`connect/register.sh`](../../connect/register.sh) reintenta si Connect responde 404, 409 o 5xx.

### Error 400 de credential vending

**Síntoma:** al crear una tabla desde DuckDB:

```text
HTTP Error: Request to 'http://polaris:8181/api/catalog/v1/lakehouse/namespaces/.../tables' returned a non-200 status code (BadRequest_400), with reason: Bad Request, body: {"error":{"message":"Credential vending was requested for table ..., but no credentials are available","type":"IllegalArgumentException","code":400}}
```

**Causa:** SeaweedFS va sin STS, el catálogo tiene `stsUnavailable: true`, y el valor por defecto de `ACCESS_DELEGATION_MODE` en DuckDB es [`vended_credentials`](https://duckdb.org/docs/2.0/core_extensions/iceberg/iceberg_options). Le pide a Polaris unas credenciales que no puede dar.

**Remedio:** en el `ATTACH`, `ACCESS_DELEGATION_MODE 'none'`, y un secreto S3 propio. Lo hacen [`smoke.sh`](../../infra/smoke.sh) y [`lh.sh`](ejemplos/lh.sh). Error reproducido para esta wiki.

### Polaris pierde el catálogo al reiniciar

**Síntoma:** tras reiniciar Polaris, el catálogo da 404 aunque los ficheros sigan en S3.

**Causa:** el backend `in-memory` por defecto [pierde los datos al reiniciar](https://polaris.apache.org/releases/1.8.0/metastores/).

**Remedio ya aplicado (T-002, T-005):** catálogo en [Postgres](componentes/postgres.md). **Si tus contenedores son anteriores a ese cambio, recréalos con `./mk up`.** Tablas registradas en la versión en memoria no se recuperan.

Comprueba si estás al día: `docker compose ps -a` debe incluir `postgres` y `polaris-bootstrap`.

### Maven, make y Kaggle viven en `tools`

**Síntoma:** `mvn: command not found`, `make: command not found`, o `./mvnw` que parece no funcionar.

**Causa:** no se instalan en tu ordenador.

**Remedio:** usa `./mk <objetivo>` o `./mvnw <args>`, que los ejecutan dentro de `tools`. Hace falta `.env`.

A la CLI de Kaggle le faltaba la dependencia `six`. Ya está en [`infra/tools/Dockerfile`](../../infra/tools/Dockerfile). Si te aparece ese error, tu imagen `tools` es vieja: reconstrúyela con `docker compose build tools`, que no se ha ejecutado para esta wiki.

### DROP TABLE deja los ficheros en S3

**Síntoma:** tras `DROP TABLE` y `DROP SCHEMA`, los objetos siguen ahí. Comprobado: 9 ficheros de una tabla `wiki_demo` tras borrarla. Cada `./mk smoke` también deja los suyos: en el stack comprobado había 20 objetos bajo `warehouse/smoke`, además del namespace `smoke`.

**Causa no confirmada.** Lo observado es que el borrado con DuckDB a través de Polaris retira la tabla del catálogo y no los ficheros. No se ha investigado si hay una forma de purgarlos al borrar.

**Remedio:** borrar la ruta en el filer, dentro del contenedor. Afecta solo a esa ruta. Cámbiala con cuidado, porque no se puede deshacer.

```bash
docker compose exec -T seaweedfs curl -s -o /dev/null -w '%{http_code}\n' -X DELETE 'http://127.0.0.1:8888/buckets/lakehouse/warehouse/wiki_demo?recursive=true'
```
```text
204
```

### El conector sale `FAILED`

**Síntoma:** `curl localhost:8083/connectors/iceberg-sink/status` muestra el conector `RUNNING` y la tarea `FAILED`, con `NoClassDefFoundError: org/apache/kafka/common/utils/AppInfoParser`. El número de versión del conector es `1.13.0-SNAPSHOT`, no `1.12.0`.

**Causa:** el Kafka de `cp-kafka-connect:8.4.0` tiene esa clase en `org.apache.kafka.common.utils.internals` (se ve en su propio log), y el sink la busca en el paquete anterior. El `1.13.0-SNAPSHOT` era un problema aparte: faltaba `version.txt` al compilar.

**Remedio ya aplicado (T-072):** [`infra/connect/Dockerfile`](../../infra/connect/Dockerfile) usa `cp-kafka-connect:8.1.0` y escribe `version.txt`. Con el stack viejo, `./mk up` reconstruye la imagen. No subas esa versión sin pasar `IcebergSinkFlowIT`.

### La tarea sale `RUNNING` pero la tabla no aparece

**Síntoma:** en el log de `connect` se repite `Commit timeout reached` seguido de `committed to 0 table(s)`, cada 30 s.

**Causa probable:** con el topic parado, la tarea solo se despierta en cada `offset.flush.interval.ms`, 60 s por defecto, y es entonces cuando lee su topic de control. Así no llega a responder dentro de la ventana de commit de 30 s. Se deduce del log, no del código del sink: el aviso de rebalanceo del consumidor de control caía justo en ese ciclo de 60 s. Lo comprobado es el efecto: con 60 s, `IcebergSinkFlowIT` no recibe la fila, y con 1 s, sí.

**Remedio ya aplicado (T-072):** `CONNECT_OFFSET_FLUSH_INTERVAL_MS: 1000` en [`compose.yaml`](../../compose.yaml).

### `connect-init` termina con 404

**Síntoma:** `./mk up` acababa con `container lakehouse-mcp-connect-init-1 exited (1)` y el log decía `iceberg-sink: 404`.

**Causa:** con el worker recién arrancado, Connect puede responder 404, y [`register.sh`](../../connect/register.sh) no reintentaba con ese código. Visto una vez.

**Remedio ya aplicado (T-072):** ahora reintenta también con 404, durante unos 2 minutos. Lo cubre `tests/test_connect_register.py` con un Connect simulado; **sin probar** en un `./mk up` real. Si aun así falla, `docker compose up connect-init` lo registra de nuevo.

### `connect-init` no aparece

**Síntoma:** `curl localhost:8083/connectors` responde `[]`, y el namespace `ecommerce` no existe.

**Causa:** contenedores levantados antes de T-012. Es el caso del stack comprobado para esta wiki.

**Remedio:** `./mk up`, que los actualiza y registra el conector.

### Falta memoria

**Síntoma (sin probar):** contenedores que se reinician o tests lentos.

**Remedio:** para tests que levanten Connect o Trino, [`docs/architecture.md`](../architecture.md#pruebas-qué-puede-verificar-cada-quién) indica apagar el stack antes. Comprueba el consumo con `docker stats --no-stream`.

## Integración continua

[`.github/workflows/ci.yml`](../../.github/workflows/ci.yml) se lanza en cada push a `develop` y `main` y en cada pull request, sin secretos ni Kaggle. Tiene dos trabajos:

| Trabajo | Qué hace | Tiempo máximo |
|---|---|---|
| `java` | JDK 25 y `mvn -B -ntp verify`, con Testcontainers sobre el Docker del runner | 60 min |
| `board-and-scripts` | Python 3.12 y `python -m pytest tests -q` | 10 min |

**Sin probar:** la ejecución en GitHub, y `mvn -B -ntp verify` fuera de la imagen `tools`. Los tiempos máximos son una previsión, no una medida.

Si `java` falla, la ejecución muestra el uso de disco y guarda los informes de los tests en el artefacto `test-reports`.

La imagen del conector no se guarda en caché y se compila en cada ejecución. Testcontainers la construye con el constructor clásico de Docker, que no aprovecha una caché de buildx ([código](https://github.com/testcontainers/testcontainers-java/blob/2.0.5/core/src/main/java/org/testcontainers/images/builder/ImageFromDockerfile.java)).

## Operaciones destructivas

Estas borran datos y no hay vuelta atrás:

| Comando | Qué borra |
|---|---|
| `docker compose down -v` | Todos los volúmenes: eventos, ficheros, catálogo y caché de Maven |
| `curl -X DELETE` al filer | La ruta indicada en SeaweedFS |
| `DELETE /subjects/...?permanent=true` | Un esquema |

Con los volúmenes de Postgres y SeaweedFS hay que ir juntos: borrar uno solo deja un catálogo sin ficheros, o ficheros sin catálogo.
