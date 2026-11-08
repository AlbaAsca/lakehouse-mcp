# Cambiar el esquema sin reprocesar

Iceberg guarda el esquema en los metadatos, no en los ficheros de datos. Añadir una columna es un commit de
metadatos: los ficheros Parquet existentes no se tocan.

Lo que demuestra `AddColumnWithoutRewriteIT` (mcp-server, sin probar aún en CI):

1. Tabla con 2 ficheros de datos (`iceberg_metadata`, `content = 'DATA'`).
2. `ALTER TABLE ... ADD COLUMN channel VARCHAR` por la instancia de reparación (`RepairSession.ADD_COLUMN`).
3. La lista de ficheros de datos es idéntica; las filas viejas leen `channel` como NULL.
4. Una fila nueva trae valor y añade un fichero; los anteriores siguen ahí.
5. `AT (VERSION => <snapshot anterior>)` devuelve el esquema antiguo, sin la columna.

Ejecutar: `./mk test MODULE=mcp-server`.
