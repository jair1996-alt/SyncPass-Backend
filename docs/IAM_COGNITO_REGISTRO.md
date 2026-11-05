# Permisos IAM para el registro administrativo de usuarios

Este documento describe los permisos que necesita la identidad IAM usada por el
backend para crear usuarios en Amazon Cognito. **No se ha modificado ninguna
política IAM.**

`CognitoConfig` obtiene las credenciales mediante `DefaultCredentialsProvider`,
por lo que en AWS corresponden al rol de instancia de EC2 (o al rol/perfil que
se haya configurado en el entorno).

## Operaciones utilizadas por `AuthService.registrar`

| Operación Cognito | Acción IAM | Estado |
| --- | --- | --- |
| `SignUp` | Ninguna (API pública de app client) | Ya utilizada |
| `AdminConfirmSignUp` | `cognito-idp:AdminConfirmSignUp` | **Nueva, obligatoria** |
| `AdminAddUserToGroup` | `cognito-idp:AdminAddUserToGroup` | Ya utilizada |
| `AdminDeleteUser` (compensación ante fallo parcial) | `cognito-idp:AdminDeleteUser` | Ya utilizada |

Las operaciones públicas `InitiateAuth`, `GlobalSignOut`, `ForgotPassword` y
`ConfirmForgotPassword` no requieren permisos IAM.

## Permiso que falta

La única acción nueva es:

```text
cognito-idp:AdminConfirmSignUp
```

Sin ella, `AdminConfirmSignUp` devuelve `AccessDeniedException` /
`NotAuthorizedException`, la creación se considera fallida y el backend intenta
compensar eliminando la cuenta recién creada. Si esa compensación también
falla, la API responde con
`USER_CREATION_REQUIRES_RECONCILIATION`.

## Política mínima de ejemplo

Sustituir `REGION`, `ACCOUNT_ID` y `USER_POOL_ID` por los valores reales. No
aplicar esta política sin revisión previa:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "SyncPassRegistroAdministrativoCognito",
      "Effect": "Allow",
      "Action": [
        "cognito-idp:AdminConfirmSignUp",
        "cognito-idp:AdminAddUserToGroup",
        "cognito-idp:AdminDeleteUser"
      ],
      "Resource": "arn:aws:cognito-idp:REGION:ACCOUNT_ID:userpool/USER_POOL_ID"
    }
  ]
}
```

`AdminDeleteUser` solo se utiliza para revertir la cuenta creada en la misma
solicitud. Nunca se elimina una cuenta preexistente: el `sub` que se borra es
exactamente el devuelto por el `SignUp` de esa solicitud.

## Verificación sin modificar IAM

Se puede comprobar si el rol actual tiene el permiso con AWS CLI, sin ejecutar
operaciones sobre Cognito:

```bash
aws iam simulate-principal-policy \
  --policy-source-arn arn:aws:iam::ACCOUNT_ID:role/NOMBRE_DEL_ROL \
  --action-names cognito-idp:AdminConfirmSignUp \
  --resource-arns arn:aws:cognito-idp:REGION:ACCOUNT_ID:userpool/USER_POOL_ID
```

## Nota sobre verificación de email y teléfono

`AdminConfirmSignUp` **no** verifica el correo ni el teléfono. Solo cambia el
estado de la cuenta de `UNCONFIRMED` a `CONFIRMED`.

El código no establece `email_verified=true` ni `phone_number_verified=true`.
Si la configuración del User Pool usa el email como *alias* y exige que esté
verificado para iniciar sesión con él, deberá revisarse la configuración del
pool por separado; no se resuelve marcando atributos como verificados sin una
verificación real.
