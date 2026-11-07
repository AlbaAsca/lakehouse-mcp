# Testkit: tests con contenedores

**Estado:** ✅ usado por los tests de integración (`*IT`) de los demás módulos.

## Para qué está aquí

Muchos fallos solo aparecen con Kafka, Connect, Polaris y SeaweedFS de verdad. El testkit levanta ese stack en contenedores desde los tests, para que cada módulo compruebe su trabajo sin tocar el stack de desarrollo.

## Qué contiene

- `LakehouseStack`: arranca y espera a que estén sanos los servicios que necesita el test.
- `ConnectWorker`: un worker de Kafka Connect con el conector hacia Iceberg.
- `SyntheticLoader`: carga la muestra sintética (`testdata/synthetic-events.csv.gz`, sin datos reales).

## Cuándo se ejecuta

El desarrollador y el tester solo pasan los tests de su módulo (`./mk test MODULE=<módulo>`). Si se toca `testkit`, `compose.yaml`, `connect/` o `infra/`, hay que pasar también los tests de contenedores antes de cerrar la tarjeta.

Código: [`testkit/`](../../testkit/src/main/java/io/github/albaasca/lakehouse/testkit/).
