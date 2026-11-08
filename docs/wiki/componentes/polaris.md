# Apache Polaris

**Estado:** ✅ funciona, con el catálogo persistente en [Postgres](postgres.md) (T-002, T-005).

[Portada](../README.md) · [Glosario](../01-conceptos.md)

## Qué es, en cristiano

El bibliotecario. No guarda los libros, pero sabe en qué estante está la edición vigente de cada uno y quién puede pedirlo. Es un [catálogo](../01-conceptos.md#catálogo) de [Iceberg](iceberg.md) al que se habla por HTTP.

## Para qué está aquí y qué pasaría sin él

Hace que todos vean la misma tabla. Sin catálogo, cada cliente tendría que saber la ruta del último fichero de metadatos, y dos escritores podrían pisarse. También gestiona permisos.

## Cómo se conecta

| Dirección | Quién | Puerto |
|---|---|---|
| Entrada | [DuckDB](duckdb.md), `lakehouse-init`, [Connect](kafka-connect.md) (🚧) | 8181, OAuth2 |
| Entrada | Healthcheck de Compose | 8182, `/q/health` |
| Salida | [Postgres](postgres.md) | 5432, JDBC |
| Salida | [SeaweedFS](seaweedfs.md) | 8333, S3 |

## Cómo funciona por dentro

Implementa la API REST de catálogo de Iceberg. Un cliente pide un token en `/api/catalog/v1/oauth/tokens`, y con él consulta y confirma cambios en tablas. La administración va por otra API, `/api/management/v1`. Es una aplicación Quarkus: por eso las propiedades se llaman `quarkus.*` y la salud está en `/q/health`.

Un *realm* es un espacio de nombres de nivel superior, y un *catálogo* cuelga de él. Aquí: realm `POLARIS_REALM` y catálogo `lakehouse`.

La [documentación](https://polaris.apache.org/releases/1.8.0/metastores/) dice que el backend `in-memory` por defecto no es apto para producción, porque pierde los datos al reiniciar, y recomienda `relational-jdbc`. Es [lo que pasó aquí](../04-operacion.md#polaris-pierde-el-catálogo-al-reiniciar).

## Cómo está configurado aquí

Servicios en [`compose.yaml`](../../../compose.yaml), imágenes `apache/polaris:1.8.0` y `apache/polaris-admin-tool:1.8.0`.

| Pieza | Qué hace |
|---|---|
| `x-polaris-persistence` | Bloque común de servidor y admin tool: `polaris.persistence.type=relational-jdbc`, usuario, `POSTGRES_PASSWORD` y URL JDBC |
| `polaris-bootstrap` | Un solo uso. Crea las tablas y el usuario raíz (`POLARIS_REALM`, `POLARIS_CLIENT_ID`, `POLARIS_CLIENT_SECRET`) |
| `polaris` | El servidor. `JAVA_OPTS_APPEND=-Xmx512m` |
| `lakehouse-init` | Un solo uso, con [`infra/polaris/init.sh`](../../../infra/polaris/init.sh): bucket, catálogo, permisos y namespace `ecommerce` |

Opciones del catálogo que importan:

| Opción | Valor | Por qué |
|---|---|---|
| `stsUnavailable` | `true` | [Sin STS](../05-decisiones.md#seaweedfs-sin-sts). La [documentación](https://polaris.apache.org/releases/1.8.0/configuration/configuring-polaris-for-production/configuring-aws-s3-cloud-storage-specific/) indica que Polaris omite el *credential vending* y el cliente accede directamente al almacén |
| `pathStyleAccess` | `true` | Para backends sin direcciones estilo `bucket.host` |
| `endpoint` | `http://seaweedfs:8333` | Dónde está el S3 |
| `default-base-location` | `s3://<S3_BUCKET>/warehouse` | Raíz de las tablas |
| `allowedLocations` | `s3://<S3_BUCKET>/` | Límite de lo que se puede registrar |

`init.sh` concede `CATALOG_MANAGE_CONTENT` a `catalog_admin` y asigna ese rol a `service_admin`, el rol con el que entra el usuario raíz. Sin eso, el usuario raíz no puede crear tablas.

## Cómo probarlo tú mismo

Salud, sin credenciales:

```bash
curl -s localhost:8182/q/health/ready
```
```text
{
    "status": "UP",
    "checks": [
        {
            "name": "MongoDB connection health check",
            "status": "UP"
        },
        {
            "name": "Database connections health check",
            "status": "UP",
...
```

El chequeo de MongoDB aparece aunque aquí no hay MongoDB. Se observó en `UP` y no influye en el resultado de esta instalación.

La API exige token:

```bash
curl -s -o /dev/null -w '%{http_code}\n' localhost:8181/api/catalog/v1/config
```
```text
401
```

Con el script de apoyo [`polaris.sh`](../ejemplos/polaris.sh), que pide un token sin imprimirlo:

```bash
docker compose run --rm -T tools sh docs/wiki/ejemplos/polaris.sh /api/management/v1/catalogs
```
```text
{
  "catalogs": [
    {
      "type": "INTERNAL",
      "name": "lakehouse",
      "properties": {
        "default-base-location": "s3://lakehouse/warehouse"
      },
      ...
      "storageConfigInfo": {
        "region": "us-east-1",
        "endpoint": "http://seaweedfs:8333",
        "stsUnavailable": true,
        "pathStyleAccess": true,
        "storageType": "S3",
        ...
```

```bash
docker compose run --rm -T tools sh docs/wiki/ejemplos/polaris.sh /api/catalog/v1/lakehouse/namespaces
```

Devuelve los namespaces. `smoke` aparece después de ejecutar `./mk smoke`. El stack comprobado se levantó antes de T-012, así que no incluía `ecommerce`, que crea el `init.sh` actual.

**Sin probar para esta wiki:** el reinicio de Polaris está prohibido en esta sesión. Según la tarjeta T-005: «up, restart de polaris, smoke y up de nuevo: todo en verde; el init devuelve 409, el catálogo sobrevive». `PolarisPersistenceIT` lo cubre en contenedores de prueba.

## Qué puede fallar

- **`HTTP 400 Credential vending was requested`.** Te falta `ACCESS_DELEGATION_MODE 'none'` en el ATTACH. [Detalle](../04-operacion.md#error-400-de-credential-vending).
- **HTTP 500 al crear una tabla llamada `snapshots`** (`invalid_path_key_for_passthrough_resolved_path`). Comprobado con ese nombre. Parece que Polaris lo toma por una tabla de metadatos de Iceberg, así que otros nombres de ese tipo podrían fallar igual. `history` también falla (HTTP 500, comprobado en `LakehouseSessionIT`); `events_history` funciona. `files` y el resto: sin probar.
- **404 del catálogo tras reiniciar.** Contenedores de antes de T-002. [Remedio](../04-operacion.md#polaris-pierde-el-catálogo-al-reiniciar).
- **`POLARIS_CLIENT_SECRET` cambiado en `.env`.** El bootstrap omite un realm ya creado, así que el secreto viejo sigue vigente. Deducido del log `already bootstrapped; skipping`, sin probar.
- **`lakehouse-init` en error.** `check` en [`init.sh`](../../../infra/polaris/init.sh) imprime el código y el cuerpo. `catalog: 409` es normal.

## Para saber más

- [Metastores en Polaris 1.8.0](https://polaris.apache.org/releases/1.8.0/metastores/)
- [Relational JDBC](https://polaris.apache.org/releases/1.8.0/metastores/relational-jdbc/)
- [Almacenamiento S3](https://polaris.apache.org/releases/1.8.0/configuration/configuring-polaris-for-production/configuring-aws-s3-cloud-storage-specific/)
