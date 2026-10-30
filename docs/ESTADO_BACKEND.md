# Estado del backend: proyectos y participantes

Proyectos y participantes exponen CRUD REST persistido mediante Spring Data JPA y PostgreSQL. Un participante pertenece a un proyecto mediante una relación `ManyToOne`, representada en la API por `proyectoId`. Los emails se normalizan y son únicos dentro de cada proyecto; no hay borrado en cascada y no se permite eliminar un proyecto con participantes asociados.

La conexión PostgreSQL se configura mediante `DATABASE_URL`, `DATABASE_USER` y `DATABASE_PASSWORD`. Hibernate tiene `ddl-auto: update`; al iniciar la aplicación con la base configurada creará/actualizará las tablas y la restricción única sin borrar datos existentes. La conexión real con Supabase debe verificarse con credenciales de entorno válidas.

## Contratos HTTP

| Método | Ruta | Éxito |
| --- | --- | --- |
| GET | `/api/proyectos` | 200 |
| POST | `/api/proyectos` | 201 |
| GET | `/api/proyectos/{id}` | 200 |
| PUT | `/api/proyectos/{id}` | 200 |
| DELETE | `/api/proyectos/{id}` | 204 |
| GET | `/api/participantes` | 200 |
| GET | `/api/participantes?proyectoId={id}` | 200 |
| POST | `/api/participantes` | 201 |
| GET | `/api/participantes/{id}` | 200 |
| PUT | `/api/participantes/{id}` | 200 |
| DELETE | `/api/participantes/{id}` | 204 |

Las respuestas exitosas usan el sobre `{ "success": true, "data": ..., "message": ..., "timestamp": ... }`. Los errores usan `{ "error": "...", "mensaje": "...", "status": ..., "detalles": {...} }`. Los DELETE exitosos no devuelven cuerpo.

## Migración SQL opcional

El script `sql/20261030_proyectos_participantes.sql` es idempotente y permite aprovisionar/verificar las tablas sin borrar datos. No se ejecuta automáticamente. Con `ddl-auto: update` no es obligatorio, pero se recomienda revisarlo antes del despliegue.

## Cognito con múltiples App Clients

`AWS_COGNITO_CLIENT_ID` sigue siendo el App Client usado por el backend para login/registro. Para aceptar access tokens emitidos por otros App Clients del mismo User Pool (Android/React), configurar `AWS_COGNITO_ALLOWED_CLIENT_IDS` con los IDs separados por coma. El backend siempre agrega el valor de `AWS_COGNITO_CLIENT_ID` a la lista permitida. No se desactiva la validación de firma, issuer, `token_use=access` ni `client_id`.
