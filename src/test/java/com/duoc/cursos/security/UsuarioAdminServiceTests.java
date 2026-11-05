package com.duoc.cursos.security;

import com.duoc.cursos.auth.dto.EliminacionUsuarioResponse;
import com.duoc.cursos.auth.model.Usuario;
import com.duoc.cursos.auth.repository.UsuarioRepository;
import com.duoc.cursos.auth.service.UsuarioAdminService;
import com.duoc.cursos.auth.service.UsuarioDependencyChecker;
import com.duoc.cursos.auth.service.UsuarioLocalTransactionService;
import com.duoc.cursos.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminDeleteUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.CognitoIdentityProviderException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ForbiddenException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.TooManyRequestsException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserNotFoundException;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UsuarioAdminServiceTests {

    private static final String USER_POOL_ID = "us-east-1_test";

    @Mock
    private CognitoIdentityProviderClient cognitoClient;

    @Mock
    private UsuarioRepository usuarioRepository;

    @Mock
    private UsuarioDependencyChecker dependencyChecker;

    @Mock
    private UsuarioLocalTransactionService localTransactionService;

    private UsuarioAdminService usuarioAdminService;

    @BeforeEach
    void setUp() {

        usuarioAdminService = new UsuarioAdminService(
                cognitoClient,
                usuarioRepository,
                dependencyChecker,
                localTransactionService
        );

        ReflectionTestUtils.setField(
                usuarioAdminService,
                "userPoolId",
                USER_POOL_ID
        );
    }

    @Test
    void administradorEliminaCorrectamenteUnUsuario() {

        Usuario usuario = usuario(
                "sub-objetivo",
                "objetivo@duoc.cl",
                "ESTUDIANTE",
                "PROFESOR"
        );

        when(usuarioRepository.findById("sub-objetivo"))
                .thenReturn(Optional.of(usuario));

        when(dependencyChecker.comprobar(usuario))
                .thenReturn(UsuarioDependencyChecker.Dependencias.SIN_DEPENDENCIAS);

        when(localTransactionService.eliminarUsuarioLocal("sub-objetivo"))
                .thenReturn(true);

        when(usuarioRepository.existsById("sub-objetivo"))
                .thenReturn(false);

        EliminacionUsuarioResponse response =
                usuarioAdminService.eliminarUsuario(
                        "sub-objetivo",
                        "sub-admin"
                );

        assertThat(response.usuarioId()).isEqualTo("sub-objetivo");
        assertThat(response.estado()).isEqualTo("ELIMINADO");
        assertThat(response.cognitoEliminado()).isTrue();
        assertThat(response.postgresEliminado()).isTrue();

        ArgumentCaptor<AdminDeleteUserRequest> captor =
                ArgumentCaptor.forClass(AdminDeleteUserRequest.class);

        verify(cognitoClient).adminDeleteUser(captor.capture());

        assertThat(captor.getValue().userPoolId())
                .isEqualTo(USER_POOL_ID);

        /*
         * Se elimina por sub, nunca por email.
         */
        assertThat(captor.getValue().username())
                .isEqualTo("sub-objetivo");

        verify(localTransactionService)
                .eliminarUsuarioLocal("sub-objetivo");
    }

    @Test
    void unAdministradorNoPuedeEliminarSuPropiaCuenta() {

        assertThatThrownBy(() -> usuarioAdminService.eliminarUsuario(
                "sub-admin",
                "sub-admin"
        ))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(
                        ((BusinessException) error).getCode()
                ).isEqualTo("CANNOT_DELETE_SELF"));

        verifyNoInteractions(
                cognitoClient,
                usuarioRepository,
                dependencyChecker,
                localTransactionService
        );
    }

    @Test
    void usuarioInexistenteNoTocaCognitoNiPostgreSQL() {

        when(usuarioRepository.findById("sub-inexistente"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> usuarioAdminService.eliminarUsuario(
                "sub-inexistente",
                "sub-admin"
        ))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(
                        ((BusinessException) error).getCode()
                ).isEqualTo("USER_NOT_FOUND"));

        verifyNoInteractions(
                cognitoClient,
                dependencyChecker,
                localTransactionService
        );
    }

    @Test
    void usuarioQueExisteEnCognitoPeroFaltaEnPostgresNoSeElimina() {

        /*
         * El endpoint no recibe el email y no puede verificar la identidad
         * de una cuenta de Cognito sin un registro local. Se rechaza para no
         * borrar una cuenta por un identificador no verificado.
         */
        when(usuarioRepository.findById("sub-solo-cognito"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> usuarioAdminService.eliminarUsuario(
                "sub-solo-cognito",
                "sub-admin"
        ))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(
                        ((BusinessException) error).getCode()
                ).isEqualTo("USER_NOT_FOUND"));

        verifyNoInteractions(
                cognitoClient,
                dependencyChecker,
                localTransactionService
        );
    }

    @Test
    void usuarioConVariosRolesNoAdministrativosSeElimina() {

        Usuario usuario = usuario(
                "sub-multirol",
                "multirol@duoc.cl",
                "ESTUDIANTE",
                "PROFESOR",
                "COORDINADOR"
        );

        when(usuarioRepository.findById("sub-multirol"))
                .thenReturn(Optional.of(usuario));

        when(dependencyChecker.comprobar(usuario))
                .thenReturn(UsuarioDependencyChecker.Dependencias.SIN_DEPENDENCIAS);

        when(localTransactionService.eliminarUsuarioLocal("sub-multirol"))
                .thenReturn(true);

        when(usuarioRepository.existsById("sub-multirol"))
                .thenReturn(false);

        EliminacionUsuarioResponse response =
                usuarioAdminService.eliminarUsuario(
                        "sub-multirol",
                        "sub-admin"
                );

        assertThat(response.estado()).isEqualTo("ELIMINADO");

        verify(localTransactionService)
                .eliminarUsuarioLocal("sub-multirol");
    }

    @Test
    void unaCuentaAdministrativaEstaProtegida() {

        Usuario usuario = usuario(
                "sub-otro-admin",
                "otro-admin@duoc.cl",
                "ADMINISTRADOR",
                "ESTUDIANTE"
        );

        when(usuarioRepository.findById("sub-otro-admin"))
                .thenReturn(Optional.of(usuario));

        assertThatThrownBy(() -> usuarioAdminService.eliminarUsuario(
                "sub-otro-admin",
                "sub-admin"
        ))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(
                        ((BusinessException) error).getCode()
                ).isEqualTo("PROTECTED_ADMIN_ACCOUNT"));

        verifyNoInteractions(
                cognitoClient,
                dependencyChecker,
                localTransactionService
        );
    }

    @Test
    void lasDependenciasAcademicasBloqueanLaEliminacion() {

        Usuario usuario = usuario(
                "sub-docente",
                "docente@duoc.cl",
                "PROFESOR"
        );

        when(usuarioRepository.findById("sub-docente"))
                .thenReturn(Optional.of(usuario));

        when(dependencyChecker.comprobar(usuario))
                .thenReturn(new UsuarioDependencyChecker.Dependencias(
                        2,
                        3,
                        4,
                        1
                ));

        assertThatThrownBy(() -> usuarioAdminService.eliminarUsuario(
                "sub-docente",
                "sub-admin"
        ))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> {
                    BusinessException exception = (BusinessException) error;
                    assertThat(exception.getCode())
                            .isEqualTo("USER_HAS_DEPENDENCIES");
                    assertThat(exception.getMessage())
                            .contains("cursos como docente: 2")
                            .contains("matrículas: 3")
                            .contains("asistencias: 4")
                            .contains("participaciones en proyectos: 1")
                            .contains("No se realizó ningún cambio");
                });

        verifyNoInteractions(
                cognitoClient,
                localTransactionService
        );
    }

    @Test
    void siCognitoYaNoTieneLaCuentaSeReconciliaElRegistroLocal() {

        Usuario usuario = usuario(
                "sub-huerfano",
                "huerfano@duoc.cl",
                "ESTUDIANTE"
        );

        when(usuarioRepository.findById("sub-huerfano"))
                .thenReturn(Optional.of(usuario));

        when(dependencyChecker.comprobar(usuario))
                .thenReturn(UsuarioDependencyChecker.Dependencias.SIN_DEPENDENCIAS);

        doThrow(UserNotFoundException.builder()
                .message("User does not exist")
                .build())
                .when(cognitoClient)
                .adminDeleteUser(any(AdminDeleteUserRequest.class));

        when(localTransactionService.eliminarUsuarioLocal("sub-huerfano"))
                .thenReturn(true);

        when(usuarioRepository.existsById("sub-huerfano"))
                .thenReturn(false);

        EliminacionUsuarioResponse response =
                usuarioAdminService.eliminarUsuario(
                        "sub-huerfano",
                        "sub-admin"
                );

        assertThat(response.estado()).isEqualTo("RECONCILIADO");
        assertThat(response.cognitoEliminado()).isFalse();
        assertThat(response.postgresEliminado()).isTrue();

        verify(localTransactionService)
                .eliminarUsuarioLocal("sub-huerfano");
    }

    @Test
    void cognitoDeniegaLaOperacionYNoSeTocaPostgreSQL() {

        Usuario usuario = usuario(
                "sub-cognito-denegado",
                "denegado@duoc.cl",
                "ESTUDIANTE"
        );

        when(usuarioRepository.findById("sub-cognito-denegado"))
                .thenReturn(Optional.of(usuario));

        when(dependencyChecker.comprobar(usuario))
                .thenReturn(UsuarioDependencyChecker.Dependencias.SIN_DEPENDENCIAS);

        doThrow(ForbiddenException.builder()
                .message("Access denied")
                .build())
                .when(cognitoClient)
                .adminDeleteUser(any(AdminDeleteUserRequest.class));

        assertThatThrownBy(() -> usuarioAdminService.eliminarUsuario(
                "sub-cognito-denegado",
                "sub-admin"
        ))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(
                        ((BusinessException) error).getCode()
                ).isEqualTo("COGNITO_DELETE_FORBIDDEN"));

        verify(localTransactionService, never())
                .eliminarUsuarioLocal(anyString());
    }

    @Test
    void unaDenegacionGenerica403DeCognitoSeTraduceEnProhibido() {

        /*
         * En AWS SDK v2 la denegación de IAM puede llegar como una
         * CognitoIdentityProviderException con statusCode 403, no solo como
         * ForbiddenException/NotAuthorizedException.
         */
        Usuario usuario = usuario(
                "sub-cognito-403",
                "cognito-403@duoc.cl",
                "ESTUDIANTE"
        );

        when(usuarioRepository.findById("sub-cognito-403"))
                .thenReturn(Optional.of(usuario));

        when(dependencyChecker.comprobar(usuario))
                .thenReturn(UsuarioDependencyChecker.Dependencias.SIN_DEPENDENCIAS);

        doThrow(CognitoIdentityProviderException.builder()
                .statusCode(403)
                .message("Access denied")
                .build())
                .when(cognitoClient)
                .adminDeleteUser(any(AdminDeleteUserRequest.class));

        assertThatThrownBy(() -> usuarioAdminService.eliminarUsuario(
                "sub-cognito-403",
                "sub-admin"
        ))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(
                        ((BusinessException) error).getCode()
                ).isEqualTo("COGNITO_DELETE_FORBIDDEN"));

        verify(localTransactionService, never())
                .eliminarUsuarioLocal(anyString());
    }

    @Test
    void cognitoFallaTemporalmenteYNoSeTocaPostgreSQL() {

        Usuario usuario = usuario(
                "sub-cognito-error",
                "error@duoc.cl",
                "ESTUDIANTE"
        );

        when(usuarioRepository.findById("sub-cognito-error"))
                .thenReturn(Optional.of(usuario));

        when(dependencyChecker.comprobar(usuario))
                .thenReturn(UsuarioDependencyChecker.Dependencias.SIN_DEPENDENCIAS);

        doThrow(SdkClientException.create("Tiempo de espera agotado"))
                .when(cognitoClient)
                .adminDeleteUser(any(AdminDeleteUserRequest.class));

        assertThatThrownBy(() -> usuarioAdminService.eliminarUsuario(
                "sub-cognito-error",
                "sub-admin"
        ))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(
                        ((BusinessException) error).getCode()
                ).isEqualTo("COGNITO_DELETE_FAILED"));

        verify(localTransactionService, never())
                .eliminarUsuarioLocal(anyString());
    }

    @Test
    void cognitoLimitandoSolicitudesNoSeTocaPostgreSQL() {

        Usuario usuario = usuario(
                "sub-cognito-limitado",
                "limitado@duoc.cl",
                "ESTUDIANTE"
        );

        when(usuarioRepository.findById("sub-cognito-limitado"))
                .thenReturn(Optional.of(usuario));

        when(dependencyChecker.comprobar(usuario))
                .thenReturn(UsuarioDependencyChecker.Dependencias.SIN_DEPENDENCIAS);

        doThrow(TooManyRequestsException.builder()
                .message("Rate exceeded")
                .build())
                .when(cognitoClient)
                .adminDeleteUser(any(AdminDeleteUserRequest.class));

        assertThatThrownBy(() -> usuarioAdminService.eliminarUsuario(
                "sub-cognito-limitado",
                "sub-admin"
        ))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(
                        ((BusinessException) error).getCode()
                ).isEqualTo("COGNITO_DELETE_THROTTLED"));

        verify(localTransactionService, never())
                .eliminarUsuarioLocal(anyString());
    }

    @Test
    void falloDePostgreSQLDespuesDeCognitoNoSeInformaComoExito() {

        Usuario usuario = usuario(
                "sub-fallo-local",
                "fallo-local@duoc.cl",
                "ESTUDIANTE"
        );

        when(usuarioRepository.findById("sub-fallo-local"))
                .thenReturn(Optional.of(usuario));

        when(dependencyChecker.comprobar(usuario))
                .thenReturn(UsuarioDependencyChecker.Dependencias.SIN_DEPENDENCIAS);

        when(localTransactionService.eliminarUsuarioLocal("sub-fallo-local"))
                .thenThrow(new DataAccessResourceFailureException(
                        "Fallo simulado de PostgreSQL"
                ));

        when(usuarioRepository.existsById("sub-fallo-local"))
                .thenReturn(true);

        assertThatThrownBy(() -> usuarioAdminService.eliminarUsuario(
                "sub-fallo-local",
                "sub-admin"
        ))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(
                        ((BusinessException) error).getCode()
                ).isEqualTo("USER_DELETE_REQUIRES_RECONCILIATION"));

        verify(localTransactionService)
                .eliminarUsuarioLocal("sub-fallo-local");
    }

    @Test
    void elReintentoTrasUnFalloParcialEsSeguroYReconcilia() {

        Usuario usuario = usuario(
                "sub-reintento",
                "reintento@duoc.cl",
                "ESTUDIANTE"
        );

        when(usuarioRepository.findById("sub-reintento"))
                .thenReturn(Optional.of(usuario));

        when(dependencyChecker.comprobar(usuario))
                .thenReturn(UsuarioDependencyChecker.Dependencias.SIN_DEPENDENCIAS);

        /*
         * Primer intento: Cognito se elimina, PostgreSQL falla.
         */
        when(localTransactionService.eliminarUsuarioLocal("sub-reintento"))
                .thenThrow(new DataAccessResourceFailureException(
                        "Fallo simulado de PostgreSQL"
                ))
                .thenReturn(true);

        when(usuarioRepository.existsById("sub-reintento"))
                .thenReturn(true)
                .thenReturn(false);

        assertThatThrownBy(() -> usuarioAdminService.eliminarUsuario(
                "sub-reintento",
                "sub-admin"
        ))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(
                        ((BusinessException) error).getCode()
                ).isEqualTo("USER_DELETE_REQUIRES_RECONCILIATION"));

        /*
         * Segundo intento: Cognito ya no contiene la cuenta. Se trata como
         * ausencia controlada y se completa la limpieza local.
         */
        doThrow(UserNotFoundException.builder()
                .message("User does not exist")
                .build())
                .when(cognitoClient)
                .adminDeleteUser(any(AdminDeleteUserRequest.class));

        EliminacionUsuarioResponse response =
                usuarioAdminService.eliminarUsuario(
                        "sub-reintento",
                        "sub-admin"
                );

        assertThat(response.estado()).isEqualTo("RECONCILIADO");
        assertThat(response.cognitoEliminado()).isFalse();
        assertThat(response.postgresEliminado()).isTrue();

        verify(cognitoClient, times(2))
                .adminDeleteUser(any(AdminDeleteUserRequest.class));

        verify(localTransactionService, times(2))
                .eliminarUsuarioLocal("sub-reintento");
    }

    @Test
    void elIdentificadorVacioSeRechazaSinEfectosSecundarios() {

        assertThatThrownBy(() -> usuarioAdminService.eliminarUsuario(
                "   ",
                "sub-admin"
        ))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(
                        ((BusinessException) error).getCode()
                ).isEqualTo("VALIDATION_ERROR"));

        verifyNoInteractions(
                cognitoClient,
                usuarioRepository,
                dependencyChecker,
                localTransactionService
        );
    }

    @Test
    void sinAdministradorIdentificadoNoSeEjecutaLaOperacion() {

        assertThatThrownBy(() -> usuarioAdminService.eliminarUsuario(
                "sub-objetivo",
                null
        ))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(
                        ((BusinessException) error).getCode()
                ).isEqualTo("FORBIDDEN"));

        verifyNoInteractions(
                cognitoClient,
                usuarioRepository,
                dependencyChecker,
                localTransactionService
        );
    }

    private Usuario usuario(
            String id,
            String email,
            String... roles
    ) {

        Usuario usuario = new Usuario();
        usuario.setId(id);
        usuario.setEmail(email);
        usuario.setNombre("Usuario de prueba");
        usuario.setRoles(new LinkedHashSet<>(Arrays.asList(roles)));

        return usuario;
    }
}
