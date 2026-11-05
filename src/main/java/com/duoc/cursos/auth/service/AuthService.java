package com.duoc.cursos.auth.service;

import com.duoc.cursos.auth.dto.UsuarioResponse;
import com.duoc.cursos.auth.model.Usuario;
import com.duoc.cursos.auth.repository.UsuarioRepository;
import com.duoc.cursos.common.exception.BusinessException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.*;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class AuthService {

    private static final Logger log =
            LoggerFactory.getLogger(AuthService.class);

    private static final String ROL_ESTUDIANTE = "ESTUDIANTE";
    private static final String ROL_PROFESOR = "PROFESOR";
    private static final String ROL_COORDINADOR = "COORDINADOR";
    private static final String ROL_ADMINISTRADOR = "ADMINISTRADOR";

    private static final Set<String> ROLES_PERMITIDOS = Set.of(
            ROL_ESTUDIANTE,
            ROL_PROFESOR,
            ROL_COORDINADOR,
            ROL_ADMINISTRADOR
    );

    private final CognitoIdentityProviderClient cognitoClient;
    private final UsuarioRepository usuarioRepository;

    @Value("${aws.cognito.client-id}")
    private String clientId;

    @Value("${aws.cognito.user-pool-id}")
    private String userPoolId;

    @Value("${aws.cognito.client-secret:}")
    private String clientSecret;

    /*
     * Nombres reales de los grupos en AWS Cognito.
     *
     * Solo se ha confirmado el nombre "administrador".
     * Los otros tres deben configurarse explícitamente
     * después de comprobarlos en AWS.
     */
    @Value("${aws.cognito.groups.estudiante:}")
    private String grupoEstudiante;

    @Value("${aws.cognito.groups.profesor:}")
    private String grupoProfesor;

    @Value("${aws.cognito.groups.coordinador:}")
    private String grupoCoordinador;

    @Value("${aws.cognito.groups.administrador:administrador}")
    private String grupoAdministrador;

    public AuthService(
            CognitoIdentityProviderClient cognitoClient,
            UsuarioRepository usuarioRepository
    ) {
        this.cognitoClient = cognitoClient;
        this.usuarioRepository = usuarioRepository;
    }

    /**
     * Registro administrativo.
     *
     * El endpoint debe estar protegido mediante
     * ROLE_ADMINISTRADOR en Spring Security.
     *
     * Conserva el flujo SignUp existente y lo completa con
     * AdminConfirmSignUp para que la cuenta quede en estado
     * CONFIRMED y el usuario pueda iniciar sesión con la
     * contraseña inicial definida por el administrador.
     */
    public UsuarioResponse registrar(
            String email,
            String password,
            String nombre,
            String phoneNumber,
            Set<String> rolesSolicitados
    ) {

        String emailNormalizado = normalizarEmail(email);
        String telefonoNormalizado = phoneNumber.trim();

        Set<String> roles = validarRoles(rolesSolicitados);

        /*
         * Validar todos los nombres de grupo ANTES
         * de crear una cuenta en Cognito.
         */
        Map<String, String> gruposCognito =
                resolverGruposCognito(roles);

        if (usuarioRepository.existsByEmail(emailNormalizado)) {
            throw new BusinessException(
                    "USER_ALREADY_EXISTS",
                    "El email ya está registrado"
            );
        }

        SignUpRequest.Builder signUpBuilder =
                SignUpRequest.builder()
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
                                        .build(),

                                AttributeType.builder()
                                        .name("phone_number")
                                        .value(telefonoNormalizado)
                                        .build()
                        );

        if (clientSecret != null && !clientSecret.isBlank()) {
            signUpBuilder.secretHash(
                    generateSecretHash(emailNormalizado)
            );
        }

        /*
         * Si Cognito falla antes de confirmar el alta,
         * NO se elimina automáticamente ningún usuario.
         */
        SignUpResponse signUpResponse =
                cognitoClient.signUp(signUpBuilder.build());

        String cognitoSub = signUpResponse.userSub();

        if (cognitoSub == null || cognitoSub.isBlank()) {
            throw new BusinessException(
                    "COGNITO_INVALID_RESPONSE",
                    "Cognito no confirmó el identificador de la cuenta. " +
                            "Se requiere revisión administrativa."
            );
        }

        try {

            /*
             * Confirmar administrativamente la cuenta recién creada.
             *
             * SignUp crea la cuenta en estado UNCONFIRMED. Sin esta
             * llamada el usuario no puede iniciar sesión hasta que un
             * administrador lo confirme manualmente desde AWS Console.
             *
             * AdminConfirmSignUp cambia únicamente el estado de la
             * cuenta a CONFIRMED. NO verifica el correo ni el teléfono:
             * no se envían ni se establecen email_verified ni
             * phone_number_verified.
             *
             * Se utiliza el sub devuelto por SignUp, que identifica de
             * forma inequívoca la cuenta recién creada y evita operar
             * sobre otra cuenta con el mismo email.
             *
             * Si el User Pool ya auto-confirmó la cuenta, no se repite
             * la operación: AdminConfirmSignUp fallaría sobre una
             * cuenta que ya está en estado CONFIRMED.
             */
            if (Boolean.TRUE.equals(signUpResponse.userConfirmed())) {

                log.debug(
                        "La cuenta ya fue confirmada por Cognito. sub={}",
                        cognitoSub
                );

            } else {

                confirmarCuentaEnCognito(cognitoSub);
            }

            /*
             * Asociar el usuario recién creado
             * a los grupos reales de Cognito.
             */
            for (String grupoCognito : gruposCognito.values()) {

                AdminAddUserToGroupRequest request =
                        AdminAddUserToGroupRequest.builder()
                                .userPoolId(userPoolId)
                                .username(cognitoSub)
                                .groupName(grupoCognito)
                                .build();

                cognitoClient.adminAddUserToGroup(request);
            }

            /*
             * Guardar la cuenta en PostgreSQL.
             *
             * Los roles locales permanecen normalizados
             * a mayúsculas, independientemente
             * de los nombres utilizados en Cognito.
             */
            Usuario usuarioLocal = new Usuario();

            usuarioLocal.setId(cognitoSub);
            usuarioLocal.setEmail(emailNormalizado);
            usuarioLocal.setNombre(nombre);
            usuarioLocal.setRoles(roles);

            Usuario saved =
                    usuarioRepository.save(usuarioLocal);

            return UsuarioResponse.builder()
                    .id(saved.getId())
                    .email(saved.getEmail())
                    .nombre(saved.getNombre())
                    .roles(saved.getRoles())
                    .build();

        } catch (RuntimeException original) {

            /*
             * Compensación de una creación parcial.
             *
             * Puede fallar la confirmación administrativa, la
             * asignación de grupos o la persistencia en PostgreSQL.
             *
             * Utilizamos el sub devuelto por SignUp,
             * no el correo de otra cuenta.
             *
             * Si falla la compensación, se informa
             * que se requiere conciliación administrativa.
             */
            log.error(
                    "Registro fallido; se intentará compensar en Cognito. sub={}",
                    cognitoSub,
                    original
            );

            try {

                cognitoClient.adminDeleteUser(
                        AdminDeleteUserRequest.builder()
                                .userPoolId(userPoolId)
                                .username(cognitoSub)
                                .build()
                );

            } catch (RuntimeException compensacionFallida) {

                original.addSuppressed(compensacionFallida);

                log.error(
                        "No se pudo compensar la cuenta en Cognito; " +
                                "se requiere conciliación administrativa. sub={}",
                        cognitoSub,
                        compensacionFallida
                );

                throw new BusinessException(
                        "USER_CREATION_REQUIRES_RECONCILIATION",
                        "La creación falló y no se pudo revertir " +
                                "en Cognito. Se requiere revisión administrativa.",
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

    /**
     * Confirma administrativamente una cuenta recién creada.
     *
     * La operación requiere credenciales IAM con el permiso
     * cognito-idp:AdminConfirmSignUp y no utiliza SECRET_HASH,
     * porque es una operación de la API administrativa.
     *
     * No modifica atributos de verificación del usuario.
     */
    private void confirmarCuentaEnCognito(String cognitoSub) {

        AdminConfirmSignUpRequest request =
                AdminConfirmSignUpRequest.builder()
                        .userPoolId(userPoolId)
                        .username(cognitoSub)
                        .build();

        cognitoClient.adminConfirmSignUp(request);
    }

    /**
     * Traduce los roles internos de SyncPass
     * a los nombres reales de AWS Cognito.
     *
     * Si falta configurar un grupo, impide
     * continuar con el registro.
     */
    private Map<String, String> resolverGruposCognito(
            Set<String> roles
    ) {

        Map<String, String> resultado =
                new LinkedHashMap<>();

        for (String rol : roles) {

            String nombreGrupo = switch (rol) {

                case ROL_ESTUDIANTE -> grupoEstudiante;

                case ROL_PROFESOR -> grupoProfesor;

                case ROL_COORDINADOR -> grupoCoordinador;

                case ROL_ADMINISTRADOR -> grupoAdministrador;

                default -> throw new BusinessException(
                        "VALIDATION_ERROR",
                        "Rol no permitido: " + rol
                );
            };

            if (nombreGrupo == null || nombreGrupo.isBlank()) {
                throw new BusinessException(
                        "COGNITO_GROUP_NOT_CONFIGURED",
                        "No está configurado el grupo Cognito " +
                                "correspondiente al rol " + rol
                );
            }

            resultado.put(rol, nombreGrupo.trim());
        }

        return resultado;
    }

    /**
     * Inicio de sesión mediante USER_PASSWORD_AUTH.
     *
     * Se conserva el comportamiento existente.
     */
    public AuthenticationResultType login(
            String email,
            String password
    ) {

        Map<String, String> authParameters =
                new HashMap<>();

        authParameters.put("USERNAME", email);
        authParameters.put("PASSWORD", password);

        if (clientSecret != null && !clientSecret.isBlank()) {
            authParameters.put(
                    "SECRET_HASH",
                    generateSecretHash(email)
            );
        }

        InitiateAuthRequest authRequest =
                InitiateAuthRequest.builder()
                        .authFlow(AuthFlowType.USER_PASSWORD_AUTH)
                        .clientId(clientId)
                        .authParameters(authParameters)
                        .build();

        InitiateAuthResponse response =
                cognitoClient.initiateAuth(authRequest);

        return response.authenticationResult();
    }

    /**
     * Cierre global de sesión mediante Cognito.
     */
    public void logout(String accessToken) {

        GlobalSignOutRequest request =
                GlobalSignOutRequest.builder()
                        .accessToken(accessToken)
                        .build();

        cognitoClient.globalSignOut(request);
    }

    /**
     * Solicitar recuperación de contraseña.
     */
    public void solicitarRecuperacionContrasena(
            String email
    ) {

        ForgotPasswordRequest.Builder builder =
                ForgotPasswordRequest.builder()
                        .clientId(clientId)
                        .username(email);

        if (clientSecret != null && !clientSecret.isBlank()) {
            builder.secretHash(
                    generateSecretHash(email)
            );
        }

        cognitoClient.forgotPassword(builder.build());
    }

    /**
     * Confirmar recuperación de contraseña.
     */
    public void confirmarRecuperacionContrasena(
            String email,
            String codigoOtp,
            String nuevaContrasena
    ) {

        ConfirmForgotPasswordRequest.Builder builder =
                ConfirmForgotPasswordRequest.builder()
                        .clientId(clientId)
                        .username(email)
                        .confirmationCode(codigoOtp)
                        .password(nuevaContrasena);

        if (clientSecret != null && !clientSecret.isBlank()) {
            builder.secretHash(
                    generateSecretHash(email)
            );
        }

        cognitoClient.confirmForgotPassword(builder.build());
    }

    /**
     * Normalización del correo.
     */
    private String normalizarEmail(String email) {

        return email.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Validar y normalizar roles internos.
     *
     * Conserva ESTUDIANTE como rol por defecto
     * cuando la solicitud no especifica roles.
     */
    private Set<String> validarRoles(
            Set<String> rolesSolicitados
    ) {

        if (rolesSolicitados == null ||
                rolesSolicitados.isEmpty()) {

            Set<String> rolPorDefecto =
                    new LinkedHashSet<>();

            rolPorDefecto.add(ROL_ESTUDIANTE);

            return rolPorDefecto;
        }

        Set<String> rolesValidados =
                new LinkedHashSet<>();

        for (String rol : rolesSolicitados) {

            if (rol == null || rol.isBlank()) {
                throw new BusinessException(
                        "VALIDATION_ERROR",
                        "Los roles no pueden contener valores vacíos"
                );
            }

            String rolNormalizado =
                    rol.trim().toUpperCase(Locale.ROOT);

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

    /**
     * Generar SECRET_HASH para clientes Cognito
     * que utilizan client secret.
     */
    private String generateSecretHash(String username) {

        try {

            javax.crypto.Mac mac =
                    javax.crypto.Mac.getInstance("HmacSHA256");

            javax.crypto.spec.SecretKeySpec key =
                    new javax.crypto.spec.SecretKeySpec(
                            clientSecret.getBytes(
                                    StandardCharsets.UTF_8
                            ),
                            "HmacSHA256"
                    );

            mac.init(key);

            byte[] hash = mac.doFinal(
                    (username + clientId).getBytes(
                            StandardCharsets.UTF_8
                    )
            );

            return java.util.Base64.getEncoder()
                    .encodeToString(hash);

        } catch (Exception e) {

            throw new IllegalStateException(
                    "Error generando SECRET_HASH",
                    e
            );
        }
    }
}
