# Especificación Técnica: Sistema de Autenticación Proxy con Spring Boot y AWS Cognito

## 1. Arquitectura del Sistema
El backend (Spring Boot) actuará como un componente proxy/fachada para todas las interacciones con AWS Cognito. El frontend nunca se comunicará directamente con AWS. 
Toda persistencia local utilizará el identificador único `sub` generado por Cognito como Clave Primaria (`@Id`) para mantener la integridad referencial y facilitar consultas complejas, ordenamiento y asociaciones con otras entidades.

---

## 2. Configuración del Entorno (`application.yml`)
Todas las variables sensibles e identificadores de AWS deben cargarse desde variables de entorno con valores por defecto locales seguros.

```yaml
aws:
  cognito:
    region: ${AWS_REGION:us-east-1}
    user-pool-id: ${AWS_COGNITO_USER_POOL_ID:us-east-1_xxxxxxxxx}
    client-id: ${AWS_COGNITO_CLIENT_ID:xxxxxxxxxxxxxxxxxxxxxxxxxx}
    client-secret: ${AWS_COGNITO_CLIENT_SECRET:} # Opcional: Solo si el App Client tiene Client Secret activado

spring:
  datasource:
    url: ${DATABASE_URL:jdbc:postgresql://localhost:5432/mi_base_datos}
    username: ${DATABASE_USER:postgres}
    password: ${DATABASE_PASSWORD:postgres}
  jpa:
    hibernate:
      ddl-auto: update
    show-sql: true
```

---

## 3. Modelo de Base de Datos (Muchos a Muchos para Roles/Grupos)
Un usuario puede pertenecer a múltiples grupos de Cognito. Esto se mapeará localmente en una tabla principal y una tabla relacional de colecciones de elementos.

### 3.1 Entidad `Usuario.java`
```java
package com.example.auth.model;

import jakarta.persistence.*;
import lombok.Data;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "usuarios")
@Data
public class Usuario {

    @Id
    @Column(name = "id", length = 64)
    private String id; // Mapeado directamente al UUID 'sub' de Cognito

    @Column(unique = true, nullable = false)
    private String email;

    @Column(nullable = false)
    private String nombre;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
        name = "usuario_roles", 
        joinColumns = @JoinColumn(name = "usuario_id")
    )
    @Column(name = "rol", nullable = false)
    private Set<String> roles = new HashSet<>();
}
```

---

## 4. Configuración del Cliente SDK v2 de AWS
El cliente de AWS Cognito debe inyectarse de forma centralizada utilizando las propiedades definidas en el archivo de propiedades.

### 4.1 Clase `CognitoConfig.java`
```java
package com.example.auth.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;

@Configuration
public class CognitoConfig {

    @Value("${aws.cognito.region}")
    private String region;

    @Bean
    public CognitoIdentityProviderClient cognitoClient() {
        return CognitoIdentityProviderClient.builder()
                .region(Region.of(region))
                // Detecta automáticamente credenciales mediante DefaultCredentialsProvider
                .build();
    }
}
```

---

## 5. Capa de Servicios (`AuthService.java`)
Esta capa maneja los flujos de negocio e interactúa directamente con el SDK de AWS Cognito y los repositorios de la base de datos local.

```java
package com.example.auth.service;

import com.example.auth.model.Usuario;
import com.example.auth.repository.UsuarioRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.*;

import java.util.Map;
import java.util.Set;

@Service
public class AuthService {

    private final CognitoIdentityProviderClient cognitoClient;
    private final UsuarioRepository usuarioRepository;

    @Value("${aws.cognito.client-id}")
    private String clientId;

    @Value("${aws.cognito.user-pool-id}")
    private String userPoolId;

    public AuthService(CognitoIdentityProviderClient cognitoClient, UsuarioRepository usuarioRepository) {
        this.cognitoClient = cognitoClient;
        this.usuarioRepository = usuarioRepository;
    }

    /**
     * FLUJO: REGISTRO Y COPIA LOCAL CON MÚLTIPLES ROLES
     */
    @Transactional
    public Usuario registrar(String email, String password, String nombre, Set<String> roles) {
        // 1. Registro en Cognito
        SignUpRequest signUpRequest = SignUpRequest.builder()
                .clientId(clientId)
                .username(email)
                .password(password)
                .userAttributes(
                        AttributeType.builder().name("email").value(email).build(),
                        AttributeType.builder().name("name").value(nombre).build()
                )
                .build();
        SignUpResponse signUpResponse = cognitoClient.signUp(signUpRequest);
        String cognitoSub = signUpResponse.userSub();

        // 2. Asignación a Grupos en Cognito (Roles)
        for (String rol : roles) {
            AdminAddUserToGroupRequest groupRequest = AdminAddUserToGroupRequest.builder()
                    .userPoolId(userPoolId)
                    .username(email)
                    .groupName(rol)
                    .build();
            cognitoClient.adminAddUserToGroup(groupRequest);
        }

        // 3. Persistencia en Base de Datos Local
        Usuario usuarioLocal = new Usuario();
        usuarioLocal.setId(cognitoSub);
        usuarioLocal.setEmail(email);
        usuarioLocal.setNombre(nombre);
        usuarioLocal.setRoles(roles);

        return usuarioRepository.save(usuarioLocal);
    }

    /**
     * FLUJO: INICIO DE SESIÓN (LOGIN) PROXY
     */
    public AuthenticationResultType login(String email, String password) {
        InitiateAuthRequest authRequest = InitiateAuthRequest.builder()
                .authFlow(AuthFlowType.USER_PASSWORD_AUTH)
                .clientId(clientId)
                .authParameters(Map.of(
                        "USERNAME", email,
                        "PASSWORD", password
                ))
                .build();

        InitiateAuthResponse authResponse = cognitoClient.initiateAuth(authRequest);
        return authResponse.authenticationResult();
    }

    /**
     * FLUJO: CIERRE DE SESIÓN GLOBAL (LOGOUT)
     */
    public void logout(String accessToken) {
        GlobalSignOutRequest signOutRequest = GlobalSignOutRequest.builder()
                .accessToken(accessToken)
                .build();
        cognitoClient.globalSignOut(signOutRequest);
    }

    /**
     * FLUJO: OLVIDÉ MI CONTRASEÑA - PASO 1 (SOLICITUD)
     */
    public void solicitarRecuperacionContrasena(String email) {
        ForgotPasswordRequest request = ForgotPasswordRequest.builder()
                .clientId(clientId)
                .username(email)
                .build();
        cognitoClient.forgotPassword(request);
    }

    /**
     * FLUJO: OLVIDÉ MI CONTRASEÑA - PASO 2 (CONFIRMACIÓN)
     */
    public void confirmarRecuperacionContrasena(String email, String codigoOto, String nuevaContrasena) {
        ConfirmForgotPasswordRequest request = ConfirmForgotPasswordRequest.builder()
                .clientId(clientId)
                .username(email)
                .confirmationCode(codigoOto)
                .password(nuevaContrasena)
                .build();
        cognitoClient.confirmForgotPassword(request);
    }
}
```

---

## 6. Endpoints de la API (`AuthController.java`)
Contratos de entrada y salida estructurados en formato RESTful JSON.

```java
package com.example.auth.controller;

import com.example.auth.model.Usuario;
import com.example.auth.service.AuthService;
import lombok.Data;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AuthenticationResultType;
import java.util.Set;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/registro")
    public ResponseEntity<Usuario> registrar(@RequestBody RegistroRequest req) {
        Usuario usuario = authService.registrar(req.getEmail(), req.getPassword(), req.getNombre(), req.getRoles());
        return ResponseEntity.ok(usuario);
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@RequestBody LoginRequest req) {
        AuthenticationResultType result = authService.login(req.getEmail(), req.getPassword());
        
        LoginResponse response = new LoginResponse();
        response.setAccessToken(result.accessToken());
        response.setIdToken(result.idToken());
        response.setRefreshToken(result.refreshToken());
        response.setExpiresIn(result.expiresIn());
        
        return ResponseEntity.ok(response);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestHeader("Authorization") String bearerToken) {
        // Remover el prefijo 'Bearer ' si está presente en el header
        String accessToken = bearerToken.substring(7);
        authService.logout(accessToken);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/olvide-contrasena/solicitar")
    public ResponseEntity<Void> solicitarPassword(@RequestBody SolicitudPasswordRequest req) {
Usa el código con precaución.
authService.solicitarRecuperacionContrasena(req.getEmail());
return ResponseEntity.ok().build();
}
@PostMapping("/olvide-contrasena/confirmar")
public ResponseEntity confirmarPassword(@RequestBody ConfirmarPasswordRequest req) {
authService.confirmarRecuperacionContrasena(req.getEmail(), req.getCodigo(), req.getNuevaContrasena());
return ResponseEntity.ok().build();
}
}
// ==========================================
// DATA TRANSFER OBJECTS (DTOs)
// ==========================================
@Data class RegistroRequest {
private String email;
private String password;
private String nombre;
private Set roles;
}
@Data class LoginRequest {
private String email;
private String password;
}
@Data class LoginResponse {
private String accessToken;
private String idToken;
private String refreshToken;
private Integer expiresIn;
}
@Data class SolicitudPasswordRequest {
private String email;
}
@Data class ConfirmarPasswordRequest {
private String email;
private String codigo;
private String nuevaContrasena;
}
```

7. Directivas para la Implementación (Instrucciones para la IA)

1. Manejo de Errores Globales: Implementar un @RestControllerAdvice para capturar excepciones específicas del SDK de AWS como UsernameExistsException, NotAuthorizedException, InvalidPasswordException o CodeMismatchException y mapearlas a estados HTTP semánticos (400 Bad Request, 401 Unauthorized, etc.).
2. Seguridad del App Client: Si el Cliente de Aplicación en Cognito se configuró para requerir un Client Secret, la lógica del Servicio deberá generar el parámetro SECRET_HASH usando el algoritmo HMAC-SHA256 combinando el USERNAME y el CLIENT_SECRET en cada petición.
3. Mecanismo Transaccional: La anotación @Transactional en el método registrar asegura que si el flujo de asignación de grupos de AWS o la persistencia local en JPA fallan catastróficamente, no queden datos corruptos o huérfanos.

***

<FollowUp>
¿Tu **App Client de Cognito** fue creado con la opción de generar un **Client Secret** (Secreto de Cliente), o está configurado de forma pública (sin secreto)? Te lo pregunto porque si tiene secreto, se requiere agregar una función matemática de hash (`SECRET_HASH`) para que AWS acepte las peticiones de inicio de sesión.
</FollowUp>
