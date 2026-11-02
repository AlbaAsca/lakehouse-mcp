# Notas del investigador

Comprobado = leído en la fuente citada. No verificado = deducción o dato sin probar en este stack. Nada de esto se ha ejecutado.

## 2026-11-02 · Aislar DuckDB dentro del motor

**Problema:** T-019 confía en `SqlGuard` como única barrera, y T-016 solo fija tiempo y memoria. Un fallo del validador daría acceso a ficheros y credenciales del contenedor.

| Opción | Coste | Licencia | Calidad | Esfuerzo |
|---|---|---|---|---|
| **Ajustes propios de DuckDB (recomendada)** | 0 | la de DuckDB, sin dependencias nuevas | segunda barrera en el motor | bajo |
| Solo validador (situación actual) | 0 | – | una sola barrera | ninguno |
| Sandbox del contenedor (solo lectura, seccomp) | 0 | – | no impide leer lo que el proceso ya ve (no verificado) | medio; choca con `tools` y el spill |

**Comprobado** ([Securing DuckDB 2.0](https://duckdb.org/docs/2.0/operations_manual/securing_duckdb/overview)):
- Orden documentado: `LOAD httpfs` → `allowed_directories = ['s3://bucket/prefix/']` → `enable_external_access = false` → `disabled_filesystems = 'LocalFileSystem'` → `lock_configuration = true`.
- Las rutas permitidas también admiten escritura: no sustituyen a la regla de solo lectura de `SqlGuard`.
- Quitar `LocalFileSystem` impide después `INSTALL` y `LOAD`. El almacén de secretos persistentes debe usarse antes del bloqueo o desactivarse con `allow_persistent_secrets = false`.
- Para HTTPS, `httpfs` lee un paquete de CA local; no afecta si SeaweedFS va por HTTP (no verificado).
- Límites recomendados: `threads`, `memory_limit`, `max_temp_directory_size`.
- También: `autoload_known_extensions = false`, `autoinstall_known_extensions = false`, `allow_community_extensions = false`.

**No verificado (lo decide la tarjeta):**
- Si la extensión `iceberg` sigue hablando con Polaris por REST con `enable_external_access = false`. La página solo habla de `ATTACH` de ficheros. Si falla, se puede probar el `ATTACH` antes del bloqueo.
- Si el derrame a disco funciona sin `LocalFileSystem`. Alternativa: dejar `LocalFileSystem` activo y limitar con `allowed_directories`.
- Si los ajustes valen para toda la instancia y cubren así todo el pool de conexiones.

**Recomendación:** aplicar los ajustes en `LakehouseSession` y probar con el validador desactivado.

**Tarjeta pendiente, NO creada** (permiso denegado en `board.py add`). Datos para crearla:
- Título: «Consulta: que DuckDB tampoco pueda salirse aunque falle el validador». Dueño `desarrollador`, 30 min, prioridad media, deps T-014, T-016, T-019.
- Descripción: hoy solo el validador impide que una consulta del agente lea ficheros del contenedor; se propone activar también los ajustes de seguridad de DuckDB. La documentación no aclara si Iceberg sigue funcionando con todo bloqueado: lo decide la prueba.
- Cómo sabremos si funciona: con el validador desactivado, leer un fichero local o cambiar un ajuste falla en el motor, y las consultas a Iceberg dan las mismas filas.
- Técnico: los ajustes en el orden indicado arriba, en `LakehouseSession`. Test sin `SqlGuard` en cada conexión del pool: `read_csv('/etc/passwd')`, `glob('/*')`, `COPY` a fichero local, `ATTACH` de fichero, `SET lock_configuration = false` e `INSTALL` deben fallar; `SELECT` sobre `lh` devuelve las filas esperadas. Si no se puede bloquear todo, documentar qué queda en `docs/architecture.md`. `./mk test`.

## 2026-11-02 · Caché de prompts en el agente

**Problema:** en cada vuelta del bucle se reenvían herramientas, system e historial. T-035 pone topes de gasto, pero nada lo reduce, y su cálculo con «un precio configurable» sería incorrecto con caché.

| Opción | Coste | Licencia | Calidad | Esfuerzo |
|---|---|---|---|---|
| **`CONVERSATION_HISTORY` + `cacheToolResults` (recomendada, a medir)** | ver cálculo | ya incluido en Spring AI | no cambia el modelo; respuestas no deberían variar (no verificado) | bajo |
| `SYSTEM_AND_TOOLS` | ver cálculo | igual | ahorra solo el prefijo fijo | bajo |
| Sin caché | 100 % | – | – | ninguno |

**Comprobado:**
- Precios sobre la entrada base: escritura 5 min 1,25×, escritura 1 h 2×, lectura 0,1× en la mayoría de modelos (0,05× en Opus 5.5 y Sonnet 5.5). El orden del prefijo es `tools` → `system` → `messages` ([Anthropic](https://platform.claude.com/docs/en/docs/build-with-claude/prompt-caching)).
- Mínimo cacheable según modelo: 512 tokens en Haiku 5.5, 4.096 en Haiku 4.5. Por debajo no se cachea y no da error: el modelo barato de T-060 cambia el resultado.
- Spring AI tiene las estrategias `TOOLS_ONLY`, `SYSTEM_ONLY`, `SYSTEM_AND_TOOLS` y `CONVERSATION_HISTORY` ([Javadoc 2.0.1](https://docs.spring.io/spring-ai/docs/2.0.1/api/org/springframework/ai/anthropic/AnthropicCacheStrategy.html)).
- `cacheToolResults` (desde 2.0.0) cachea resultados entre rondas de herramientas, solo con `CONVERSATION_HISTORY` ([Javadoc 2.0.1](https://docs.spring.io/spring-ai/docs/2.0.1/api/org/springframework/ai/anthropic/AnthropicCacheOptions.html)).
- Cambiar las definiciones de herramientas invalida las cachés siguientes ([blog de Spring](https://spring.io/blog/2025/10/27/spring-ai-anthropic-prompt-caching-blog/), octubre de 2025; sus ejemplos de código pueden estar desfasados).

**Cálculo propio (no medido):** con 5 peticiones sobre el mismo prefijo en 5 min y lectura a 0,1×, cuesta 1,25 + 4 × 0,1 = 1,65 frente a 5, unos 67 % menos solo en esa parte. No incluye salida ni el texto nuevo de cada vuelta, así que el ahorro total será menor.

**No verificado:**
- Comportamiento en el bucle interno de herramientas de Spring AI: el blog no lo trata.
- Que la API no cambie en 2.0.2, la versión del `pom.xml`; se leyó la 2.0.1.
- Si una pregunta suelta llega al mínimo cacheable.
- Si el orden de las herramientas que devuelve el cliente MCP es estable entre peticiones.

**Recomendación:** activarla por configuración, separar los tipos de token en el cálculo de gasto y decidir con una medición real.

**Tarjeta pendiente, NO creada** (permiso denegado en `board.py add`). Datos para crearla:
- Título: «Agente: gastar menos con la caché de prompts y calcular bien el coste». Dueño `desarrollador`, 25 min, prioridad media, dep T-035.
- Descripción: en cada vuelta el agente reenvía herramientas, instrucciones e historial, y se paga cada vez. Se propone activar la caché por configuración y contar sus tokens a su precio real en el tope de gasto. Se mantiene solo si la medición lo justifica.
- Cómo sabremos si funciona: con modelo simulado, el coste calculado coincide con el esperado por tipo de token; en una prueba real de 3 preguntas, las lecturas de caché son mayores que cero y el coste por pregunta baja respecto a la misma prueba sin caché.
- Técnico: `AnthropicCacheOptions` con `CONVERSATION_HISTORY` y `cacheToolResults`, activable por propiedad, `NONE` como alternativa. Gasto de T-035 separado en entrada, escritura y lectura de caché y salida, con precios configurables. Revisar `EVAL_MODEL` (T-060) por el mínimo cacheable. Sin datos variables en el system. Tests con `ChatModel` simulado en `./mk test`; medición real como nota en T-061.

## 2026-11-02 · Anotaciones de herramientas MCP (sin tarjeta, para T-020 a T-026)

`@McpTool` admite `annotations = @McpTool.McpAnnotations(...)` con `readOnlyHint`, `destructiveHint` e `idempotentHint` ([referencia de Spring AI 2.1](https://docs.spring.io/spring-ai/reference/2.1/api/mcp/mcp-annotations-server.html); no se ha comprobado la página de 2.0.x). Coste casi nulo: marcar lecturas como `readOnlyHint = true` y `apply_fix` como `destructiveHint = true`. Son pistas para el cliente, no control de acceso: la barrera sigue siendo RBAC y la aprobación (decisiones 9 y 11).
