# Imagen `tools`

**Estado:** ✅ funciona.

[Portada](../README.md) · [Glosario](../01-conceptos.md)

## Qué es, en cristiano

Una caja de herramientas en un contenedor. En lugar de instalar Java, Maven, make y demás en tu ordenador, los usas desde esta caja, y al terminar la caja se recoge.

## Para qué está aquí y qué pasaría sin él

Permite clonar el proyecto y trabajar con solo Docker. Sin ella, cada persona tendría que instalar JDK 25, Maven, make, DuckDB, Python y la CLI de Kaggle en las versiones correctas.

## Cómo se conecta

| Montaje o red | Para qué |
|---|---|
| `.:/workspace` | Tu copia del repo. Es el único [bind mount](../01-conceptos.md#bind-mount) de código |
| `m2:/root/.m2` | Caché de Maven |
| `data:/data` | Dataset descargado |
| `~/.kaggle:/root/.kaggle:ro` | Credenciales de Kaggle, de solo lectura |
| `/var/run/docker.sock` | Para poder lanzar `docker compose` y Testcontainers desde dentro |
| Red de Compose | Alcanza todos los servicios por su nombre |

`env_file: [.env]` carga tus variables. No publica puertos.

## Cómo funciona por dentro

[`mk`](../../../mk) y [`mk.cmd`](../../../mk.cmd) ejecutan `docker compose run --rm tools make <objetivo>`. El [`Makefile`](../../../Makefile) corre dentro del contenedor. [`mvnw`](../../../mvnw) hace lo mismo con `mvn`, sin descargar nada.

Cuando `make up` corre dentro de `tools`, habla con el Docker de tu ordenador a través del socket. De ahí la regla «[sin bind mounts fuera de `tools`](../05-decisiones.md)»: las rutas de dentro no existen fuera.

## Cómo está configurado aquí

[`infra/tools/Dockerfile`](../../../infra/tools/Dockerfile), sobre `maven:3.10.0-eclipse-temurin-25-noble`, con perfil `tools` en [`compose.yaml`](../../../compose.yaml): `up` no lo arranca.

| Objetivo | Estado |
|---|---|
| `up`, `down`, `smoke` | ✅ `down` y `smoke` probados. ⚠️ `up` puede terminar con error por `connect-init` ([ver operación](../04-operacion.md#connect-init-termina-con-404)) |
| `test-board` | ✅ probado |
| `test`, `test MODULE=<módulo>` | ✅ probado con `./mk` |
| `download-data` | Sin probar: requiere credenciales de Kaggle |
| `seed` | Sin probar: requiere el CSV real. El comando que lanza sí está probado con la muestra sintética (`ProducerIT`) |
| `seed-sample` | ✅ probado con `./mk` y el stack arriba: Kafka confirma las 50.000 filas |
| `evals`, `chaos`, `demo` | ⬜ responden `not implemented yet` |

## Cómo probarlo tú mismo

```bash
docker compose run --rm -T tools sh -c 'java -version 2>&1 | head -1; mvn -v | head -1; make --version | head -1; duckdb --version; kaggle --version 2>&1 | head -2; docker --version; docker compose version; /opt/py/bin/python --version; /opt/py/bin/python -c "import six; print(\"six\", six.__version__)"'
```
```text
openjdk version "25.0.5" 2026-10-20 LTS
Apache Maven 3.10.0 (c43a36b8d67be7e0805a411bc0898af1a51f5472)
GNU Make 4.3
v2.0.0 (Cyanoptera) d54cbbe3df
Kaggle CLI 2.2.5
Docker version 29.1.3, build 29.1.3-0ubuntu3~24.04.2
Docker Compose version 2.40.3+ds1-0ubuntu1~24.04.1
Python 3.12.3
six 1.17.0
```

Tests del panel, que no tocan el stack:

```bash
./mk test-board
```
```text
88 passed, 1 warning in 10.91s
```

La cifra de tests crecerá con el proyecto.

Tests Java de un solo módulo. Primero compila e instala sus dependencias en la caché de Maven sin probarlas, y luego ejecuta solo los tests de ese módulo, también los de contenedores. Sin `MODULE` se pasa la suite completa:

```bash
./mk test MODULE=producer
```

Si un test falla o el módulo no existe, termina con error, igual que la suite completa. `mk.cmd test MODULE=<módulo>` pasa los mismos argumentos, pero está sin probar.

Un objetivo pendiente:

```bash
./mk evals
```
```text
evals: not implemented yet, see the board
make: *** [Makefile:49: evals] Error 1
```

## Qué puede fallar

- **`kaggle` falla con un error de `six`.** Ya resuelto: [el Dockerfile instala `six`](../04-operacion.md#maven-make-y-kaggle-viven-en-tools). Si tu imagen es vieja, reconstruye `tools`.
- **Compose exige `.env`.** Sin él, `env_file` da error. Copia [`.env.example`](../../../.env.example).
- **No tienes `~/.kaggle`.** En la sintaxis corta de volúmenes, Compose [crea la ruta si no existe](https://docs.docker.com/reference/compose-file/services/#volumes), así que quedará una carpeta vacía. Solo importa para `download-data`.
- **Rutas transformadas en Git Bash.** Pon `MSYS_NO_PATHCONV=1` delante si una ruta de contenedor sale con `C:/Program Files/Git/...`.
- **Dos copias del repo probando a la vez.** `test MODULE=<módulo>` instala las dependencias en la caché de Maven, que comparten todas las copias: una podría usar el `testkit` que acaba de instalar la otra.
- **Se acumula memoria.** `tools` y `testkit` ocupan RAM. Para tests que levantan Connect, [`docs/architecture.md`](../../architecture.md#pruebas-qué-puede-verificar-cada-quién) recomienda apagar el stack.

## Para saber más

- [Volúmenes en Compose](https://docs.docker.com/reference/compose-file/services/#volumes)
- [Testcontainers](https://java.testcontainers.org/)
