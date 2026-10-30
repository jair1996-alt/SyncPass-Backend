Estan definidos en el archivo .env

```
DATABASE_URL=jdbc:postgresql://localhost:5432/dbname
DATABASE_USER=postgres
DATABASE_PASSWORD=postgres
AWS_REGION=us-east-1
AWS_COGNITO_USER_POOL_ID=us-east-1_xxxxx
AWS_COGNITO_CLIENT_ID=xxxxxxxxxxxxxxxxxxxxxxxxxx
AWS_COGNITO_CLIENT_SECRET=
# App Clients adicionales (Android/React) separados por coma
AWS_COGNITO_ALLOWED_CLIENT_IDS=
# Obligatorio para validar JWT del User Pool
SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI=https://cognito-idp.us-east-1.amazonaws.com/us-east-1_xxxxx

# CORS Configuration
CORS_ENABLED=true
CORS_ALLOWED_ORIGINS=*
CORS_ALLOWED_METHODS=GET,POST,PUT,PATCH,DELETE,OPTIONS
CORS_ALLOWED_HEADERS=*
CORS_EXPOSED_HEADERS=Authorization,Content-Type
CORS_ALLOW_CREDENTIALS=false
CORS_MAX_AGE=3600

# Test user
TEST_USER_EMAIL=test@example.com
TEST_USER_PASSWORD=secret
```

Utilizar `Test user`

- Para validar que la integración con Cognito es correcta
- Validar con curl levantando en localhost