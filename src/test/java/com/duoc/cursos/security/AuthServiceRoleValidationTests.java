
package com.duoc.cursos.security;

import com.duoc.cursos.auth.dto.UsuarioResponse;
import com.duoc.cursos.auth.model.Usuario;
import com.duoc.cursos.auth.repository.UsuarioRepository;
import com.duoc.cursos.auth.service.AuthService;
import com.duoc.cursos.common.exception.BusinessException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.test.util.ReflectionTestUtils;

import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminAddUserToGroupRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminConfirmSignUpRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminDeleteUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminUpdateUserAttributesRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.SignUpRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.SignUpResponse;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceRoleValidationTests {

    @Mock
    private CognitoIdentityProviderClient cognitoClient;

    @Mock
    private UsuarioRepository usuarioRepository;

    private AuthService authService;

    @BeforeEach
    void setUp() {

        authService = new AuthService(
                cognitoClient,
                usuarioRepository
        );

        ReflectionTestUtils.setField(
                authService,
                "clientId",
                "test-client-id"
        );

        ReflectionTestUtils.setField(
                authService,
                "userPoolId",
                "us-east-1_test"
        );

        ReflectionTestUtils.setField(
                authService,
                "clientSecret",
                ""
        );

        /*
         * Valores de prueba.
         *
         * Solo "administrador" está confirmado
         * en el entorno AWS del proyecto.
         *
         * Los otros tres nombres son ejemplos
         * utilizados exclusivamente por estas
         * pruebas unitarias con mocks.
         */
        ReflectionTestUtils.setField(
                authService,
                "grupoEstudiante",
                "estudiante-test"
        );

        ReflectionTestUtils.setField(
                authService,
                "grupoProfesor",
                "profesor-test"
        );

        ReflectionTestUtils.setField(
                authService,
                "grupoCoordinador",
                "coordinador-test"
        );

        ReflectionTestUtils.setField(
                authService,
                "grupoAdministrador",
                "administrador"
        );
    }

    @Test
    void rolesPermitidosSeTraducenAGruposConfigurados() {

        when(usuarioRepository.existsByEmail("nuevo@duoc.cl"))
                .thenReturn(false);

        when(cognitoClient.signUp(any(SignUpRequest.class)))
                .thenReturn(
                        SignUpResponse.builder()
                                .userSub("sub-test")
                                .build()
                );

        when(usuarioRepository.save(any(Usuario.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        UsuarioResponse response = authService.registrar(
                "Nuevo@Duoc.cl",
                "Password123!",
                "Nuevo Usuario",
                "+56912345678",
                Set.of("profesor", "COORDINADOR")
        );

        ArgumentCaptor<SignUpRequest> signUpCaptor =
                ArgumentCaptor.forClass(SignUpRequest.class);
        verify(cognitoClient).signUp(signUpCaptor.capture());
        assertThat(signUpCaptor.getValue().userAttributes())
                .anySatisfy(attribute -> {
                    assertThat(attribute.name()).isEqualTo("phone_number");
                    assertThat(attribute.value()).isEqualTo("+56912345678");
                });

        ArgumentCaptor<AdminAddUserToGroupRequest> captor =
                ArgumentCaptor.forClass(
                        AdminAddUserToGroupRequest.class
                );

        verify(cognitoClient, times(2))
                .adminAddUserToGroup(captor.capture());

        assertThat(captor.getAllValues())
                .extracting(AdminAddUserToGroupRequest::groupName)
                .containsExactlyInAnyOrder(
                        "profesor-test",
                        "coordinador-test"
                );

        assertThat(captor.getAllValues())
                .extracting(AdminAddUserToGroupRequest::username)
                .containsOnly("sub-test");

        assertThat(response.getEmail())
                .isEqualTo("nuevo@duoc.cl");

        assertThat(response.getRoles())
                .containsExactlyInAnyOrder(
                        "PROFESOR",
                        "COORDINADOR"
                );
    }

    @Test
    void administradorSeAsignaAlGrupoRealConfigurado() {

        when(usuarioRepository.existsByEmail("nuevo@duoc.cl"))
                .thenReturn(false);

        when(cognitoClient.signUp(any(SignUpRequest.class)))
                .thenReturn(
                        SignUpResponse.builder()
                                .userSub("sub-test")
                                .build()
                );

        when(usuarioRepository.save(any(Usuario.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        UsuarioResponse response = authService.registrar(
                "nuevo@duoc.cl",
                "Password123!",
                "Nuevo Administrador",
                "+56912345678",
                Set.of("ADMINISTRADOR")
        );

        ArgumentCaptor<AdminAddUserToGroupRequest> captor =
                ArgumentCaptor.forClass(
                        AdminAddUserToGroupRequest.class
                );

        verify(cognitoClient)
                .adminAddUserToGroup(captor.capture());

        assertThat(captor.getValue().groupName())
                .isEqualTo("administrador");

        assertThat(captor.getValue().username())
                .isEqualTo("sub-test");

        assertThat(response.getRoles())
                .containsExactly("ADMINISTRADOR");
    }

    @Test
    void registroExitosoConfirmaLaCuentaAntesDeAsignarGruposYGuardar() {

        when(usuarioRepository.existsByEmail("nuevo@duoc.cl"))
                .thenReturn(false);

        when(cognitoClient.signUp(any(SignUpRequest.class)))
                .thenReturn(
                        SignUpResponse.builder()
                                .userSub("sub-test")
                                .build()
                );

        when(usuarioRepository.save(any(Usuario.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        authService.registrar(
                "nuevo@duoc.cl",
                "Password123!",
                "Nuevo Usuario",
                "+56912345678",
                Set.of("ESTUDIANTE")
        );

        ArgumentCaptor<AdminConfirmSignUpRequest> captor =
                ArgumentCaptor.forClass(
                        AdminConfirmSignUpRequest.class
                );

        verify(cognitoClient)
                .adminConfirmSignUp(captor.capture());

        assertThat(captor.getValue().userPoolId())
                .isEqualTo("us-east-1_test");

        assertThat(captor.getValue().username())
                .isEqualTo("sub-test");

        /*
         * La confirmación debe ocurrir después de SignUp y antes de
         * asignar grupos o persistir en PostgreSQL.
         */
        var ordered = inOrder(cognitoClient, usuarioRepository);

        ordered.verify(cognitoClient).signUp(any(SignUpRequest.class));
        ordered.verify(cognitoClient)
                .adminConfirmSignUp(any(AdminConfirmSignUpRequest.class));
        ordered.verify(cognitoClient)
                .adminAddUserToGroup(any(AdminAddUserToGroupRequest.class));
        ordered.verify(usuarioRepository).save(any(Usuario.class));
    }

    @Test
    void cuentaYaConfirmadaPorCognitoNoSeVuelveAConfirmar() {

        when(usuarioRepository.existsByEmail("nuevo@duoc.cl"))
                .thenReturn(false);

        when(cognitoClient.signUp(any(SignUpRequest.class)))
                .thenReturn(
                        SignUpResponse.builder()
                                .userSub("sub-test")
                                .userConfirmed(true)
                                .build()
                );

        when(usuarioRepository.save(any(Usuario.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        UsuarioResponse response = authService.registrar(
                "nuevo@duoc.cl",
                "Password123!",
                "Nuevo Usuario",
                "+56912345678",
                Set.of("ESTUDIANTE")
        );

        /*
         * Una cuenta que Cognito ya confirmó (por ejemplo, mediante
         * un trigger pre sign-up) no debe recibir AdminConfirmSignUp:
         * la API fallaría porque ya está en estado CONFIRMED.
         */
        verify(cognitoClient, never())
                .adminConfirmSignUp(
                        any(AdminConfirmSignUpRequest.class)
                );

        verify(usuarioRepository).save(any(Usuario.class));

        assertThat(response.getRoles())
                .containsExactly("ESTUDIANTE");
    }

    @Test
    void confirmacionNoMarcaEmailNiTelefonoComoVerificados() {

        when(usuarioRepository.existsByEmail("nuevo@duoc.cl"))
                .thenReturn(false);

        when(cognitoClient.signUp(any(SignUpRequest.class)))
                .thenReturn(
                        SignUpResponse.builder()
                                .userSub("sub-test")
                                .build()
                );

        when(usuarioRepository.save(any(Usuario.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        authService.registrar(
                "nuevo@duoc.cl",
                "Password123!",
                "Nuevo Usuario",
                "+56912345678",
                Set.of("ESTUDIANTE")
        );

        ArgumentCaptor<SignUpRequest> signUpCaptor =
                ArgumentCaptor.forClass(SignUpRequest.class);

        verify(cognitoClient).signUp(signUpCaptor.capture());

        assertThat(signUpCaptor.getValue().userAttributes())
                .extracting(attribute -> attribute.name())
                .doesNotContain(
                        "email_verified",
                        "phone_number_verified"
                );

        /*
         * AdminConfirmSignUp solo cambia el estado de la cuenta.
         * No se actualizan atributos de verificación.
         */
        verify(cognitoClient, never())
                .adminUpdateUserAttributes(
                        any(AdminUpdateUserAttributesRequest.class)
                );
    }

    @Test
    void falloAlConfirmarCuentaRevierteCuentaCognito() {

        when(usuarioRepository.existsByEmail("nuevo@duoc.cl"))
                .thenReturn(false);

        when(cognitoClient.signUp(any(SignUpRequest.class)))
                .thenReturn(
                        SignUpResponse.builder()
                                .userSub("sub-test")
                                .build()
                );

        doThrow(new IllegalStateException(
                "Fallo simulado AdminConfirmSignUp"
        ))
                .when(cognitoClient)
                .adminConfirmSignUp(
                        any(AdminConfirmSignUpRequest.class)
                );

        assertThatThrownBy(() -> authService.registrar(
                "nuevo@duoc.cl",
                "Password123!",
                "Nuevo Usuario",
                "+56912345678",
                Set.of("ESTUDIANTE")
        ))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> {
                    BusinessException exception =
                            (BusinessException) error;

                    assertThat(exception.getCode())
                            .isEqualTo("USER_CREATION_FAILED");
                });

        ArgumentCaptor<AdminDeleteUserRequest> captor =
                ArgumentCaptor.forClass(
                        AdminDeleteUserRequest.class
                );

        verify(cognitoClient)
                .adminDeleteUser(captor.capture());

        assertThat(captor.getValue().username())
                .isEqualTo("sub-test");

        assertThat(captor.getValue().userPoolId())
                .isEqualTo("us-east-1_test");

        verify(cognitoClient, never())
                .adminAddUserToGroup(
                        any(AdminAddUserToGroupRequest.class)
                );

        verify(usuarioRepository, never())
                .save(any(Usuario.class));
    }

    @Test
    void falloDeConfirmacionYCompensacionSolicitaRevisionAdministrativa() {

        when(usuarioRepository.existsByEmail("nuevo@duoc.cl"))
                .thenReturn(false);

        when(cognitoClient.signUp(any(SignUpRequest.class)))
                .thenReturn(
                        SignUpResponse.builder()
                                .userSub("sub-test")
                                .build()
                );

        doThrow(new IllegalStateException(
                "Fallo confirmando cuenta"
        ))
                .when(cognitoClient)
                .adminConfirmSignUp(
                        any(AdminConfirmSignUpRequest.class)
                );

        doThrow(new IllegalStateException(
                "Fallo eliminando cuenta"
        ))
                .when(cognitoClient)
                .adminDeleteUser(
                        any(AdminDeleteUserRequest.class)
                );

        assertThatThrownBy(() -> authService.registrar(
                "nuevo@duoc.cl",
                "Password123!",
                "Nuevo Usuario",
                "+56912345678",
                Set.of("ESTUDIANTE")
        ))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> {
                    BusinessException exception =
                            (BusinessException) error;

                    assertThat(exception.getCode())
                            .isEqualTo(
                                    "USER_CREATION_REQUIRES_RECONCILIATION"
                            );

                    assertThat(exception.getCause())
                            .isNotNull();

                    assertThat(
                            exception.getCause().getSuppressed()
                    ).hasSize(1);
                });

        verify(usuarioRepository, never())
                .save(any(Usuario.class));
    }

    @Test
    void rolNoPermitidoNoLlegaACognito() {

        assertThatThrownBy(() -> authService.registrar(
                "nuevo@duoc.cl",
                "Password123!",
                "Nuevo Usuario",
                "+56912345678",
                Set.of("SUPERADMIN")
        ))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Rol no permitido");

        verifyNoInteractions(cognitoClient);

        verify(usuarioRepository, never())
                .save(any(Usuario.class));
    }

    @Test
    void sinRolesSeAplicaEstudiantePorDefecto() {

        when(usuarioRepository.existsByEmail("nuevo@duoc.cl"))
                .thenReturn(false);

        when(cognitoClient.signUp(any(SignUpRequest.class)))
                .thenReturn(
                        SignUpResponse.builder()
                                .userSub("sub-test")
                                .build()
                );

        when(usuarioRepository.save(any(Usuario.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        UsuarioResponse response = authService.registrar(
                "nuevo@duoc.cl",
                "Password123!",
                "Nuevo Usuario",
                "+56912345678",
                null
        );

        ArgumentCaptor<AdminAddUserToGroupRequest> captor =
                ArgumentCaptor.forClass(
                        AdminAddUserToGroupRequest.class
                );

        verify(cognitoClient)
                .adminAddUserToGroup(captor.capture());

        assertThat(captor.getValue().groupName())
                .isEqualTo("estudiante-test");

        assertThat(captor.getValue().username())
                .isEqualTo("sub-test");

        assertThat(response.getRoles())
                .containsExactly("ESTUDIANTE");
    }

    @Test
    void usuarioExistenteNoSeModificaNiSeEnviaACognito() {

        when(usuarioRepository.existsByEmail("nuevo@duoc.cl"))
                .thenReturn(true);

        assertThatThrownBy(() -> authService.registrar(
                "nuevo@duoc.cl",
                "Password123!",
                "Nuevo Usuario",
                "+56912345678",
                Set.of("ESTUDIANTE")
        ))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("registrado");

        verifyNoInteractions(cognitoClient);

        verify(usuarioRepository, never())
                .save(any(Usuario.class));
    }

    @Test
    void grupoNoConfiguradoImpideCrearCuenta() {

        ReflectionTestUtils.setField(
                authService,
                "grupoProfesor",
                ""
        );

        assertThatThrownBy(() -> authService.registrar(
                "nuevo@duoc.cl",
                "Password123!",
                "Nuevo Profesor",
                "+56912345678",
                Set.of("PROFESOR")
        ))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> {
                    BusinessException exception =
                            (BusinessException) error;

                    assertThat(exception.getCode())
                            .isEqualTo("COGNITO_GROUP_NOT_CONFIGURED");
                });

        verifyNoInteractions(cognitoClient);

        verify(usuarioRepository, never())
                .save(any(Usuario.class));
    }

    @Test
    void falloAlAsignarGrupoRevierteCuentaCognito() {

        when(usuarioRepository.existsByEmail("nuevo@duoc.cl"))
                .thenReturn(false);

        when(cognitoClient.signUp(any(SignUpRequest.class)))
                .thenReturn(
                        SignUpResponse.builder()
                                .userSub("sub-test")
                                .build()
                );

        doThrow(new IllegalStateException(
                "Fallo simulado Cognito"
        ))
                .when(cognitoClient)
                .adminAddUserToGroup(
                        any(AdminAddUserToGroupRequest.class)
                );

        assertThatThrownBy(() -> authService.registrar(
                "nuevo@duoc.cl",
                "Password123!",
                "Nuevo Usuario",
                "+56912345678",
                Set.of("PROFESOR")
        ))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> {
                    BusinessException exception =
                            (BusinessException) error;

                    assertThat(exception.getCode())
                            .isEqualTo("USER_CREATION_FAILED");
                });

        ArgumentCaptor<AdminDeleteUserRequest> captor =
                ArgumentCaptor.forClass(
                        AdminDeleteUserRequest.class
                );

        verify(cognitoClient)
                .adminDeleteUser(captor.capture());

        assertThat(captor.getValue().username())
                .isEqualTo("sub-test");

        assertThat(captor.getValue().userPoolId())
                .isEqualTo("us-east-1_test");

        verify(usuarioRepository, never())
                .save(any(Usuario.class));
    }

    @Test
    void falloAlGuardarEnBaseDeDatosRevierteCuentaCognito() {

        when(usuarioRepository.existsByEmail("nuevo@duoc.cl"))
                .thenReturn(false);

        when(cognitoClient.signUp(any(SignUpRequest.class)))
                .thenReturn(
                        SignUpResponse.builder()
                                .userSub("sub-test")
                                .build()
                );

        when(usuarioRepository.save(any(Usuario.class)))
                .thenThrow(
                        new IllegalStateException(
                                "Fallo simulado PostgreSQL"
                        )
                );

        assertThatThrownBy(() -> authService.registrar(
                "nuevo@duoc.cl",
                "Password123!",
                "Nuevo Usuario",
                "+56912345678",
                Set.of("ESTUDIANTE")
        ))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> {
                    BusinessException exception =
                            (BusinessException) error;

                    assertThat(exception.getCode())
                            .isEqualTo("USER_CREATION_FAILED");
                });

        ArgumentCaptor<AdminDeleteUserRequest> captor =
                ArgumentCaptor.forClass(
                        AdminDeleteUserRequest.class
                );

        verify(cognitoClient)
                .adminDeleteUser(captor.capture());

        assertThat(captor.getValue().username())
                .isEqualTo("sub-test");
    }

    @Test
    void falloDeCompensacionSolicitaRevisionAdministrativa() {

        when(usuarioRepository.existsByEmail("nuevo@duoc.cl"))
                .thenReturn(false);

        when(cognitoClient.signUp(any(SignUpRequest.class)))
                .thenReturn(
                        SignUpResponse.builder()
                                .userSub("sub-test")
                                .build()
                );

        doThrow(new IllegalStateException(
                "Fallo asignando grupo"
        ))
                .when(cognitoClient)
                .adminAddUserToGroup(
                        any(AdminAddUserToGroupRequest.class)
                );

        doThrow(new IllegalStateException(
                "Fallo eliminando cuenta"
        ))
                .when(cognitoClient)
                .adminDeleteUser(
                        any(AdminDeleteUserRequest.class)
                );

        assertThatThrownBy(() -> authService.registrar(
                "nuevo@duoc.cl",
                "Password123!",
                "Nuevo Usuario",
                "+56912345678",
                Set.of("PROFESOR")
        ))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> {
                    BusinessException exception =
                            (BusinessException) error;

                    assertThat(exception.getCode())
                            .isEqualTo(
                                    "USER_CREATION_REQUIRES_RECONCILIATION"
                            );

                    assertThat(exception.getCause())
                            .isNotNull();

                    assertThat(
                            exception.getCause().getSuppressed()
                    ).hasSize(1);
                });
    }

    @Test
    void falloDeSignUpNoEliminaCuentas() {

        when(usuarioRepository.existsByEmail("nuevo@duoc.cl"))
                .thenReturn(false);

        when(cognitoClient.signUp(any(SignUpRequest.class)))
                .thenThrow(
                        new IllegalStateException(
                                "Fallo simulado signUp"
                        )
                );

        assertThatThrownBy(() -> authService.registrar(
                "nuevo@duoc.cl",
                "Password123!",
                "Nuevo Usuario",
                "+56912345678",
                Set.of("ESTUDIANTE")
        ))
                .isInstanceOf(IllegalStateException.class);

        verify(cognitoClient, never())
                .adminDeleteUser(
                        any(AdminDeleteUserRequest.class)
                );
    }
}
