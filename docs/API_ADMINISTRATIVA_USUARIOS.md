# API administrativa de usuarios

Contrato de los endpoints administrativos de usuarios de SyncPass-Backend.

| Método | Ruta | Uso |
| --- | --- | --- |
| `GET` | `/api/usuarios` | Listado paginado |
| `GET` | `/api/usuarios/{id}` | Detalle por identificador exacto |
| `DELETE` | `/api/usuarios/{id}` | Eliminación coordinada Cognito + PostgreSQL |

- Todos los endpoints requieren un JWT válido de Cognito.
- Todos los endpoints requieren `ROLE_ADMINISTRADOR`.
- `{id}` es el `sub` de Cognito almacenado en `usuarios.id`. **No es el email.**
- La consulta lee únicamente PostgreSQL. No se llama a Cognito para listar ni
  para consultar un usuario.
- No se exponen contraseñas, tokens ni atributos de autenticación.

La operación de eliminación se detalla además en
[`ELIMINACION_ADMINISTRATIVA_USUARIOS.md`](./ELIMINACION_ADMINISTRATIVA_USUARIOS.md).

---

## 1. Autorización

```http
Authorization: Bearer <access_token>
```

| Situación | HTTP | Cuerpo |
| --- | --- | --- |
| Sin token o token inválido | `401` | Puede no incluir cuerpo JSON |
| Token válido sin `ROLE_ADMINISTRADOR` | `403` | Puede no incluir cuerpo JSON |
| Token con `ROLE_ADMINISTRADOR` | Continúa | — |

Los roles se obtienen del claim `cognito:groups`, normalizado a mayúsculas.
Se aceptan `ESTUDIANTE`, `PROFESOR`, `COORDINADOR` y `ADMINISTRADOR`; solo el
último puede usar estos endpoints.

---

## 2. `GET /api/usuarios`

Listado paginado de usuarios desde PostgreSQL.

### 2.1 Parámetros de consulta

| Parámetro | Tipo | Obligatorio | Valor por defecto | Límites |
| --- | --- | --- | --- | --- |
| `page` | entero | No | `0` | `>= 0` |
| `size` | entero | No | `20` | `1..100` |

- Orden estable: `nombre` ascendente y, como desempate, `id` ascendente.
- Un `page` fuera de rango devuelve una página vacía, no un error.
- `size` mayor a `100` se rechaza con `400`; no se recorta silenciosamente.
- Valores no numéricos se rechazan con `400`.

### 2.2 Ejemplo

```http
GET /api/usuarios?page=0&size=20
Authorization: Bearer eyJraWQiOi...
```

```json
{
  "success": true,
  "data": {
    "content": [
      {
        "id": "3f6c1a90-8d2b-4c47-9e11-6a0f7b2d1c01",
        "nombre": "Ana Pérez",
        "email": "ana.perez@duoc.cl",
        "roles": [
          "ESTUDIANTE",
          "PROFESOR"
        ]
      },
      {
        "id": "b71d4e22-0a55-4f0e-b3c8-2f9d1e6a7b42",
        "nombre": "Beto Silva",
        "email": "beto.silva@duoc.cl",
        "roles": [
          "ADMINISTRADOR"
        ]
      }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 2,
    "totalPages": 1,
    "first": true,
    "last": true
  },
  "message": "Usuarios obtenidos correctamente",
  "timestamp": "2026-11-05T08:10:00.000Z"
}
```

### 2.3 Página vacía

Si no hay usuarios, o si `page` supera el total de páginas:

```json
{
  "success": true,
  "data": {
    "content": [],
    "page": 5,
    "size": 20,
    "totalElements": 0,
    "totalPages": 0,
    "first": false,
    "last": true
  },
  "message": "Usuarios obtenidos correctamente",
  "timestamp": "2026-11-05T08:10:00.000Z"
}
```

### 2.4 Errores

| HTTP | `error` | Causa |
| --- | --- | --- |
| `400` | `VALIDATION_ERROR` | `page < 0`, `size < 1`, `size > 100`, o parámetro no numérico |
| `401` | — | Sin token o token inválido |
| `403` | — | Rol distinto de `ADMINISTRADOR` |

Ejemplo de `400`:

```json
{
  "error": "VALIDATION_ERROR",
  "mensaje": "El tamaño de página debe estar entre 1 y 100.",
  "status": 400,
  "detalles": {}
}
```

---

## 3. `GET /api/usuarios/{id}`

Detalle de un usuario por su `sub` exacto.

### 3.1 Reglas

- `{id}` es el `sub` de Cognito (`usuarios.id`), con longitud máxima 64.
- No se acepta email ni búsqueda parcial.
- `{id}` vacío o de más de 64 caracteres devuelve `400`.
- Si no existe, devuelve `404 USER_NOT_FOUND`.

### 3.2 Ejemplo

```http
GET /api/usuarios/3f6c1a90-8d2b-4c47-9e11-6a0f7b2d1c01
Authorization: Bearer eyJraWQiOi...
```

```json
{
  "success": true,
  "data": {
    "id": "3f6c1a90-8d2b-4c47-9e11-6a0f7b2d1c01",
    "nombre": "Ana Pérez",
    "email": "ana.perez@duoc.cl",
    "roles": [
      "ESTUDIANTE",
      "PROFESOR"
    ]
  },
  "message": "Usuario obtenido correctamente",
  "timestamp": "2026-11-05T08:10:00.000Z"
}
```

### 3.3 Errores

| HTTP | `error` | Causa |
| --- | --- | --- |
| `400` | `VALIDATION_ERROR` | `{id}` vacío o de más de 64 caracteres |
| `401` | — | Sin token o token inválido |
| `403` | — | Rol distinto de `ADMINISTRADOR` |
| `404` | `USER_NOT_FOUND` | No existe un usuario con ese `id` |

Ejemplo de `404`:

```json
{
  "error": "USER_NOT_FOUND",
  "mensaje": "El usuario indicado no existe o ya fue eliminado.",
  "status": 404,
  "detalles": {}
}
```

> Un email válido como `{id}` también devuelve `404`: el endpoint no busca por
> email.

---

## 4. `DELETE /api/usuarios/{id}`

Eliminación administrativa coordinada.

### 4.1 Ejemplo de solicitud

```http
DELETE /api/usuarios/3f6c1a90-8d2b-4c47-9e11-6a0f7b2d1c01
Authorization: Bearer eyJraWQiOi...
```

Sin cuerpo de solicitud.

### 4.2 Respuesta exitosa

```json
{
  "success": true,
  "data": {
    "usuarioId": "3f6c1a90-8d2b-4c47-9e11-6a0f7b2d1c01",
    "estado": "ELIMINADO",
    "cognitoEliminado": true,
    "postgresEliminado": true
  },
  "message": "Usuario eliminado correctamente",
  "timestamp": "2026-11-05T08:10:00.000Z"
}
```

Si Cognito ya no contenía la cuenta pero el registro local existía:

```json
{
  "success": true,
  "data": {
    "usuarioId": "3f6c1a90-8d2b-4c47-9e11-6a0f7b2d1c01",
    "estado": "RECONCILIADO",
    "cognitoEliminado": false,
    "postgresEliminado": true
  },
  "message": "Usuario eliminado de PostgreSQL; la cuenta de Cognito ya no existía",
  "timestamp": "2026-11-05T08:10:00.000Z"
}
```

### 4.3 Errores

| HTTP | `error` | Causa | ¿Se modificó algo? |
| --- | --- | --- | --- |
| `400` | `VALIDATION_ERROR` | `{id}` vacío o de más de 64 caracteres | No |
| `401` | — | Sin token o token inválido | No |
| `403` | — | Rol distinto de `ADMINISTRADOR` | No |
| `403` | `FORBIDDEN` | No se pudo identificar al administrador autenticado | No |
| `403` | `CANNOT_DELETE_SELF` | El administrador intenta eliminarse a sí mismo | No |
| `403` | `PROTECTED_ADMIN_ACCOUNT` | El objetivo tiene rol `ADMINISTRADOR` | No |
| `404` | `USER_NOT_FOUND` | No existe localmente o ya fue eliminado | No se llamó a Cognito |
| `409` | `USER_HAS_DEPENDENCIES` | Tiene cursos, matrículas, asistencias o participaciones | No se llamó a Cognito |
| `500` | `USER_DELETE_REQUIRES_RECONCILIATION` | Cognito se procesó, pero no se pudo verificar el borrado local | Cognito puede estar eliminado; reintentar es seguro |
| `503` | `COGNITO_DELETE_FORBIDDEN` | IAM rechazó `AdminDeleteUser` | No se tocó PostgreSQL |
| `503` | `COGNITO_DELETE_THROTTLED` | Cognito limitó las solicitudes | No se tocó PostgreSQL |
| `503` | `COGNITO_DELETE_FAILED` | Error temporal, de red o de comunicación con Cognito | No se tocó PostgreSQL |

Ejemplo de conflicto:

```json
{
  "error": "USER_HAS_DEPENDENCIES",
  "mensaje": "El usuario tiene dependencias académicas o históricas que deben conservarse (cursos como docente: 2, matrículas: 3). No se realizó ningún cambio.",
  "status": 409,
  "detalles": {}
}
```

### 4.4 Identificador enviado a Cognito

`Usuario.id` es el `sub` de Cognito y se envía como `Username` en
`AdminDeleteUser`. La [documentación oficial de AWS](https://docs.aws.amazon.com/cognito-user-identity-pools/latest/APIReference/API_AdminDeleteUser.html)
indica que, si `username` no es un atributo alias del user pool, el valor debe
ser el `sub` del usuario local. Se usa el `sub` y no el email para evitar
ambigüedades y cambios de correo.

---

## 5. Contrato para React

### 5.1 Tipos

```ts
export type Rol = "ESTUDIANTE" | "PROFESOR" | "COORDINADOR" | "ADMINISTRADOR";

export interface UsuarioAdmin {
  id: string;        // sub de Cognito
  nombre: string;
  email: string;
  roles: Rol[];
}

export interface PaginaUsuarios {
  content: UsuarioAdmin[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  first: boolean;
  last: boolean;
}

export interface EliminacionUsuario {
  usuarioId: string;
  estado: "ELIMINADO" | "RECONCILIADO";
  cognitoEliminado: boolean;
  postgresEliminado: boolean;
}

export interface ApiResponse<T> {
  success: boolean;
  data: T;
  message?: string;
  timestamp: string;
}

export interface ErrorApi {
  error: string;
  mensaje: string;
  status: number;
  detalles?: Record<string, string>;
}
```

### 5.2 Funciones

```ts
export async function listarUsuarios(
  page = 0,
  size = 20,
  signal?: AbortSignal
): Promise<ApiResponse<PaginaUsuarios>> {
  const params = new URLSearchParams({
    page: String(page),
    size: String(size),
  });

  const response = await apiFetch(`/api/usuarios?${params}`, { signal });

  if (!response.ok) {
    throw await respuestaError(response);
  }

  return response.json();
}

export async function obtenerUsuario(
  id: string
): Promise<ApiResponse<UsuarioAdmin>> {
  const response = await apiFetch(
    `/api/usuarios/${encodeURIComponent(id)}`
  );

  if (!response.ok) {
    throw await respuestaError(response);
  }

  return response.json();
}

export async function eliminarUsuario(
  id: string
): Promise<ApiResponse<EliminacionUsuario>> {
  const response = await apiFetch(
    `/api/usuarios/${encodeURIComponent(id)}`,
    { method: "DELETE" }
  );

  if (!response.ok) {
    throw await respuestaError(response);
  }

  return response.json();
}

async function respuestaError(response: Response): Promise<ErrorApi> {
  const body = await response.json().catch(() => null);

  return {
    error: body?.error ?? "HTTP_ERROR",
    mensaje: body?.mensaje ?? `Error HTTP ${response.status}`,
    status: response.status,
  };
}
```

`apiFetch` debe ser el wrapper existente que agrega
`Authorization: Bearer <access_token>` y la URL base.

### 5.3 Uso en la lista administrativa

```tsx
const [pagina, setPagina] = useState(0);
const [tamano, setTamano] = useState(20);
const [datos, setDatos] = useState<PaginaUsuarios | null>(null);
const [cargando, setCargando] = useState(false);
const [error, setError] = useState<string | null>(null);

useEffect(() => {
  const controller = new AbortController();

  setCargando(true);
  setError(null);

  listarUsuarios(pagina, tamano, controller.signal)
    .then((respuesta) => setDatos(respuesta.data))
    .catch((e) => {
      if (e?.name !== "AbortError") {
        setError(e.mensaje ?? "No fue posible cargar los usuarios.");
      }
    })
    .finally(() => setCargando(false));

  return () => controller.abort();
}, [pagina, tamano]);
```

Reglas de interfaz:

- La lista debe usar `usuario.id` como `key` y como identificador de acciones.
- Al eliminar con `200`, refrescar la página actual; si la página queda vacía y
  `page > 0`, retroceder una página.
- Ante `404` al eliminar, refrescar la lista: el usuario ya no existe.
- Ante `409`, `500` o `503`, no quitar la fila y mostrar el mensaje del backend.
- No usar actualización optimista: el estado puede ser incierto tras un `500`.
- Deshabilitar el botón de eliminar mientras la solicitud está en curso.
- En `401`, redirigir al login. En `403`, mostrar que no tiene permisos.

### 5.4 Paginación

- `page` empieza en `0`.
- Enviar siempre `page` y `size` explícitos para evitar depender del valor por
  defecto.
- `size` máximo: `100`. Para tablas grandes, usar `20` o `50`.
- El backend ya aplica un orden estable; no reordenar en cliente salvo
  preferencia visual.

### 5.5 Resumen de acciones tras `DELETE`

| Resultado | Acción en la UI |
| --- | --- |
| `200 ELIMINADO` | Refrescar lista; informar éxito |
| `200 RECONCILIADO` | Refrescar lista; informar reconciliación |
| `404 USER_NOT_FOUND` | Refrescar lista; tratar como ya eliminado |
| `409 USER_HAS_DEPENDENCIES` | Mantener fila; mostrar dependencias |
| `500 USER_DELETE_REQUIRES_RECONCILIATION` | Mantener fila; ofrecer reintento |
| `503 COGNITO_*` | Mantener fila; informar que no hubo cambios |

---

## 6. Notas de implementación

- **Sin N+1:** la lista consulta primero las columnas `id`, `nombre` y `email`,
  y luego carga todos los roles de la página en una sola consulta por lote.
- **Sin cambios de esquema:** se usan las tablas `usuarios` y `usuario_roles`
  existentes. No hay migraciones nuevas.
- **Sin Cognito en lecturas:** `GET` no usa `AdminGetUser`, `ListUsers` ni
  ninguna otra operación de Cognito.
- **Roles:** se devuelven tal como están almacenados en PostgreSQL, ordenados
  alfabéticamente para que la respuesta sea determinista.
- **Registro administrativo intacto:** no se modificó `AuthService.registrar`,
  `AdminConfirmSignUp`, `AdminAddUserToGroup` ni el flujo de creación.
- **Eliminación intacta:** el flujo de `DELETE` conserva sus reglas de
  autoprotección, dependencias, reconciliación y reintento.
