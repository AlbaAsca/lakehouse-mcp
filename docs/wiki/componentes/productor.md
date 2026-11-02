# Productor

**Estado:** 🚧 en curso. El lector de CSV, el esquema, el muestreo por sesión, la reordenación y la aceleración están en la rama, con sus tests en verde (T-008, T-011). El envío a Kafka y el comando (T-013) funcionan contra Kafka y Schema Registry en contenedores, con la muestra sintética. Falta probar `./mk seed` con el CSV real y con el conector.

[Portada](../README.md) · [Glosario](../01-conceptos.md)

## Qué es, en cristiano

Un reproductor. Coge un fichero con todo lo que pasó en una tienda durante un mes y lo vuelve a «tocar» en Kafka, evento a evento, en orden y a la velocidad que le pidas. Como poner a cámara rápida la grabación de un mes.

## Para qué está aquí y qué pasaría sin él

Convierte un fichero estático en un flujo en directo, para que todo lo demás se comporte como en un sistema real. Sin él no habría eventos que ingerir.

## Cómo se conecta

| Dirección | Quién | Puerto |
|---|---|---|
| Entrada | CSV en `/data/raw` (volumen `data` de `tools`) | fichero |
| Salida | [Kafka](kafka.md), topic `ecommerce.events` | 9092 |
| Salida | [Schema Registry](schema-registry.md) | 8081 |

## Cómo funciona por dentro

**En la rama:**

- [`event.avsc`](../../../producer/src/main/avro/event.avsc): esquema Avro con las nueve columnas. `category_code`, `brand` y `user_session` admiten nulos. `event_time` es `timestamp-micros`.
- [`CsvEventReader`](../../../producer/src/main/java/io/github/albaasca/lakehouse/producer/CsvEventReader.java): lee línea a línea, así que el tamaño del fichero no importa. Detecta gzip por el contenido, no por el nombre. Las filas ilegibles se cuentan y se saltan. Una cabecera incorrecta o un error de lectura detienen todo, para no presentar como completo un fichero roto.
- [`KafkaEventSink`](../../../producer/src/main/java/io/github/albaasca/lakehouse/producer/KafkaEventSink.java) y [`SeedCommand`](../../../producer/src/main/java/io/github/albaasca/lakehouse/producer/SeedCommand.java) (T-013): crean el topic `ecommerce.events` con 6 particiones si no existe; si existe, lo dejan como está. La clave es `user_session`, para que una sesión quede en una sola partición y conserve su orden. Las filas sin sesión van sin clave y sin orden garantizado. Opciones `--input`, `--sample-rate`, `--max-rows`, `--speedup`, `--rewrite-timestamp`, `--bootstrap-servers` y `--schema-registry-url`. Cada 10 segundos escribe en el log cuántos eventos lleva. Termina con código 0 solo si ha enviado algo y Kafka lo ha confirmado todo.

Los precios a cero o negativos y los duplicados pasan tal cual, a propósito: son los casos de calidad de la demo.

**Implementado (T-011):** muestreo por sesión completa, ventana de reordenación acotada, aceleración y reescritura de la hora. Se explica en la [decisión 14](../../architecture.md#decisiones).

## Cómo está configurado aquí

- **Java 25 y Spring Boot 4.1.2**, en [`pom.xml`](../../../pom.xml). Avro sin generación de código: el esquema se carga como recurso.
- **Serializador:** `kafka-avro-serializer` `8.4.0`.
- **Makefile:** [`seed`](../../../Makefile) usa `MONTH ?= 2019-Oct`, `SAMPLE ?= 0.01` y `SPEEDUP ?= 3600`, compila el jar y espera el CSV en `/data/raw`. Se cambian así (sin probar, como todo `seed`): `./mk seed MONTH=2019-Nov SAMPLE=0.05`.
- **Datos de prueba:** [`testdata/synthetic-events.csv.gz`](../../../testdata/synthetic-events.csv.gz), 50.000 filas inventadas con casos sucios y una caída plantada. Lo describe [`infra/synthetic/README.md`](../../../infra/synthetic/README.md).

## Cómo probarlo tú mismo

Comprobado: la muestra tiene las columnas esperadas y 50.000 filas.

```bash
python -c "import gzip,csv; r=csv.reader(gzip.open('testdata/synthetic-events.csv.gz','rt',encoding='utf-8')); print(next(r)); print(sum(1 for _ in r))"
```
```text
['event_time', 'event_type', 'product_id', 'category_id', 'category_code', 'brand', 'price', 'user_id', 'user_session']
50000
```

Comprueba que compila y que sus tests pasan (forma parte de la suite completa):

```bash
./mk test
```

Termina con código 0 si todo va bien. Entre sus tests, `ProducerIT` lanza el jar empaquetado, el mismo que usa `make seed`: envía la muestra a Kafka y Schema Registry en contenedores, lee el topic y lo compara sesión a sesión con el CSV. También comprueba los códigos de salida con argumentos incorrectos (2) y con Schema Registry inalcanzable (1). La salida del jar solo se muestra si el test falla.

**Sin probar:** `./mk seed` con el stack levantado. Necesita el CSV real, y el conector aún falla (T-072).

## Qué puede fallar

- **`Unexpected CSV header`.** El fichero no tiene las columnas esperadas.
- **Avisos `Skipping unreadable row`.** Normal en datos sucios: mira el total en `Skipped ... unreadable rows`.
- **`No events sent: the sample is empty`.** Si `--max-rows` es menor que la primera cubeta con filas, la muestra sale vacía. El comando lo avisa y termina con código 1: sube el límite.
- **`Seed failed` al arrancar.** Kafka o Schema Registry no responden: comprueba que el stack está levantado con `./mk up`.
- **Orden del CSV real.** Sin perfil todavía (T-007). La ventana de orden se contrastará en T-070.

## Para saber más

- [Dataset en Kaggle](https://www.kaggle.com/datasets/mkechinov/ecommerce-behavior-data-from-multi-category-store). Licencia «© Original Authors»: no se sube al repo.
- [Productores de Kafka](https://kafka.apache.org/44/getting-started/introduction/)
- [Apache Avro](https://avro.apache.org/docs/)
