# Apache Kafka

**Estado:** ✅ funciona. Aún no le llegan eventos del proyecto: el [productor](productor.md) está en curso.

[Portada](../README.md) · [Glosario](../01-conceptos.md)

## Qué es, en cristiano

Una cinta transportadora con memoria. Unos dejan notas en un extremo, otros las recogen por el otro, cada uno a su ritmo, y las notas no desaparecen al leerlas. Las notas son [eventos](../01-conceptos.md#evento) y cada cinta con nombre es un [topic](../01-conceptos.md#topic).

## Para qué está aquí y qué pasaría sin él

Separa a quien produce los eventos de quien los archiva. Si el archivador se cae, los eventos esperan en Kafka y se recuperan al volver. Sin Kafka, el productor tendría que escribir directamente en las tablas, y cualquier caída perdería datos o pararía la reproducción. También hace posible uno de los fallos de la demo: un consumidor caído, visible como [lag](../01-conceptos.md#lag) creciente.

## Cómo se conecta

| Dirección | Quién | Puerto |
|---|---|---|
| Entrada | [Productor](productor.md) (🚧) | 9092 interno, topic `ecommerce.events` |
| Salida | [Kafka Connect](kafka-connect.md) | 9092 interno |
| Almacén interno | [Schema Registry](schema-registry.md) guarda los esquemas en el topic `_schemas` | 9092 interno |
| Desde tu ordenador | Cualquier cliente Kafka (sin probar) | 19092 publicado |
| Interno de Kafka | Controlador KRaft | 9093 |

## Cómo funciona por dentro

Un topic se divide en [particiones](../01-conceptos.md#partición), y cada una es un registro de solo añadir: un evento nuevo recibe el siguiente [offset](../01-conceptos.md#offset). Los eventos con la misma clave van a la misma partición, y dentro de ella se conserva el orden ([conceptos oficiales](https://kafka.apache.org/44/getting-started/introduction/)).

Los metadatos del clúster los gestiona un *quorum* de controladores con el protocolo KRaft, sin ZooKeeper. Aquí el mismo proceso hace de broker y controlador (modo combinado). La [documentación oficial](https://kafka.apache.org/44/operations/kraft/) lo considera adecuado para entornos pequeños y de desarrollo, y lo desaconseja en despliegues críticos.

## Cómo está configurado aquí

Servicio `kafka` en [`compose.yaml`](../../../compose.yaml), imagen `apache/kafka:4.4.0`.

| Opción | Valor | Por qué |
|---|---|---|
| `KAFKA_PROCESS_ROLES` | `broker,controller` | Un solo nodo: es lo que cabe en 7,6 GB |
| `KAFKA_LISTENERS` | `INTERNAL :9092`, `EXTERNAL :19092`, `CONTROLLER :9093` | Dos entradas porque cada cliente necesita una dirección que pueda resolver |
| `KAFKA_ADVERTISED_LISTENERS` | `kafka:9092` y `localhost:19092` | Lo que Kafka dice a cada cliente para que se reconecte |
| `*_REPLICATION_FACTOR`, `*_MIN_ISR` | 1 | Con un broker no hay réplicas posibles |
| `KAFKA_HEAP_OPTS` | `-Xmx768m` | Presupuesto de memoria |
| Volumen | `kafka-data` | Los eventos sobreviven a recrear el contenedor |

Todo va en PLAINTEXT, sin autenticación. Es una decisión para una demo local.

## Cómo probarlo tú mismo

Requiere el stack levantado ([tutorial](../03-probarlo.md)). Crea un topic de prueba, escribe, lee y bórralo.

```bash
docker compose exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --topic wiki-demo --partitions 3 --replication-factor 1
```
```text
Created topic wiki-demo.
```

```bash
docker compose exec -T kafka /opt/kafka/bin/kafka-verifiable-producer.sh --bootstrap-server localhost:9092 --topic wiki-demo --max-messages 3
```
```text
{"timestamp":...,"name":"startup_complete"}
{"timestamp":...,"name":"producer_send_success","key":null,"value":"0","partition":1,"offset":0,"topic":"wiki-demo"}
...
{"timestamp":...,"name":"tool_data","sent":3,"acked":3,"target_throughput":-1,"avg_throughput":4.6}
```

La partición elegida puede variar.

```bash
docker compose exec -T kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic wiki-demo --group wiki-demo-group --from-beginning --max-messages 3 --timeout-ms 20000
```
```text
0
1
2
Processed a total of 3 messages
```

Lag del grupo, que es lo que vigilará `pipeline_health`:

```bash
docker compose exec -T kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group wiki-demo-group
```
```text
GROUP           TOPIC           PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG
wiki-demo-group wiki-demo       0          0               0               0
wiki-demo-group wiki-demo       1          3               3               0
wiki-demo-group wiki-demo       2          0               0               0
```

Limpieza:

```bash
docker compose exec -T kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --delete --group wiki-demo-group
docker compose exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --delete --topic wiki-demo
```

## Qué puede fallar

- **Un cliente en tu ordenador no conecta por 9092.** Ese puerto no está publicado: usa `localhost:19092`.
- **El volumen pertenece a otro clúster.** Sin probar aquí. Si cambias el id del clúster o la configuración de roles con el volumen viejo, Kafka puede negarse a arrancar. Mira los logs antes de borrar nada.
- **Un topic desaparece.** El borrado es inmediato y no hay réplicas de las que recuperar.

## Para saber más

- [Conceptos de Kafka 4.4](https://kafka.apache.org/44/getting-started/introduction/)
- [Operación con KRaft](https://kafka.apache.org/44/operations/kraft/)
