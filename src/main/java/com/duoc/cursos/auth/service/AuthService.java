package com.duoc.cursos.auth.service;

import com.duoc.cursos.auth.dto.UsuarioResponse;
import com.duoc.cursos.auth.model.Usuario;
import com.duoc.cursos.auth.repository.UsuarioRepository;
import com.duoc.cursos.common.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.*;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class AuthService {

    private static final String ROL_ESTUDIANTE = "ESTUDIANTE";

    private static final Set<String> ROLES_PERMITIDOS = Set.of(
            ROL_ESTUDIANTE,
            "PROFESOR",
            "COORDINADOR",
            "ADMINISTRADOR"
    );

    private final CognitoIdentityProviderClient cognitoClient;
    private final UsuarioRepository usuarioRepository;

    @Value("${aws.cognito.client-id}")
    private String clientId;

    @Value("${aws.cognito.user-pool-id}")
    private String userPoolId;

    @Value("${aws.cognito.client-secret:}")
    private String clientSecret;

    public AuthService(
            CognitoIdentityProviderClient cognitoClient,
            UsuarioRepository usuarioRepository
    ) {
        this.cognitoClient = cognitoClient;
        this.usuarioRepository = usuarioRepository;
    }

    /**
     * Alta administrativa de usuario. El acceso al endpoint debe estar protegido
     * por Spring Security con ROLE_ADMINISTRADOR.
     */
    public UsuarioResponse registrar(
            String email,
            String password,
            String nombre,
            Set<String> rolesSolicitados
    ) {
        String emailNormalizado = normalizarEmail(email);
        Set<String> roles = validarRoles(rolesSolicitados);

        if (usuarioRepository.existsByEmail(emailNormalizado)) {
            throw new BusinessException(
                    "USER_ALREADY_EXISTS",
                    "El email ya está registrado"
            );
        }

        SignUpRequest.Builder signUpBuilder = SignUpRequest.builder()
                .clientId(clientId)
                .username(emailNormalizado)
                .password(password)
                .userAttributes(
                        AttributeType.builder()
                                .name("email")
                                .value(emailNormalizado)
                                .build(),
                        AttributeType.builder()
                                .name("name")
                                .value(nombre)
                                .build()
                );

        if (clientSecret != null && !clientSecret.isBlank()) {
            signUpBuilder.secretHash(generateSecretHash(emailNormalizado));
        }

        // La operacion puede fallar con resultado remoto ambiguo.
        // En tal caso NO eliminamos cuentas por nombre de usuario.
        SignUpResponse signUpResponse = cognitoClient.signUp(signUpBuilder.build());
        String cognitoSub = signUpResponse.userSub();

        if (cognitoSub == null || cognitoSub.isBlank()) {
            throw new BusinessException(
                    "COGNITO_INVALID_RESPONSE",
                    "Cognito no confirmó el identificador de la cuenta; se requiere revisión administrativa"
            );
        }

        try {
            for (String rol : roles) {
                AdminAddUserToGroupRequest request = AdminAddUserToGroupRequest.builder()
                        .userPoolId(userPoolId)
                        .username(emailNormalizado)
                        .groupName(rol)
                        .build();
                cognitoClient.adminAddUserToGroup(request);
            }

            Usuario usuarioLocal = new Usuario();
            usuarioLocal.setId(cognitoSub);
            usuarioLocal.setEmail(emailNormalizado);
            usuarioLocal.setNombre(nombre);
            usuarioLocal.setRoles(roles);

            Usuario saved = usuarioRepository.save(usuarioLocal);

            return UsuarioResponse.builder()
                    .id(saved.getId())
                    .email(saved.getEmail())
                    .nombre(saved.getNombre())
                    .roles(saved.getRoles())
                    .build();
        } catch (RuntimeException original) {
            try {
                // Solo se intenta compensar una creacion cuya respuesta
                // proporciono un identificador de usuario Cognito.
                cognitoClient.adminDeleteUser(
                        AdminDeleteUserRequest.builder()
                                .userPoolId(userPoolId)
                                .username(emailNormalizado)
                                .build()
                );
            } catch (RuntimeException compensacionFallida) {
                original.addSuppressed(compensacionFallida);
                throw new BusinessException(
                        "USER_CREATION_REQUIRES_RECONCILIATION",
                        "La creación falló y no se pudo revertir en Cognito. Se requiere revisión administrativa.",
                        original
                );
            }

            throw new BusinessException(
                    "USER_CREATION_FAILED",
                    "No se pudo completar la creación del usuario.",
                    original
            );
        }
    }

    /** Inicio de sesión mediante Cognito USER_PASSWORD_AUTH. */
    public AuthenticationResultType login(String email, String password) {
        Map<String, String> authParameters = new HashMap<>();
        authParameters.put("USERNAME", email);
        authParameters.put("PASSWORD", password);

        if (clientSecret != null && !clientSecret.isBlank()) {
            authParameters.put("SECRET_HASH", generateSecretHash(email));
        }

        InitiateAuthRequest authRequest = InitiateAuthRequest.builder()
                .authFlow(AuthFlowType.USER_PASSWORD_AUTH)
                .clientId(clientId)
                .authParameters(authParameters)
                .build();

        InitiateAuthResponse response = cognitoClient.initiateAuth(authRequest);
        return response.authenticationResult();
    }

    /** Cierre global de sesión. */
    public void logout(String accessToken) {
        GlobalSignOutRequest request = GlobalSignOutRequest.builder()
                .accessToken(accessToken)
                .build();
        cognitoClient.globalSignOut(request);
    }

    /** Solicita el código de recuperación de contraseña. */
    public void solicitarRecuperacionContrasena(String email) {
        ForgotPasswordRequest.Builder builder = ForgotPasswordRequest.builder()
                .clientId(clientId)
                .username(email);

        if (clientSecret != null && !clientSecret.isBlank()) {
            builder.secretHash(generateSecretHash(email));
        }

        cognitoClient.forgotPassword(builder.build());
    }

    /** Confirma el cambio de contraseña mediante el código recibido. */
    public void confirmarRecuperacionContrasena(
            String email,
            String codigoOtp,
            String nuevaContrasena
    ) {
        ConfirmForgotPasswordRequest.Builder builder = ConfirmForgotPasswordRequest.builder()
                .clientId(clientId)
                .username(email)
                .confirmationCode(codigoOtp)
                .password(nuevaContrasena);

        if (clientSecret != null && !clientSecret.isBlank()) {
            builder.secretHash(generateSecretHash(email));
        }

        cognitoClient.confirmForgotPassword(builder.build());
    }

    private String normalizarEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    /** Valida grupos contra una lista cerrada y asigna ESTUDIANTE por defecto. */
    private Set<String> validarRoles(Set<String> rolesSolicitados) {
        if (rolesSolicitados == null || rolesSolicitados.isEmpty()) {
            Set<String> rolPorDefecto = new LinkedHashSet<>();
            rolPorDefecto.add(ROL_ESTUDIANTE);
            return rolPorDefecto;
        }

        Set<String> rolesValidados = new LinkedHashSet<>();
        for (String rol : rolesSolicitados) {
            if (rol == null || rol.isBlank()) {
                throw new BusinessException(
                        "VALIDATION_ERROR",
                        "Los roles no pueden contener valores vacíos"
                );
            }

            String rolNormalizado = rol.trim().toUpperCase(Locale.ROOT);
            if (!ROLES_PERMITIDOS.contains(rolNormalizado)) {
                throw new BusinessException(
                        "VALIDATION_ERROR",
                        "Rol no permitido: " + rol
                );
            }
            rolesValidados.add(rolNormalizado);
        }
        return rolesValidados;
    }

    /** Calcula SECRET_HASH para clientes Cognito con secreto. */
    private String generateSecretHash(String username) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            javax.crypto.spec.SecretKeySpec key = new javax.crypto.spec.SecretKeySpec(
                    clientSecret.getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256"
            );
            mac.init(key);
            byte[] hash = mac.doFinal((username + clientId).getBytes(StandardCharsets.UTF_8));
            return java.util.Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new IllegalStateException("Error generando SECRET_HASH", e);
        }
    }
}
