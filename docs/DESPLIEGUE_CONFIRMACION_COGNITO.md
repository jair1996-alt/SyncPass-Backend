# Actualización controlada: confirmación automática de usuarios Cognito

Procedimiento para desplegar el JAR que incorpora `AdminConfirmSignUp` en el
registro administrativo de SyncPass.

- Servicio systemd: `syncpass-api.service`
- Directorio de la aplicación: `/home/ec2-user/syncpass-deploy`
- Artefacto local: `build/libs/cursos-0.0.1-SNAPSHOT.jar`
- Health check interno: `http://127.0.0.1:8080/api/health`
- Health check público: `https://<DOMINIO_PUBLICO>/api/health` (Caddy)

Este documento **no ejecuta el despliegue**. Todos los comandos son manuales y
requieren autorización previa.

## 0. Qué cambia y qué no cambia

Cambia únicamente el JAR de la aplicación. El nuevo código llama a
`AdminConfirmSignUp` después de `SignUp` y antes de asignar grupos y guardar en
PostgreSQL. No modifica atributos `email_verified` ni `phone_number_verified`.

El procedimiento **no toca**:

- `.env` ni ningún secreto.
- La unidad `/etc/systemd/system/syncpass-api.service`.
- La configuración de Caddy ni sus certificados.
- La base de datos, el esquema, las migraciones SQL ni datos persistentes.
- Usuarios, grupos o políticas de Cognito.
- El frontend.

No se ejecutan operaciones sobre usuarios reales de Cognito. La confirmación
automática solo se ejercitará cuando un administrador cree un usuario nuevo a
través de la API, después del despliegue y con autorización funcional aparte.

## 1. Verificación previa en EC2 (solo lectura)

```bash
sudo systemctl status syncpass-api.service --no-pager
sudo systemctl show syncpass-api.service \
  -p ExecStart -p WorkingDirectory -p User -p Group
sudo systemctl cat syncpass-api.service
ls -lh /home/ec2-user/syncpass-deploy
```

Registrar de `ExecStart` el nombre exacto del JAR en uso. En los comandos
siguientes se usa la variable `JAR_NAME`; si el servicio ejecuta otro nombre
(por ejemplo `app.jar`), ajustarla al valor observado. Usar también el mismo
`User`/`Group` que muestre `systemctl show` en los comandos `install`; si no
define usuario, el servicio corre como `root` y deben omitirse `-o` y `-g`.

```bash
APP_DIR=/home/ec2-user/syncpass-deploy
SERVICE=syncpass-api.service
JAR_NAME='cursos-0.0.1-SNAPSHOT.jar'   # <-- usar el nombre real observado
JAR_PATH="$APP_DIR/$JAR_NAME"

test -f "$JAR_PATH"
```

Comprobar el rol IAM y la conectividad, sin modificar nada:

```bash
aws sts get-caller-identity
```

El rol esperado es `LabRole`. La simulación IAM de
`cognito-idp:AdminConfirmSignUp`, `cognito-idp:AdminAddUserToGroup` y
`cognito-idp:AdminDeleteUser` ya fue validada como `allowed`.

Comprobar que el health actual responde antes de cambiar nada:

```bash
curl -fsS http://127.0.0.1:8080/api/health
```

## 2. Respaldo del JAR anterior

```bash
APP_DIR=/home/ec2-user/syncpass-deploy
JAR_NAME='cursos-0.0.1-SNAPSHOT.jar'   # <-- usar el nombre real observado
JAR_PATH="$APP_DIR/$JAR_NAME"
BACKUP="$APP_DIR/$JAR_NAME.bak-$(date +%Y%m%d-%H%M%S)"

test ! -e "$BACKUP"
cp -a "$JAR_PATH" "$BACKUP"
ls -lh "$BACKUP"
sha256sum "$JAR_PATH" "$BACKUP"
```

Anotar la ruta exacta de `$BACKUP`. Ambos SHA256 deben coincidir.

No es necesario respaldar `.env` para este cambio porque el procedimiento no lo
modifica. Si por alguna razón se decidiera respaldarlo, hacerlo sin mostrarlo en
pantalla y sin reemplazarlo después salvo que se haya modificado:

```bash
# Opcional y NO forma parte del cambio de JAR.
cp -a "$APP_DIR/.env" "$APP_DIR/.env.bak-$(date +%Y%m%d-%H%M%S)"
chmod 600 "$APP_DIR/.env" "$APP_DIR"/.env.bak-*
```

## 3. Compilación local y transferencia

En el equipo de desarrollo, con JDK 21:

```bash
./gradlew cleanTest test
./gradlew clean bootJar
```

En PowerShell (Windows):

```powershell
.\gradlew.bat cleanTest test
.\gradlew.bat clean bootJar
Get-FileHash build\libs\cursos-0.0.1-SNAPSHOT.jar -Algorithm SHA256
```

Transferir el JAR con un nombre temporal `.nuevo`. No sobrescribir el JAR en uso
todavía:

```bash
scp -i "<RUTA_LLAVE.pem>" \
  build/libs/cursos-0.0.1-SNAPSHOT.jar \
  ec2-user@<EC2_HOST>:/home/ec2-user/syncpass-deploy/cursos-0.0.1-SNAPSHOT.jar.nuevo
```

Si el servicio usa otro nombre de JAR, subirlo como
`/home/ec2-user/syncpass-deploy/<JAR_NAME>.nuevo`.

En EC2, verificar que la transferencia llegó completa comparando el SHA256 con
el obtenido localmente:

```bash
sha256sum /home/ec2-user/syncpass-deploy/cursos-0.0.1-SNAPSHOT.jar.nuevo
```

No continuar si los hashes no coinciden.

## 4. Instalación y reinicio del servicio

```bash
APP_DIR=/home/ec2-user/syncpass-deploy
SERVICE=syncpass-api.service
JAR_NAME='cursos-0.0.1-SNAPSHOT.jar'   # <-- usar el nombre real observado
JAR_PATH="$APP_DIR/$JAR_NAME"
NUEVO="$APP_DIR/cursos-0.0.1-SNAPSHOT.jar.nuevo"

test -f "$NUEVO"

sudo systemctl stop "$SERVICE"

# Reemplaza únicamente el JAR. El respaldo ya existe en $BACKUP.
sudo install -o ec2-user -g ec2-user -m 644 "$NUEVO" "$JAR_PATH"

sudo systemctl start "$SERVICE"
```

No copiar `.env`, no editar la unidad systemd, no reiniciar Caddy, no ejecutar
SQL y no eliminar el respaldo.

## 5. Validación posterior

```bash
sudo systemctl is-active syncpass-api.service
sudo systemctl status syncpass-api.service --no-pager
sudo journalctl -u syncpass-api.service -n 100 --no-pager
```

Health interno, con reintentos durante el arranque:

```bash
for i in $(seq 1 30); do
  CODE=$(curl -sS -o /tmp/syncpass-health.json -w '%{http_code}' \
    http://127.0.0.1:8080/api/health || true)
  [ "$CODE" = "200" ] && break
  sleep 2
done

echo "HTTP $CODE"
cat /tmp/syncpass-health.json
```

Esperado: HTTP 200 y un cuerpo equivalente a:

```json
{
  "success": true,
  "data": {
    "status": "UP",
    "timestamp": "...",
    "service": "cursos-api"
  }
}
```

Health público a través de Caddy:

```bash
curl -i https://<DOMINIO_PUBLICO>/api/health
```

Esperado: HTTP 200, sin redirecciones inesperadas ni errores TLS.

Si ambos health checks son correctos, eliminar solo el archivo temporal:

```bash
rm -f /home/ec2-user/syncpass-deploy/cursos-0.0.1-SNAPSHOT.jar.nuevo
```

Conservar el respaldo `$BACKUP` según la política de retención del equipo.

## 6. Rollback

Ejecutar si el servicio no arranca, el health no responde 200 o aparece un error
relacionado con el nuevo cambio.

```bash
APP_DIR=/home/ec2-user/syncpass-deploy
SERVICE=syncpass-api.service
JAR_NAME='cursos-0.0.1-SNAPSHOT.jar'   # <-- usar el nombre real observado
JAR_PATH="$APP_DIR/$JAR_NAME"
BACKUP="$APP_DIR/$JAR_NAME.bak-<TIMESTAMP_ANOTADO>"

test -f "$BACKUP"

sudo systemctl stop "$SERVICE"
sudo install -o ec2-user -g ec2-user -m 644 "$BACKUP" "$JAR_PATH"
sudo systemctl start "$SERVICE"
```

Validar de nuevo:

```bash
sudo systemctl is-active syncpass-api.service
sudo journalctl -u syncpass-api.service -n 100 --no-pager
curl -i http://127.0.0.1:8080/api/health
curl -i https://<DOMINIO_PUBLICO>/api/health
```

No restaurar `.env` ni la unidad systemd: no fueron modificados por este
procedimiento. Si el rollback también falla, recolectar
`journalctl -u syncpass-api.service -n 200 --no-pager` y detenerse; no seguir
reiniciando ni cambiar configuración sin autorización.

## 7. Confirmación de bajo impacto

- El único archivo reemplazado es el JAR indicado por `ExecStart`.
- El JAR anterior queda respaldado con timestamp antes del reemplazo.
- El JAR nuevo se sube como `.nuevo` y se valida por SHA256.
- `.env`, secretos, unidad systemd, Caddy y base de datos no se escriben.
- No hay migración SQL ni cambio de entidades JPA en este cambio.
- No se crean, confirman ni eliminan usuarios reales durante el despliegue.
- El rollback solo restaura el JAR respaldado y reinicia el mismo servicio.
