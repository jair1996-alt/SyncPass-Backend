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
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminDeleteUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.SignUpRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.SignUpResponse;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
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
        authService = new AuthService(cognitoClient, usuarioRepository);
        ReflectionTestUtils.setField(authService, "clientId", "test-client-id");
        ReflectionTestUtils.setField(authService, "userPoolId", "us-east-1_test");
        ReflectionTestUtils.setField(authService, "clientSecret", "");
    }

    @Test
    void rolesPermitidosSeEnvianComoGruposExactos() {
        when(usuarioRepository.existsByEmail("nuevo@duoc.cl")).thenReturn(false);
        when(cognitoClient.signUp(any(SignUpRequest.class)))
                .thenReturn(SignUpResponse.builder().userSub("sub-test").build());
        when(usuarioRepository.save(any(Usuario.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        UsuarioResponse response = authService.registrar(
                "Nuevo@Duoc.cl", "Password123!", "Nuevo Usuario",
                Set.of("profesor", "COORDINADOR")
        );

        ArgumentCaptor<AdminAddUserToGroupRequest> captor =
                ArgumentCaptor.forClass(AdminAddUserToGroupRequest.class);
        verify(cognitoClient, times(2)).adminAddUserToGroup(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(AdminAddUserToGroupRequest::groupName)
                .containsExactlyInAnyOrder("PROFESOR", "COORDINADOR");
        assertThat(response.getEmail()).isEqualTo("nuevo@duoc.cl");
        assertThat(response.getRoles())
                .containsExactlyInAnyOrder("PROFESOR", "COORDINADOR");
    }

    @Test
    void rolNoPermitidoNoLlegaACognito() {
        assertThatThrownBy(() -> authService.registrar(
                "nuevo@duoc.cl", "Password123!", "Nuevo Usuario", Set.of("SUPERADMIN")
        )).isInstanceOf(BusinessException.class)
                .hasMessageContaining("Rol no permitido");
        verifyNoInteractions(cognitoClient);
        verify(usuarioRepository, never()).save(any(Usuario.class));
    }

    @Test
    void sinRolesSeAplicaEstudiantePorDefecto() {
        when(usuarioRepository.existsByEmail("nuevo@duoc.cl")).thenReturn(false);
        when(cognitoClient.signUp(any(SignUpRequest.class)))
                .thenReturn(SignUpResponse.builder().userSub("sub-test").build());
        when(usuarioRepository.save(any(Usuario.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        UsuarioResponse response = authService.registrar(
                "nuevo@duoc.cl", "Password123!", "Nuevo Usuario", null
        );

        ArgumentCaptor<AdminAddUserToGroupRequest> captor =
                ArgumentCaptor.forClass(AdminAddUserToGroupRequest.class);
        verify(cognitoClient).adminAddUserToGroup(captor.capture());
        assertThat(captor.getValue().groupName()).isEqualTo("ESTUDIANTE");
        assertThat(response.getRoles()).containsExactly("ESTUDIANTE");
    }

    @Test
    void usuarioExistenteNoSeModificaNiSeEnviaACognito() {
        when(usuarioRepository.existsByEmail("nuevo@duoc.cl")).thenReturn(true);
        assertThatThrownBy(() -> authService.registrar(
                "nuevo@duoc.cl", "Password123!", "Nuevo Usuario", Set.of("ESTUDIANTE")
        )).isInstanceOf(BusinessException.class)
                .hasMessageContaining("registrado");
        verifyNoInteractions(cognitoClient);
        verify(usuarioRepository, never()).save(any(Usuario.class));
    }

    @Test
    void falloAlAsignarGrupoRevierteCuentaCognito() {
        when(usuarioRepository.existsByEmail("nuevo@duoc.cl")).thenReturn(false);
        when(cognitoClient.signUp(any(SignUpRequest.class)))
                .thenReturn(SignUpResponse.builder().userSub("sub-test").build());
        doThrow(new IllegalStateException("Fallo simulado Cognito"))
                .when(cognitoClient).adminAddUserToGroup(any(AdminAddUserToGroupRequest.class));

        assertThatThrownBy(() -> authService.registrar(
                "nuevo@duoc.cl", "Password123!", "Nuevo Usuario", Set.of("PROFESOR")
        )).isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getCode())
                        .isEqualTo("USER_CREATION_FAILED"));

        ArgumentCaptor<AdminDeleteUserRequest> captor =
                ArgumentCaptor.forClass(AdminDeleteUserRequest.class);
        verify(cognitoClient).adminDeleteUser(captor.capture());
        assertThat(captor.getValue().username()).isEqualTo("nuevo@duoc.cl");
        assertThat(captor.getValue().userPoolId()).isEqualTo("us-east-1_test");
        verify(usuarioRepository, never()).save(any(Usuario.class));
    }

    @Test
    void falloAlGuardarEnBaseDeDatosRevierteCuentaCognito() {
        when(usuarioRepository.existsByEmail("nuevo@duoc.cl")).thenReturn(false);
        when(cognitoClient.signUp(any(SignUpRequest.class)))
                .thenReturn(SignUpResponse.builder().userSub("sub-test").build());
        when(usuarioRepository.save(any(Usuario.class)))
                .thenThrow(new IllegalStateException("Fallo simulado PostgreSQL"));

        assertThatThrownBy(() -> authService.registrar(
                "nuevo@duoc.cl", "Password123!", "Nuevo Usuario", Set.of("ESTUDIANTE")
        )).isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getCode())
                        .isEqualTo("USER_CREATION_FAILED"));
        verify(cognitoClient).adminDeleteUser(any(AdminDeleteUserRequest.class));
    }

    @Test
    void falloDeCompensacionSolicitaRevisionAdministrativa() {
        when(usuarioRepository.existsByEmail("nuevo@duoc.cl")).thenReturn(false);
        when(cognitoClient.signUp(any(SignUpRequest.class)))
                .thenReturn(SignUpResponse.builder().userSub("sub-test").build());
        doThrow(new IllegalStateException("Fallo asignando grupo"))
                .when(cognitoClient).adminAddUserToGroup(any(AdminAddUserToGroupRequest.class));
        doThrow(new IllegalStateException("Fallo eliminando cuenta"))
                .when(cognitoClient).adminDeleteUser(any(AdminDeleteUserRequest.class));

        assertThatThrownBy(() -> authService.registrar(
                "nuevo@duoc.cl", "Password123!", "Nuevo Usuario", Set.of("PROFESOR")
        )).isInstanceOf(BusinessException.class)
                .satisfies(error -> {
                    BusinessException exception = (BusinessException) error;
                    assertThat(exception.getCode())
                            .isEqualTo("USER_CREATION_REQUIRES_RECONCILIATION");
                    assertThat(exception.getCause()).isNotNull();
                    assertThat(exception.getCause().getSuppressed()).hasSize(1);
                });
    }

    @Test
    void falloDeSignUpNoEliminaCuentas() {
        when(usuarioRepository.existsByEmail("nuevo@duoc.cl")).thenReturn(false);
        when(cognitoClient.signUp(any(SignUpRequest.class)))
                .thenThrow(new IllegalStateException("Fallo simulado signUp"));

        assertThatThrownBy(() -> authService.registrar(
                "nuevo@duoc.cl", "Password123!", "Nuevo Usuario", Set.of("ESTUDIANTE")
        )).isInstanceOf(IllegalStateException.class);
        verify(cognitoClient, never()).adminDeleteUser(any(AdminDeleteUserRequest.class));
    }
}
