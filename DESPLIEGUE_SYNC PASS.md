# Despliegue SyncPass API en AWS EC2

Documento operativo para reemplazar el backend antiguo de EC2 por esta version, sin modificar la infraestructura existente.

## 1. Alcance de esta version

Esta version incorpora y expone los CRUD persistidos de Proyectos y Participantes sobre PostgreSQL/Supabase, manteniendo autenticacion JWT con AWS Cognito y compatibilidad con los clientes Android y React.

Incluye:

- CRUD completo de Proyectos.
- CRUD completo de Participantes.
- Filtro de participantes por proyecto.
- Persistencia JPA/PostgreSQL.
- Validacion JWT con access tokens de Cognito.
- Validacion de multiples App Clients del mismo User Pool mediante lista de IDs permitidos.
- Manejo de errores HTTP para JSON invalido y parametros con formato incorrecto.

No incluye cambios de infraestructura, cambios en Supabase, cambios en Cognito ni despliegue automatico.

## 2. Contratos HTTP confirmados

### Proyectos

| Metodo | Ruta | Codigo exitoso | Cuerpo de respuesta |
| --- | --- | --- | --- |
| GET | `/api/proyectos` | 200 | `success/data` con lista |
| POST | `/api/proyectos` | 201 | `success/data` con proyecto creado |
| GET | `/api/proyectos/{id}` | 200 | `success/data` con proyecto |
| PUT | `/api/proyectos/{id}` | 200 | `success/data` con proyecto actualizado |
| DELETE | `/api/proyectos/{id}` | 204 | Sin cuerpo |

Campos de Proyecto:

```json
{
  "id": 1,
  "nombre": "Proyecto Demo",
  "descripcion": "Descripcion del proyecto",
  "estado": "ACTIVO"
}
```

`estado` solo acepta `ACTIVO`, `INACTIVO` o `FINALIZADO`.

Request de creacion/actualizacion:

```json
{
  "nombre": "Proyecto Demo",
  "descripcion": "Descripcion del proyecto",
  "estado": "ACTIVO"
}
```

### Participantes

| Metodo | Ruta | Codigo exitoso | Cuerpo de respuesta |
| --- | --- | --- | --- |
| GET | `/api/participantes` | 200 | `success/data` con lista |
| GET | `/api/participantes?proyectoId={id}` | 200 | `success/data` con lista filtrada |
| POST | `/api/participantes` | 201 | `success/data` con participante creado |
| GET | `/api/participantes/{id}` | 200 | `success/data` con participante |
| PUT | `/api/participantes/{id}` | 200 | `success/data` con participante actualizado |
| DELETE | `/api/participantes/{id}` | 204 | Sin cuerpo |

Campos de Participante:

```json
{
  "id": 10,
  "nombre": "Ana Perez",
  "email": "ana.perez@example.com",
  "proyectoId": 1
}
```

Request de creacion/actualizacion:

```json
{
  "nombre": "Ana Perez",
  "email": "ana.perez@example.com",
  "proyectoId": 1
}
```

Reglas de persistencia:

- Un participante pertenece a un proyecto mediante `proyectoId`.
- El email se normaliza a minusculas y se guarda sin espacios.
- El email es unico dentro del mismo proyecto.
- No se permite eliminar un proyecto que tenga participantes asociados: responde 409.
- No hay borrado en cascada.

### Formato de respuestas

Exito:

```json
{
  "success": true,
  "data": {},
  "message": "opcional",
  "timestamp": "2026-10-30T00:00:00Z"
}
```

Error:

```json
{
  "error": "VALIDATION_ERROR",
  "mensaje": "Datos de entrada invalidos",
  "status": 400,
  "detalles": {
    "campo": "mensaje"
  }
}
```

Codigos esperados:

- 200: consulta o actualizacion exitosa.
- 201: creacion exitosa.
- 204: eliminacion exitosa, sin cuerpo.
- 400: datos invalidos, JSON invalido, parametro con formato incorrecto o estado no permitido.
- 401: token ausente, invalido o de un cliente no autorizado.
- 404: proyecto o participante no encontrado.
- 409: proyecto con participantes o email duplicado en el proyecto.
- 500: error interno no controlado.

## 3. Cambios realizados en el codigo

- `src/main/java/com/duoc/cursos/config/JwtConfig.java`: permite validar access tokens emitidos por varios App Clients del mismo User Pool mediante `AWS_COGNITO_ALLOWED_CLIENT_IDS`, manteniendo validacion de firma, issuer, `token_use=access` y `client_id`.
- `src/main/resources/application.yml`: agrega `aws.cognito.allowed-client-ids`.
- `src/main/java/com/duoc/cursos/config/GlobalExceptionHandler.java`: agrega respuestas 400 para JSON invalido y parametros con tipo incorrecto.
- `.env.example`: documenta las nuevas variables de Cognito.
- `docs/ENVS.md`: documenta las nuevas variables.
- `docs/ESTADO_BACKEND.md`: documenta contratos, migracion y multiples App Clients.
- `sql/20261030_proyectos_participantes.sql`: migracion SQL opcional e idempotente.
- `DESPLIEGUE_EC2.md`: documento historico sanitizado, sin IP, credenciales ni identificadores reales.
- `DESPLIEGUE_SYNC PASS.md`: este documento.

## 4. Variables de entorno requeridas

No guardar credenciales en el repositorio ni en el ZIP. El archivo `.env` debe existir solo en el servidor y con permisos restringidos.

```properties
SPRING_PROFILES_ACTIVE=prod

DATABASE_URL=jdbc:postgresql://<SUPABASE_POOLER_HOST>:6543/postgres?sslmode=require
DATABASE_USER=<SUPABASE_USER>
DATABASE_PASSWORD=<SUPABASE_PASSWORD>

AWS_REGION=<AWS_REGION>
AWS_COGNITO_USER_POOL_ID=<USER_POOL_ID>
AWS_COGNITO_CLIENT_ID=<BACKEND_APP_CLIENT_ID>
AWS_COGNITO_CLIENT_SECRET=<BACKEND_APP_CLIENT_SECRET_O_VACIO>
AWS_COGNITO_ALLOWED_CLIENT_IDS=<ANDROID_APP_CLIENT_ID>,<REACT_APP_CLIENT_ID>
SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI=https://cognito-idp.<AWS_REGION>.amazonaws.com/<USER_POOL_ID>

CORS_ENABLED=true
CORS_ALLOWED_ORIGINS=<URL_REACT>
CORS_ALLOWED_METHODS=GET,POST,PUT,PATCH,DELETE,OPTIONS
CORS_ALLOWED_HEADERS=*
CORS_EXPOSED_HEADERS=Authorization,Content-Type
CORS_ALLOW_CREDENTIALS=false
CORS_MAX_AGE=3600

SERVER_PORT=8080
```

Notas:

- `DATABASE_URL` debe tener formato JDBC (`jdbc:postgresql://...`), no formato `postgres://`.
- Para Supabase se recomienda el pooler en modo transaccion, puerto 6543, con `sslmode=require`.
- `AWS_COGNITO_CLIENT_ID` es el App Client que usa el backend para login/registro.
- `AWS_COGNITO_ALLOWED_CLIENT_IDS` contiene los App Clients de Android y React separados por coma. El backend agrega automaticamente `AWS_COGNITO_CLIENT_ID` a la lista permitida.
- `AWS_COGNITO_CLIENT_SECRET` solo se usa si el App Client del backend tiene secreto habilitado.
- `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI` es obligatorio para que Spring Security pueda validar los JWT.

## 5. Cognito: Android y React en el mismo User Pool

Los tres clientes deben pertenecer al mismo User Pool:

1. App Client del backend: usado por `/api/auth/login` y `/api/auth/registro`. Puede tener secreto si el backend lo maneja.
2. App Client de Android: normalmente publico, sin secreto.
3. App Client de React: normalmente SPA publico, sin secreto, con PKCE.

Configuracion recomendada en Cognito:

- Mantener el mismo `User Pool ID` y el mismo issuer para todos.
- Registrar los callbacks y logout URLs reales de React y Android en cada App Client.
- Habilitar los flujos OAuth que ya use cada cliente. Para el backend se mantiene `USER_PASSWORD_AUTH` porque el login se hace por proxy.
- No habilitar acceso anonimo ni desactivar la validacion JWT.
- Configurar `AWS_COGNITO_ALLOWED_CLIENT_IDS` con los IDs de Android y React.
- Los clientes deben enviar `Authorization: Bearer <access_token>`.
- Esta configuracion exige `token_use=access`. Si un cliente envia un ID token, sera rechazado; debe usar el access token.

La validacion aplicada es:

- Firma del JWT contra el issuer/JWKS de Cognito.
- Issuer correcto.
- `token_use` igual a `access`.
- `client_id` incluido en la lista permitida.

## 6. Base de datos y migracion

La aplicacion usa `spring.jpa.hibernate.ddl-auto=update`, por lo que Hibernate puede crear o actualizar las tablas al iniciar sin borrar datos existentes.

Migracion opcional incluida:

```text
sql/20261030_proyectos_participantes.sql
```

Es idempotente y crea/verifica:

- Tabla `proyectos`.
- Tabla `participantes`.
- Clave foranea `fk_participantes_proyecto`.
- Restriccion unica `uk_participante_proyecto_email` sobre `(proyecto_id, email)`.
- Restriccion de estado `ck_proyectos_estado`.
- Indice `idx_participantes_proyecto_id`.

Recomendaciones:

- Hacer respaldo de la base antes de cualquier cambio manual.
- No ejecutar el script sobre produccion sin revisar antes el esquema y los datos existentes.
- Si la tabla ya fue creada por Hibernate, el script no la borra ni la recrea.
- Si existieran datos con estados distintos a `ACTIVO`, `INACTIVO` o `FINALIZADO`, corregirlos antes de aplicar la restriccion `ck_proyectos_estado`.
- Verificar despues:

```sql
SELECT table_name
FROM information_schema.tables
WHERE table_schema = 'public'
  AND table_name IN ('proyectos', 'participantes');

SELECT conname, contype
FROM pg_constraint
WHERE conname IN (
  'fk_participantes_proyecto',
  'uk_participante_proyecto_email',
  'ck_proyectos_estado'
);
```

## 7. Respaldo antes del despliegue

En EC2, identificar el JAR y el proceso actuales:

```bash
cd /home/ec2-user
ls -lh *.jar
ps -ef | grep '[j]ava -jar'
```

Crear respaldo del JAR actual con fecha:

```bash
cd /home/ec2-user
cp cursos-0.0.1-SNAPSHOT.jar "cursos-0.0.1-SNAPSHOT.backup-$(date +%Y%m%d-%H%M%S).jar"
ls -lh cursos-0.0.1-SNAPSHOT.backup-*.jar
```

Respaldar tambien el `.env` actual sin mostrarlo en pantalla:

```bash
cd /home/ec2-user
cp .env ".env.backup-$(date +%Y%m%d-%H%M%S)"
chmod 600 .env .env.backup-*
```

Si el backend se ejecuta con systemd, respaldar tambien la unidad existente:

```bash
sudo cp /etc/systemd/system/<SERVICIO>.service /etc/systemd/system/<SERVICIO>.service.backup-$(date +%Y%m%d-%H%M%S)
```

## 8. Compilacion y carga del nuevo JAR

La compilacion se realiza fuera de este documento y no fue ejecutada por quien preparo esta entrega. Requiere JDK 21 y el Gradle Wrapper incluido.

En el equipo de desarrollo:

```bash
./gradlew clean bootJar
```

El artefacto esperado es:

```text
build/libs/cursos-0.0.1-SNAPSHOT.jar
```

Copiar el JAR a EC2:

```bash
scp -i <RUTA_LLAVE.pem> \
  build/libs/cursos-0.0.1-SNAPSHOT.jar \
  ec2-user@<EC2_HOST>:/home/ec2-user/cursos-0.0.1-SNAPSHOT.jar.nuevo
```

No sobrescribir el JAR en uso hasta haber hecho el respaldo del paso 7.

## 9. Despliegue con el mecanismo actual (nohup)

Si el backend actual se inicia con `nohup`, usar este procedimiento.

1. Detener el proceso actual:

```bash
sudo fuser -k 8080/tcp || true
pkill -f 'java -jar cursos-0.0.1-SNAPSHOT.jar' || true
sleep 3
ps -ef | grep '[j]ava -jar' || true
```

2. Reemplazar el JAR:

```bash
cd /home/ec2-user
mv cursos-0.0.1-SNAPSHOT.jar.nuevo cursos-0.0.1-SNAPSHOT.jar
chmod 644 cursos-0.0.1-SNAPSHOT.jar
```

3. Verificar que `.env` existe y tiene permisos restringidos:

```bash
cd /home/ec2-user
test -f .env && chmod 600 .env
```

4. Iniciar la aplicacion:

```bash
cd /home/ec2-user
nohup java -jar cursos-0.0.1-SNAPSHOT.jar > app.log 2>&1 &
echo $!
sleep 15
tail -100 app.log
```

5. Confirmar que el puerto esta escuchando:

```bash
ss -ltnp | grep ':8080' || true
```

## 10. Despliegue con systemd (solo si ya existe el servicio)

Si el backend ya esta administrado por systemd, no crear unidades nuevas sin coordinarlo. Usar el servicio existente:

```bash
sudo systemctl stop <SERVICIO>
cd /home/ec2-user
mv cursos-0.0.1-SNAPSHOT.jar.nuevo cursos-0.0.1-SNAPSHOT.jar
sudo systemctl start <SERVICIO>
sudo systemctl status <SERVICIO> --no-pager
sudo journalctl -u <SERVICIO> -n 100 --no-pager
```

## 11. Validacion posterior al despliegue

### 11.1 Health check

```bash
curl -i http://<EC2_HOST>:8080/api/health
```

Esperado: HTTP 200 y `success=true`, `data.status=UP`, `data.service=cursos-api`.

### 11.2 Login

```bash
curl -i -X POST http://<EC2_HOST>:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"<TEST_EMAIL>","password":"<TEST_PASSWORD>"}'
```

Esperado: HTTP 200 con `data.accessToken`, `data.idToken` y `data.refreshToken`.

Guardar el access token para las pruebas de CRUD:

```bash
export ACCESS_TOKEN='<ACCESS_TOKEN>'
```

### 11.3 Crear proyecto

```bash
curl -i -X POST http://<EC2_HOST>:8080/api/proyectos \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"nombre":"Proyecto Validacion","descripcion":"Prueba de despliegue","estado":"ACTIVO"}'
```

Esperado: HTTP 201 y `success=true` con `data.id`.

Guardar el ID retornado:

```bash
export PROYECTO_ID='<PROJECT_ID>'
```

### 11.4 Consultar y actualizar proyecto

```bash
curl -i http://<EC2_HOST>:8080/api/proyectos \
  -H "Authorization: Bearer $ACCESS_TOKEN"

curl -i http://<EC2_HOST>:8080/api/proyectos/$PROYECTO_ID \
  -H "Authorization: Bearer $ACCESS_TOKEN"

curl -i -X PUT http://<EC2_HOST>:8080/api/proyectos/$PROYECTO_ID \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"nombre":"Proyecto Validacion","descripcion":"Actualizado","estado":"INACTIVO"}'
```

Esperado: HTTP 200 en las tres operaciones.

### 11.5 Crear participante

```bash
curl -i -X POST http://<EC2_HOST>:8080/api/participantes \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"nombre\":\"Participante Validacion\",\"email\":\"participante.validacion@example.com\",\"proyectoId\":$PROYECTO_ID}"
```

Esperado: HTTP 201 y `data.proyectoId` igual a `$PROYECTO_ID`.

Guardar el ID retornado:

```bash
export PARTICIPANTE_ID='<PARTICIPANT_ID>'
```

### 11.6 Consultar participantes y filtrar por proyecto

```bash
curl -i http://<EC2_HOST>:8080/api/participantes \
  -H "Authorization: Bearer $ACCESS_TOKEN"

curl -i "http://<EC2_HOST>:8080/api/participantes?proyectoId=$PROYECTO_ID" \
  -H "Authorization: Bearer $ACCESS_TOKEN"

curl -i http://<EC2_HOST>:8080/api/participantes/$PARTICIPANTE_ID \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

Esperado: HTTP 200 y solo participantes del proyecto cuando se usa el filtro.

### 11.7 Actualizar y eliminar participante

```bash
curl -i -X PUT http://<EC2_HOST>:8080/api/participantes/$PARTICIPANTE_ID \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"nombre\":\"Participante Actualizado\",\"email\":\"participante.actualizado@example.com\",\"proyectoId\":$PROYECTO_ID}"

curl -i -X DELETE http://<EC2_HOST>:8080/api/participantes/$PARTICIPANTE_ID \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

Esperado: HTTP 200 en PUT y HTTP 204 sin cuerpo en DELETE.

### 11.8 Eliminar proyecto de validacion

Primero eliminar los participantes asociados. Luego:

```bash
curl -i -X DELETE http://<EC2_HOST>:8080/api/proyectos/$PROYECTO_ID \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

Esperado: HTTP 204 sin cuerpo.

### 11.9 Casos negativos recomendados

- Sin token: debe responder 401.
- Token de un App Client no incluido en `AWS_COGNITO_ALLOWED_CLIENT_IDS`: debe responder 401.
- `POST /api/proyectos` con estado `PENDIENTE`: debe responder 400.
- `GET /api/proyectos/999999999`: debe responder 404.
- `GET /api/proyectos/abc`: debe responder 400.
- Eliminar un proyecto con participantes: debe responder 409.
- Crear dos participantes con el mismo email en el mismo proyecto: debe responder 409.

## 12. Rollback

Si la validacion falla:

1. Detener la version nueva:

```bash
sudo fuser -k 8080/tcp || true
pkill -f 'java -jar cursos-0.0.1-SNAPSHOT.jar' || true
sleep 3
```

2. Restaurar el JAR respaldado:

```bash
cd /home/ec2-user
cp "$(ls -1t cursos-0.0.1-SNAPSHOT.backup-*.jar | head -1)" cursos-0.0.1-SNAPSHOT.jar
```

3. Restaurar el `.env` si fue modificado:

```bash
cd /home/ec2-user
cp "$(ls -1t .env.backup-* | head -1)" .env
chmod 600 .env
```

4. Reiniciar con el mecanismo original (nohup o systemd).

5. Validar:

```bash
curl -i http://<EC2_HOST>:8080/api/health
curl -i -X POST http://<EC2_HOST>:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"<TEST_EMAIL>","password":"<TEST_PASSWORD>"}'
```

Si se aplico la migracion SQL y el rollback requiere volver a un esquema anterior, restaurar el respaldo de la base de datos. La migracion incluida solo agrega tablas, columnas, indices y restricciones; no elimina datos.

Nota: al volver al backend antiguo, `GET /api/proyectos` volvera a responder con los datos simulados y no existira el CRUD de participantes.

## 13. Checklist de entrega

- [ ] El ZIP no contiene `.env`, credenciales, `.git`, `build`, `.gradle`, `.idea`, logs ni temporales.
- [ ] El JAR nuevo fue compilado con `./gradlew clean bootJar`.
- [ ] Se respaldo el JAR anterior y el `.env` en EC2.
- [ ] El `.env` de EC2 contiene todas las variables del paso 4.
- [ ] `DATABASE_URL` usa formato JDBC y el pooler de Supabase en puerto 6543 con SSL.
- [ ] `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI` coincide con el User Pool.
- [ ] `AWS_COGNITO_ALLOWED_CLIENT_IDS` incluye los App Clients de Android y React.
- [ ] Los App Clients pertenecen al mismo User Pool y tienen callbacks/logout correctos.
- [ ] CORS permite el origen real de React.
- [ ] El puerto 8080 esta libre antes de iniciar.
- [ ] Health check responde 200.
- [ ] Login responde 200 con access token.
- [ ] CRUD de proyectos y participantes responde segun los contratos del paso 2.
- [ ] DELETE responde 204.
- [ ] Existe un procedimiento de rollback probado.

## 14. Configuraciones pendientes de verificar en AWS

No se modifico ninguna configuracion de AWS. Antes de desplegar, el responsable de EC2/AWS debe verificar:

- IDs reales de los App Clients de backend, Android y React.
- Que los tres App Clients pertenezcan al mismo User Pool.
- Que el issuer configurado corresponda al User Pool real.
- Callbacks, logout URLs y flujos OAuth habilitados en cada App Client.
- Que Android y React usen access tokens, no ID tokens, para llamar a la API.
- Credenciales de AWS disponibles en EC2 para el SDK de Cognito (rol IAM de instancia o variables de entorno), sin exponerlas en el repositorio.
- Security Group y puerto 8080 accesibles solo desde los origenes/redes necesarios.
- Origen real de React en `CORS_ALLOWED_ORIGINS`.
- Credenciales y host reales de Supabase, con SSL y pooler en puerto 6543.
- Existencia de respaldo reciente de Supabase.
- Esquema de `proyectos` y `participantes` antes de iniciar la aplicacion.
