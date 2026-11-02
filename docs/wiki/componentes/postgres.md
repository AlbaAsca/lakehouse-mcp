# PostgreSQL

**Estado:** ✅ base `polaris` en uso. ⬜ La base `ops` existe vacía: propuestas de arreglo y auditoría son T-026 y T-053.

[Portada](../README.md) · [Glosario](../01-conceptos.md)

## Qué es, en cristiano

Una base de datos clásica. Aquí no guarda eventos: es el cuaderno donde [Polaris](polaris.md) apunta qué catálogos y tablas existen, para no olvidarlo cuando se reinicia.

## Para qué está aquí y qué pasaría sin él

Sin Postgres, Polaris trabaja en memoria, y [cada reinicio perdía el catálogo](../04-operacion.md#polaris-pierde-el-catálogo-al-reiniciar) aunque los ficheros siguieran en S3.

## Cómo se conecta

| Dirección | Quién | Puerto |
|---|---|---|
| Entrada | Polaris y `polaris-bootstrap` | 5432, **no publicado** |
| Entrada prevista | [Servidor MCP](servidor-mcp.md), base `ops` (⬜) | 5432 |
| Salida | ninguna | |

Solo se alcanza desde la red de Compose, o con `docker compose exec`.

## Cómo funciona por dentro

Polaris no crea su esquema: la [documentación](https://polaris.apache.org/releases/1.8.0/metastores/relational-jdbc/) exige que exista antes. El bootstrap aplica las tablas y anota la versión en `polaris_schema.version`.

## Cómo está configurado aquí

Servicio `postgres` en [`compose.yaml`](../../../compose.yaml), imagen [`infra/postgres/Dockerfile`](../../../infra/postgres/Dockerfile) sobre `postgres:18-alpine`. Versión observada: 18.6.

| Pieza | Valor | Por qué |
|---|---|---|
| [`init.sql`](../../../infra/postgres/init.sql) | Crea `polaris_schema` y la base `ops` | Va dentro de la imagen, porque no hay bind mounts. Solo corre con el volumen vacío |
| Volumen | `postgres-data:/var/lib/postgresql` | Desde la versión 18 la imagen oficial [mueve `PGDATA` a una ruta por versión y el `VOLUME` a este directorio](https://github.com/docker-library/docs/pull/2582.diff) |
| Healthcheck | `pg_isready -h 127.0.0.1` | Por TCP: durante `initdb` el servidor temporal solo escucha en el socket unix |
| `POSTGRES_PASSWORD` | De `.env`, con un valor por defecto en `compose.yaml` | Un `.env` antiguo sin esta variable sigue funcionando |

## Cómo probarlo tú mismo

```bash
docker compose exec -T postgres psql -U polaris -d polaris -c '\dt polaris_schema.*'
```
```text
                          List of tables
     Schema     |             Name              | Type  |  Owner
----------------+-------------------------------+-------+---------
 polaris_schema | commit_metrics_report         | table | polaris
 polaris_schema | entities                      | table | polaris
 polaris_schema | events                        | table | polaris
 polaris_schema | grant_records                 | table | polaris
 polaris_schema | policy_mapping_record         | table | polaris
 polaris_schema | principal_authentication_data | table | polaris
 polaris_schema | scan_metrics_report           | table | polaris
 polaris_schema | version                       | table | polaris
(8 rows)
```

En Git Bash, antepón `MSYS_NO_PATHCONV=1` si el comando se queja de rutas.

Ver cómo Polaris apunta el catálogo:

```bash
docker compose exec -T postgres psql -U polaris -d polaris -c "SELECT name, type_code FROM polaris_schema.entities WHERE name = 'lakehouse';"
```
```text
   name    | type_code
-----------+-----------
 lakehouse |         4
(1 row)
```

No consultes `principal_authentication_data`: contiene material de autenticación.

## Qué puede fallar

- **`init.sql` no se ejecuta.** Solo corre con el volumen vacío. Un volumen de un arranque anterior conserva lo que tuviera.
- **`POSTGRES_PASSWORD` cambiada con el volumen ya creado.** Postgres mantiene la contraseña inicial, y Polaris dejaría de conectar. Comportamiento de la imagen oficial, sin probar aquí.
- **Volumen en otra ruta.** Montar en `/var/lib/postgresql/data` en la versión 18 no guarda los datos donde se espera.

## Para saber más

- [Imagen oficial de Postgres](https://hub.docker.com/_/postgres)
- [Polaris y Relational JDBC](https://polaris.apache.org/releases/1.8.0/metastores/relational-jdbc/)
