# Confluent Schema Registry

**Estado:** ✅ el servicio funciona. 🚧 El esquema de eventos existe en el repo, pero no está registrado todavía. ⬜ El test de contrato es T-043.

[Portada](../README.md) · [Glosario](../01-conceptos.md)

## Qué es, en cristiano

El registro civil de los formularios. Antes de enviar un evento con un formato nuevo, se presenta el formulario. Si rompe las reglas, se rechaza, y así nadie recibe datos que no sabe leer.

## Para qué está aquí y qué pasaría sin él

Es la base del [data contract](../01-conceptos.md#data-contract). Sin él, un productor podría cambiar un tipo de columna y el fallo aparecería más tarde, en la ingesta o en una consulta. Además, Avro no incluye el esquema en cada mensaje: el mensaje lleva su identificador y el lector lo busca aquí.

## Cómo se conecta

| Dirección | Quién | Puerto |
|---|---|---|
| Entrada | [Productor](productor.md) (🚧) registra el esquema | 8081, HTTP |
| Entrada | [Connect](kafka-connect.md) lee el esquema con `AvroConverter` | 8081, HTTP |
| Salida | [Kafka](kafka.md): guarda todo en el topic `_schemas` | 9092 interno |

Sin autenticación. Puerto publicado en tu ordenador: 8081.

## Cómo funciona por dentro

No tiene base de datos propia: usa un topic de Kafka como almacén. Los esquemas se agrupan en *subjects*. El nombre `<topic>-value`, que se ve abajo, es la convención por defecto para el valor de un mensaje.

Con compatibilidad `BACKWARD`, que es la predeterminada, quien usa el esquema nuevo debe poder leer datos escritos con el anterior. Por eso un campo nuevo necesita un valor por defecto ([evolución de esquemas](https://docs.confluent.io/platform/current/schema-registry/fundamentals/schema-evolution.html)).

## Cómo está configurado aquí

Servicio `schema-registry` en [`compose.yaml`](../../../compose.yaml), imagen `confluentinc/cp-schema-registry:8.4.0`.

| Opción | Valor | Por qué |
|---|---|---|
| `SCHEMA_REGISTRY_KAFKASTORE_BOOTSTRAP_SERVERS` | `kafka:9092` | Su almacén |
| `SCHEMA_REGISTRY_HEAP_OPTS` | `-Xmx256m` | Presupuesto de memoria |
| Compatibilidad | no se configura: `BACKWARD` por defecto | Es la de la [decisión 13](../../architecture.md#decisiones) |
| Healthcheck | `exec 3<>/dev/tcp/localhost/8081` con `bash` | [La imagen no trae curl](../04-operacion.md#las-imágenes-confluent-no-traen-curl) |

El esquema de los eventos está en [`event.avsc`](../../../producer/src/main/avro/event.avsc). La carpeta `contracts/` de la decisión 13 aún no existe.

## Cómo probarlo tú mismo

Desde Git Bash, Linux o macOS. Registra un esquema de prueba:

```bash
curl -s -X POST localhost:8081/subjects/wiki-demo-value/versions -H 'Content-Type: application/vnd.schemaregistry.v1+json' -d '{"schema":"{\"type\":\"record\",\"name\":\"Demo\",\"fields\":[{\"name\":\"id\",\"type\":\"long\"}]}"}'
```
```text
{"id":1,"version":1,"guid":"...","schemaType":"AVRO","schema":"..."}
```

El `id` puede ser otro. Ahora intenta romper el contrato con un campo obligatorio nuevo, sin valor por defecto:

```bash
curl -s -X POST localhost:8081/subjects/wiki-demo-value/versions -H 'Content-Type: application/vnd.schemaregistry.v1+json' -d '{"schema":"{\"type\":\"record\",\"name\":\"Demo\",\"fields\":[{\"name\":\"id\",\"type\":\"long\"},{\"name\":\"nota\",\"type\":\"string\"}]}"}'
```
```text
{"error_code":40901,"message":"Schema being registered is incompatible with an earlier schema for subject \"wiki-demo-value\", details: [{errorType:\"READER_FIELD_MISSING_DEFAULT_VALUE\", description:\"The field 'nota' at path '/fields/1' in the new schema has no default value and is missing in the old schema\", ...
```

Con un valor por defecto sí es compatible:

```bash
curl -s -X POST localhost:8081/compatibility/subjects/wiki-demo-value/versions/latest -H 'Content-Type: application/vnd.schemaregistry.v1+json' -d '{"schema":"{\"type\":\"record\",\"name\":\"Demo\",\"fields\":[{\"name\":\"id\",\"type\":\"long\"},{\"name\":\"nota\",\"type\":[\"null\",\"string\"],\"default\":null}]}"}'
```
```text
{"is_compatible":true}
```

Limpieza. Hace falta borrar dos veces: la primera marca, la segunda elimina.

```bash
curl -s -X DELETE localhost:8081/subjects/wiki-demo-value
curl -s -X DELETE 'localhost:8081/subjects/wiki-demo-value?permanent=true'
```
```text
[1]
[1]
```

Otras lecturas:

```bash
curl -s localhost:8081/config
```
```text
{"compatibilityLevel":"BACKWARD"}
```

## Qué puede fallar

- **Error 409 con `error_code` 40901.** El esquema rompe la compatibilidad. Es el comportamiento buscado.
- **El borrado permanente falla.** La [API](https://docs.confluent.io/platform/current/schema-registry/develop/api.html) exige un borrado normal antes.
- **Arranca antes que Kafka.** Compose lo evita con `depends_on`. Si Kafka no está sano, Schema Registry no arranca.

## Para saber más

- [API de Schema Registry](https://docs.confluent.io/platform/current/schema-registry/develop/api.html)
- [Evolución y compatibilidad](https://docs.confluent.io/platform/current/schema-registry/fundamentals/schema-evolution.html)
