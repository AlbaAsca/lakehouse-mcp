# Claude Desktop contra el servidor MCP

El servidor del stack (`./mk up`) escucha en `http://127.0.0.1:8090/mcp` (protocolo Streamable HTTP) y
exige `Authorization: Bearer <clave>` con la clave de analista o de administrador (`MCP_ANALYST_KEY` / `MCP_ADMIN_KEY` de `.env`).

## Qué hay que instalar en el equipo

- **Node.js** (para `npx`). Claude Desktop, en `claude_desktop_config.json`, solo lanza servidores locales por stdio;
  no habla HTTP con un `localhost`. El puente `mcp-remote` traduce stdio ↔ HTTP.
- El «conector personalizado» de Claude Desktop (Ajustes → Conectores) lo llama la nube de Anthropic y exige una URL
  pública HTTPS, así que no sirve para `127.0.0.1`. Tampoco lo recomendamos: expondría el lakehouse.

Estado: configuración **sin probar** con Claude Desktop (tarjeta T-030, manual). Los nombres de `mcp-remote`
y sus opciones conviene revisarlos en su documentación antes de usarlos.

## Configuración

Ejemplo en `docs/claude-desktop.config.example.json`; cópialo dentro de `claude_desktop_config.json`
(Windows: `%APPDATA%\Claude\`; macOS: `~/Library/Application Support/Claude/`) cambiando la clave:

```json
{
  "mcpServers": {
    "lakehouse": {
      "command": "npx",
      "args": ["-y", "mcp-remote", "http://127.0.0.1:8090/mcp",
               "--header", "Authorization:${LAKEHOUSE_AUTH}", "--allow-http"],
      "env": {"LAKEHOUSE_AUTH": "Bearer <MCP_ANALYST_KEY>"}
    }
  }
}
```

(La clave va en `env` y no en `args` porque Windows trocea los espacios de los argumentos.)

Reinicia Claude Desktop. Con la clave de analista ofrece ocho herramientas: `list_tables`, `describe_table`,
`run_query`, `time_travel`, `pipeline_health`, `data_quality_check`, `list_metrics`, `get_metric`.
`propose_fix` y `apply_fix` solo aparecen con la clave de administrador.
