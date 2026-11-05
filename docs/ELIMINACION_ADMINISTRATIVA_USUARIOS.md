# Eliminación administrativa de usuarios (Cognito + PostgreSQL)

Documento de referencia del endpoint `DELETE /api/usuarios/{id}`.

> Para el contrato conjunto de listado, detalle y eliminación, incluido el uso
> desde React, ver [`API_ADMINISTRATIVA_USUARIOS.md`](./API_ADMINISTRATIVA_USUARIOS.md).

Describe el contrato, las reglas de autorización, la política de dependencias,
el flujo de eliminación entre Amazon Cognito y PostgreSQL, el tratamiento de
fallos parciales, los permisos IAM necesarios, la integración desde React y el
procedimiento de despliegue y rollback.

> Este documento **no ejecuta** ninguna operación sobre Cognito, PostgreSQL ni
> infraestructura. No se eliminaron usuarios reales durante el desarrollo ni se
> aplicaron migraciones.

---

## 1. Problema que resuelve

Antes de este cambio, una cuenta eliminada manualmente desde la consola de
Cognito dejaba filas huérfanas en `usuarios` y `usuario_roles`. Como
`AuthService.registrar` valida `usuarioRepository.existsByEmail`, ese residuo
impedía volver a registrar el mismo email.

El endpoint administrativo realiza la baja coordinada:

1. Verifica que quien invoca sea administrador.
2. Verifica que el objetivo exista localmente.
3. Bloquea cuentas protegidas y usuarios con historial.
4. Elimina la identidad en Cognito.
5. Elimina el registro local y sus roles.
6. Verifica el estado final y reporta el resultado real.

La operación es **idempotente en efecto**, aunque no devuelve siempre `200`:
repetirla después de un éxito devuelve `404 USER_NOT_FOUND` y no vuelve a llamar
a Cognito.

---

## 2. Contrato del endpoint

### 2.1 Ruta y método

```http
DELETE /api/usuarios/{id}
Authorization: Bearer <access_token de Cognito>
```

| Elemento | Valor |
| --- | --- |
| `{id}` | `sub` de Cognito, almacenado en `usuarios.id` (`VARCHAR(64)`) |
| Identificación | Por `sub`, **nunca** por email |
| Autenticación | JWT de Cognito (Spring OAuth2 Resource Server) |
| Autorización | `ROLE_ADMINISTRADOR` |
| Cuerpo de solicitud | No aplica (sin body) |
| CORS | `DELETE` ya está permitido por defecto en `CORS_ALLOWED_METHODS` |

Ejemplo:

```http
DELETE /api/usuarios/1f2a3b4c-5d6e-7f80-9a1b-2c3d4e5f6a7b
Authorization: Bearer eyJraWQiOi...
```

### 2.2 Respuesta exitosa (`200 OK`)

```json
{
  "success": true,
  "data": {
    "usuarioId": "1f2a3b4c-5d6e-7f80-9a1b-2c3d4e5f6a7b",
    "estado": "ELIMINADO",
    "cognitoEliminado": true,
    "postgresEliminado": true
  },
  "message": "Usuario eliminado correctamente",
  "timestamp": "2026-11-05T07:25:00.000Z"
}
```

Estados posibles de `data.estado`:

| `estado` | `cognitoEliminado` | Significado |
| --- | --- | --- |
| `ELIMINADO` | `true` | La cuenta existía en Cognito y fue eliminada. El registro local también fue eliminado y verificado. |
| `RECONCILIADO` | `false` | Cognito ya no contenía la cuenta (`UserNotFoundException`, por ejemplo tras un borrado manual). El registro local existía, fue eliminado y el estado final quedó consistente. |

En ambos casos `postgresEliminado` es `true`: solo se responde `200` cuando la
ausencia del registro local fue verificada.

> Se eligió `200` con cuerpo (y no `204`) porque la operación reporta la
> reconciliación entre dos sistemas. Un `204` ocultaría si Cognito se eliminó o
> si solo se reconcilió un registro huérfano.

### 2.3 Respuestas de error

El formato de error proviene de `GlobalExceptionHandler`:

```json
{
  "error": "USER_HAS_DEPENDENCIES",
  "mensaje": "El usuario tiene dependencias académicas o históricas que deben conservarse (...). No se realizó ningún cambio.",
  "status": 409,
  "detalles": {}
}
```

| HTTP | Código (`error`) | Cuándo ocurre | ¿Se modificó algo? |
| --- | --- | --- | --- |
| `400` | `VALIDATION_ERROR` | `{id}` vacío o de más de 64 caracteres | No |
| `401` | — | Sin token o token inválido (respuesta de Spring Security) | No |
| `403` | — | Token válido sin `ROLE_ADMINISTRADOR` | No |
| `403` | `FORBIDDEN` | No se pudo obtener el `sub` del administrador autenticado | No |
| `403` | `CANNOT_DELETE_SELF` | El `sub` del token coincide con `{id}` | No |
| `403` | `PROTECTED_ADMIN_ACCOUNT` | El objetivo tiene rol `ADMINISTRADOR` | No |
| `404` | `USER_NOT_FOUND` | El usuario no existe localmente o ya fue eliminado | No se llamó a Cognito |
| `409` | `USER_HAS_DEPENDENCIES` | Existen cursos, matrículas, asistencias o participaciones asociadas | No se llamó a Cognito |
| `409` | `DATA_CONFLICT` | Violación de integridad al borrar (condición de carrera) | Puede requerir reintento |
| `500` | `USER_DELETE_REQUIRES_RECONCILIATION` | Cognito se procesó, pero no se pudo verificar el borrado local | Cognito puede estar eliminado; reintentar es seguro |
| `503` | `COGNITO_DELETE_FORBIDDEN` | IAM rechazó `AdminDeleteUser` | No se tocó PostgreSQL |
| `503` | `COGNITO_DELETE_THROTTLED` | Cognito limitó las solicitudes | No se tocó PostgreSQL |
| `503` | `COGNITO_DELETE_FAILED` | Error temporal, de red o de comunicación con Cognito | No se tocó PostgreSQL |

Los errores `401`/`403` generados por el filtro de seguridad pueden no incluir
el cuerpo JSON anterior; el frontend debe basarse en el código HTTP.

---

## 3. Autorización y protecciones

### 3.1 Doble control de acceso

- `SecurityConfig` define, antes de `anyRequest().denyAll()`:

  ```java
  .requestMatchers(HttpMethod.DELETE, "/api/usuarios", "/api/usuarios/**")
  .hasRole("ADMINISTRADOR")
  ```

- `UsuarioController` refuerza con `@PreAuthorize("hasRole('ADMINISTRADOR')")`.

Los roles se derivan del claim `cognito:groups`, normalizado a mayúsculas y
prefijado con `ROLE_`. Solo se aceptan `ESTUDIANTE`, `PROFESOR`, `COORDINADOR`
y `ADMINISTRADOR`.

### 3.2 Reglas de negocio

| Regla | Implementación | Resultado |
| --- | --- | --- |
| Un administrador no se elimina a sí mismo | Compara `jwt.getSubject()` con `{id}` | `403 CANNOT_DELETE_SELF` |
| Las cuentas administrativas están protegidas | Revisa el rol `ADMINISTRADOR` del registro local | `403 PROTECTED_ADMIN_ACCOUNT` |
| No hay escalamiento ni borrado por email | El `sub` se usa como `username` exacto en Cognito | No aplica |
| No se borran identidades no verificadas | Si falta el registro local, no se llama a Cognito | `404 USER_NOT_FOUND` |
| No se borran datos académicos | Solo se eliminan `usuarios` y `usuario_roles` | — |

Proteger **todas** las cuentas `ADMINISTRADOR` (no solo la propia) evita que un
administrador elimine a otro y deje la instancia sin acceso administrativo.

### 3.3 Efecto sobre sesiones activas

`AdminDeleteUser` impide nuevos inicios de sesión, pero un access token ya
emitido puede seguir siendo aceptado hasta su expiración. Este cambio **no**
invoca `AdminUserGlobalSignOut`. Si se requiere revocación inmediata, debe
evaluarse esa operación y su permiso IAM en un cambio separado.

---

## 4. Política de dependencias

El modelo no declara claves foráneas JPA desde las entidades académicas hacia
`usuarios`; la relación se guarda por el `sub` o por el email. Por eso las
dependencias se comprueban explícitamente con `UsuarioDependencyChecker`.

### 4.1 Referencias que bloquean la eliminación

| Tabla / entidad | Columna | Repositorio | Método |
| --- | --- | --- | --- |
| `cursos` | `docente_id` | `CursoRepository` | `countByDocenteId` |
| `matriculas` | `estudiante_id` | `MatriculaRepository` | `countByEstudianteId` |
| `asistencias` | `estudiante_id` | `AsistenciaRepository` | `countByEstudianteId` |
| `participantes` | `email` | `ParticipanteRepository` | `countByEmailIgnoreCase` |

La comparación de participantes es por email sin distinguir mayúsculas, porque
esa tabla no almacena el `sub`.

### 4.2 Comportamiento ante dependencias

- Se devuelve `409 USER_HAS_DEPENDENCIES` con el detalle de los conteos.
- **No se elimina nada**: ni Cognito ni PostgreSQL.
- No se usan cascadas ni borrados en bloque.
- Los proyectos, cursos, matrículas, asistencias y participaciones se conservan
  como información académica e histórica.

### 4.3 Doble verificación y condición de carrera

La comprobación se ejecuta:

1. Antes de llamar a Cognito, para evitar una eliminación irreversible cuando ya
   se sabe que hay dependencias.
2. Dentro de la transacción local (`UsuarioLocalTransactionService`), justo
   antes de borrar, para reducir la ventana entre validación y borrado.

Si aparecen dependencias nuevas entre ambas comprobaciones, la transacción
local se revierte. En ese caso Cognito ya podría estar eliminado; el reintento
seguirá devolviendo `409` y requerirá revisión administrativa. Es un escenario
de carrera poco frecuente, pero debe conocerse.

---

## 5. Flujo de eliminación

```
Cliente (React)
   │  DELETE /api/usuarios/{sub}
   ▼
SecurityConfig + @PreAuthorize  ──► 401/403 si no es ADMINISTRADOR
   ▼
UsuarioAdminService.eliminarUsuario
   │
   ├─ 1. Auto-eliminación           ──► 403 CANNOT_DELETE_SELF
   ├─ 2. Buscar usuario local       ──► 404 USER_NOT_FOUND (no toca Cognito)
   ├─ 3. ¿Rol ADMINISTRADOR?        ──► 403 PROTECTED_ADMIN_ACCOUNT
   ├─ 4. Comprobar dependencias     ──► 409 USER_HAS_DEPENDENCIES (no toca nada)
   │
   ├─ 5. Cognito AdminDeleteUser(username = sub)
   │        ├─ UserNotFoundException ──► cognitoEliminado = false (reconciliar)
   │        ├─ IAM/limitación/error   ──► 503 (no toca PostgreSQL)
   │        └─ OK                     ──► cognitoEliminado = true
   │
   ├─ 6. Transacción local separada
   │        ├─ Revalidar dependencias
   │        ├─ DELETE usuarios + usuario_roles
   │        └─ flush
   │
   ├─ 7. Verificar existsById(id) == false
   │        ├─ Sí ──► 200 ELIMINADO / RECONCILIADO
   │        └─ No ──► 500 USER_DELETE_REQUIRES_RECONCILIATION
   ▼
Respuesta
```

### 5.1 Por qué Cognito primero

Cognito no participa en la transacción de PostgreSQL. El orden elegido busca que
un fallo deje un estado recuperable:

- Si falla Cognito, no se tocó PostgreSQL: el usuario sigue operativo y se puede
  reintentar.
- Si Cognito se elimina y PostgreSQL falla, el reintento encuentra
  `UserNotFoundException`, lo trata como ausencia controlada y completa el
  borrado local. Nunca se informa éxito sin verificar PostgreSQL.

El orden inverso (borrar PostgreSQL primero) dejaría una cuenta de Cognito activa
sin registro local, lo que permitiría iniciar sesión sin perfil asociado.

### 5.2 Por qué la transacción local es un bean separado

`UsuarioAdminService` no es `@Transactional`. La llamada HTTP a Cognito ocurre
fuera de cualquier transacción de base de datos. Solo
`UsuarioLocalTransactionService.eliminarUsuarioLocal` abre una transacción que
cubre la revalidación, el borrado del usuario y el borrado de `usuario_roles`
(`@ElementCollection`, eliminada junto con la entidad).

No hay migraciones ni cambios de esquema: se usan las tablas existentes.

---

## 6. Fallos parciales y reconciliación

| Escenario | Estado final | Respuesta | Acción recomendada |
| --- | --- | --- | --- |
| Cognito eliminado + PostgreSQL eliminado | Consistente | `200 ELIMINADO` | Ninguna |
| Cognito ya no existía + PostgreSQL eliminado | Consistente | `200 RECONCILIADO` | Ninguna |
| Falla Cognito antes de tocar PostgreSQL | Sin cambios | `503` | Reintentar más tarde |
| Cognito eliminado + falla PostgreSQL | Parcial: identidad ausente, registro local presente | `500 USER_DELETE_REQUIRES_RECONCILIATION` | Reintentar la misma operación; es segura |
| Reintento tras éxito | Consistente | `404 USER_NOT_FOUND` | Tratar como ya eliminado en la UI |
| Registro local ausente pero cuenta Cognito existe | Sin cambios | `404 USER_NOT_FOUND` | Revisión manual; el endpoint no borra identidades no verificadas |

### 6.1 Reintento

- Si quedó `USER_DELETE_REQUIRES_RECONCILIATION`, repetir el mismo `DELETE`.
- En el reintento, `AdminDeleteUser` devuelve `UserNotFoundException`, se marca
  `cognitoEliminado = false` y se elimina el registro local.
- Si el fallo local persiste, se vuelve a responder `500`; no se informa un
  éxito falso.

### 6.2 Cuentas huérfanas en Cognito

Este endpoint **no** elimina una cuenta de Cognito cuando no existe el registro
local. Hacerlo permitiría borrar una identidad por un `sub` no verificado en la
base. La conciliación de esos casos requiere un procedimiento administrativo
aparte, con `AdminGetUser` / `ListUsers` y autorización explícita.

---

## 7. Permisos IAM

La identidad usada por el backend debe poder ejecutar:

```text
cognito-idp:AdminDeleteUser
```

El proyecto ya requiere `cognito-idp:AdminConfirmSignUp` y
`cognito-idp:AdminAddUserToGroup` para el registro administrativo. Ver
[`IAM_COGNITO_REGISTRO.md`](./IAM_COGNITO_REGISTRO.md).

Política mínima de referencia (reemplazar `REGION`, `ACCOUNT_ID` y
`USER_POOL_ID`; no aplicar sin revisión):

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "SyncPassEliminacionAdministrativaCognito",
      "Effect": "Allow",
      "Action": [
        "cognito-idp:AdminDeleteUser"
      ],
      "Resource": "arn:aws:cognito-idp:REGION:ACCOUNT_ID:userpool/USER_POOL_ID"
    }
  ]
}
```

Verificación sin modificar IAM:

```bash
aws iam simulate-principal-policy \
  --policy-source-arn arn:aws:iam::ACCOUNT_ID:role/NOMBRE_DEL_ROL \
  --action-names cognito-idp:AdminDeleteUser \
  --resource-arns arn:aws:cognito-idp:REGION:ACCOUNT_ID:userpool/USER_POOL_ID
```

El resultado esperado es `allowed`. Si no lo es, el endpoint responderá
`503 COGNITO_DELETE_FORBIDDEN` y no tocará PostgreSQL.

> `AdminDeleteUser` se usa con el `sub` exacto de `usuarios.id`. No se usa email
> como `username` y no se requiere `AdminGetUser` para esta operación.

---

## 8. Integración desde React

### 8.1 Cliente HTTP

```js
// src/api/usuarios.js
import { apiFetch } from "./client"; // wrapper existente que agrega el Bearer token

export async function eliminarUsuario(usuarioId) {
  const response = await apiFetch(`/api/usuarios/${encodeURIComponent(usuarioId)}`, {
    method: "DELETE",
  });

  const body = await response.json().catch(() => null);
  return { response, body };
}
```

El identificador debe ser el `sub` que la lista ya usa como `id`; no enviar el
email.

### 8.2 Manejo de respuestas

```jsx
async function onEliminar(usuario) {
  const confirmado = window.confirm(
    `¿Eliminar definitivamente a ${usuario.nombre}? Esta acción no se puede deshacer.`
  );
  if (!confirmado) return;

  setEliminandoId(usuario.id);

  try {
    const { response, body } = await eliminarUsuario(usuario.id);

    if (response.ok) {
      // 200: ELIMINADO o RECONCILIADO. Ambos dejan el estado consistente.
      setUsuarios((prev) => prev.filter((u) => u.id !== usuario.id));
      const mensaje =
        body?.data?.estado === "RECONCILIADO"
          ? "Usuario eliminado de la base local; su cuenta de identidad ya no existía."
          : "Usuario eliminado correctamente.";
      mostrarExito(mensaje);
      return;
    }

    switch (response.status) {
      case 401:
        redirigirALogin();
        break;
      case 403:
        mostrarError("No tienes permisos para eliminar este usuario.");
        break;
      case 404:
        // Ya no existe: quitar de la lista para reflejar el estado real.
        setUsuarios((prev) => prev.filter((u) => u.id !== usuario.id));
        mostrarInfo("El usuario ya no existe o fue eliminado previamente.");
        break;
      case 409:
        mostrarError(
          body?.mensaje ??
            "El usuario tiene historial académico asociado y no puede eliminarse."
        );
        break;
      case 500:
        mostrarError(
          "La cuenta de identidad puede haber sido eliminada, pero la base local no se pudo actualizar. " +
            "Vuelve a intentar la operación; es segura. Si persiste, contacta a soporte."
        );
        // No quitar de la lista todavía: el estado local es incierto.
        break;
      case 503:
        mostrarError(
          "El proveedor de identidad no está disponible en este momento. " +
            "No se modificó ningún dato. Intenta nuevamente más tarde."
        );
        break;
      default:
        mostrarError(body?.mensaje ?? "No fue posible eliminar el usuario.");
    }
  } finally {
    setEliminandoId(null);
  }
}
```

### 8.3 Actualización de la lista

| Resultado | Acción en la UI |
| --- | --- |
| `200 ELIMINADO` | Quitar la fila de la lista |
| `200 RECONCILIADO` | Quitar la fila; informar que solo se reconcilió el registro local |
| `404 USER_NOT_FOUND` | Quitar la fila; el usuario ya no existe |
| `409 USER_HAS_DEPENDENCIES` | Mantener la fila; mostrar el detalle del conflicto |
| `500 USER_DELETE_REQUIRES_RECONCILIATION` | Mantener la fila; ofrecer reintento explícito |
| `503 COGNITO_*` | Mantener la fila; informar que no hubo cambios |

Recomendaciones:

- Deshabilitar el botón mientras la solicitud está en curso para evitar dobles
  clics.
- No usar actualización optimista: el estado local puede ser incierto tras un
  `500`.
- Tras un `200` o un `404`, refrescar la lista desde el backend si la pantalla
  usa paginación o filtros.
- No reintentar automáticamente los `500`/`503` sin intervención del usuario.

---

## 9. Despliegue y rollback

El procedimiento detallado de transferencia, respaldo y rollback del JAR se
encuentra en
[`DESPLIEGUE_CONFIRMACION_COGNITO.md`](./DESPLIEGUE_CONFIRMACION_COGNITO.md).
Aplica el mismo mecanismo: respaldar el JAR en uso, subir el nuevo como
`.nuevo`, validar SHA256, reemplazar únicamente el JAR y reiniciar el servicio.

### 9.1 Antes de desplegar

```powershell
.\gradlew.bat cleanTest test --console=plain
.\gradlew.bat bootJar --console=plain
Get-FileHash build\libs\cursos-0.0.1-SNAPSHOT.jar -Algorithm SHA256
```

Comprobar en AWS, sin modificar nada:

```bash
aws sts get-caller-identity
aws iam simulate-principal-policy \
  --policy-source-arn arn:aws:iam::ACCOUNT_ID:role/NOMBRE_DEL_ROL \
  --action-names cognito-idp:AdminDeleteUser \
  --resource-arns arn:aws:cognito-idp:REGION:ACCOUNT_ID:userpool/USER_POOL_ID
```

### 9.2 Consideraciones de datos

- **No se requiere migración**: no hay tablas, columnas ni índices nuevos.
- Antes de la primera baja real, validar el usuario objetivo en un entorno de
  pruebas. Nunca ejecutar el endpoint contra producción sin autorización.
- No se debe eliminar un usuario real para "probar" el endpoint en producción.
- Si se necesita conservar evidencia, exportar previamente el registro de
  `usuarios` y `usuario_roles` del `sub` objetivo (solo lectura).
- El rollback del JAR no restaura usuarios ya eliminados. Cognito y PostgreSQL
  no ofrecen una transacción conjunta; una baja confirmada es irreversible
  salvo restauración desde respaldos administrados.

### 9.3 Rollback

1. Detener el servicio.
2. Restaurar el JAR respaldado (mismo procedimiento del documento de
   despliegue).
3. Iniciar el servicio y validar `/api/health`.
4. Revisar `journalctl` por errores.
5. No modificar `.env`, Caddy ni la unidad systemd: no forman parte de este
   cambio.
6. Si el rollback no recupera el servicio, detenerse y recolectar logs; no
   reiniciar repetidamente ni cambiar configuración sin autorización.

---

## 10. Pruebas

Comando ejecutado:

```powershell
.\gradlew.bat cleanTest test --console=plain
```

Resultado: **BUILD SUCCESSFUL**, **81 pruebas, 0 fallos, 0 errores, 0 omitidas**.

| Clase | Pruebas |
| --- | --- |
| `CursosApplicationTests` | 1 |
| `AsistenciaSecurityTests` | 9 |
| `AuthServiceRoleValidationTests` | 15 |
| `SecurityAuthorizationTests` | 21 |
| `UsuarioAdminServiceTests` | 16 |
| `UsuarioEliminacionIntegrationTests` | 10 |
| `UsuarioSecurityTests` | 9 |
| **Total** | **81** |

Cobertura relevante:

- Eliminación correcta de usuario y de todos sus roles.
- Autobloqueo y protección de cuentas administrativas.
- Ausencia local sin tocar Cognito.
- Reconciliación cuando Cognito ya no contiene la cuenta.
- Bloqueo por curso, matrícula, asistencia y participación en proyecto.
- Denegación IAM, error genérico 403, limitación de solicitudes y fallo
  temporal de Cognito.
- Fallo local posterior a Cognito sin informar éxito falso.
- Reintento seguro tras fallo parcial.
- Autorización: anónimo, estudiante, profesor, coordinador y administrador.
- Mapeo HTTP de `403`, `404`, `409`, `500` y `503` sin filtrar detalles internos.

Las pruebas de integración usan H2 y un `CognitoIdentityProviderClient`
mockeado. **No** se invocó Cognito real ni se modificaron datos de Supabase.

---

## 11. Limitaciones y decisiones pendientes

1. **Usuarios con historial no se eliminan.** Si el negocio necesita dar de baja
   cuentas con cursos, matrículas, asistencias o participaciones, se requiere
   una estrategia futura de desactivación o anonimización compatible con el
   esquema actual. No se implementó para no destruir información académica.
2. **Cuentas huérfanas en Cognito.** Un `sub` sin registro local no se elimina
   por este endpoint. Requiere conciliación administrativa con permisos
   adicionales (`AdminGetUser` / `ListUsers`).
3. **Tokens ya emitidos.** La eliminación impide nuevos inicios de sesión, pero
   no revoca access tokens vigentes. Evaluar `AdminUserGlobalSignOut` si se
   necesita corte inmediato.
4. **Ventana de carrera.** Entre la comprobación previa y el borrado local puede
   aparecer una dependencia nueva. La revalidación transaccional la detecta,
   pero si Cognito ya fue eliminado el estado queda parcial y requiere revisión.
   Es un caso excepcional, no un flujo normal.
5. **Sin auditoría dedicada.** No se agregó una tabla de auditoría para no
   modificar el esquema. Los eventos quedan en logs de aplicación
   (`sub`, resultado y errores), sin datos personales adicionales.
6. **Respuesta elegida.** Se mantiene `200` con `ApiResponse` en lugar de `204`
   para informar la reconciliación. Si se prefiere homogeneizar con otros
   `DELETE` del proyecto, requiere un cambio de contrato en backend y frontend.

---

## Archivos involucrados

| Archivo | Rol |
| --- | --- |
| `src/main/java/com/duoc/cursos/auth/controller/UsuarioController.java` | Endpoint `DELETE /api/usuarios/{id}` |
| `src/main/java/com/duoc/cursos/auth/service/UsuarioAdminService.java` | Orquestación Cognito + PostgreSQL y manejo de fallos |
| `src/main/java/com/duoc/cursos/auth/service/UsuarioLocalTransactionService.java` | Borrado local transaccional y revalidación |
| `src/main/java/com/duoc/cursos/auth/service/UsuarioDependencyChecker.java` | Conteo de dependencias académicas e históricas |
| `src/main/java/com/duoc/cursos/auth/dto/EliminacionUsuarioResponse.java` | Contrato de respuesta |
| `src/main/java/com/duoc/cursos/config/SecurityConfig.java` | Regla de autorización `DELETE` |
| `src/main/java/com/duoc/cursos/config/GlobalExceptionHandler.java` | Mapeo de códigos a HTTP |
| `src/main/java/com/duoc/cursos/academico/repository/*.java` | Conteos de cursos, matrículas y asistencias |
| `src/main/java/com/duoc/cursos/participantes/repository/ParticipanteRepository.java` | Conteo de participaciones por email |
| `src/test/java/com/duoc/cursos/security/Usuario*Tests.java` | Pruebas unitarias, de integración y de seguridad |
