package com.hic.service;

import com.hic.dto.LoginRequest;
import com.hic.dto.LoginResponse;
import com.hic.dto.InitialAdminSetupRequest;
import com.hic.dto.InitialSetupStatusResponse;
import com.hic.dto.ChangePasswordRequest;
import com.hic.dto.SignupRequest;
import com.hic.dto.UserDTO;
import com.hic.exception.BadRequestException;
import com.hic.exception.UnauthorizedException;
import com.hic.model.User;
import com.hic.model.User.UserType;
import com.hic.repository.TenantRepository;
import com.hic.repository.UserRepository;
import com.hic.util.JwtUtil;
import com.hic.util.PasswordUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private InitialSetupGuard initialSetupGuard;

    @org.junit.jupiter.api.AfterEach
    void clearTenant() { com.hic.util.TenantContext.clear(); }

    @Mock
    private UserRepository userRepository;

    @Mock
    private TenantRepository tenantRepository;

    @Mock
    private JwtUtil jwtUtil;

    @Mock
    private PasswordUtil passwordUtil;

    @InjectMocks
    private AuthService authService;

    private User testUser;

    @BeforeEach
    void setUp() {
        testUser = new User();
        testUser.setId(1L);
        testUser.setUsername("admin");
        testUser.setEmail("admin@hic.az");
        testUser.setPasswordHash("$2a$10$hashedpassword");
        testUser.setUserType(UserType.HEAD_OFFICE_HR);
        testUser.setBranchId(1L);
    }

    // ---- Login tests ----

    @Test
    void login_validUsernameCredentials_returnsLoginResponse() {
        LoginRequest request = new LoginRequest();
        request.setUsername("admin");
        request.setPassword("admin123");

        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(testUser));
        when(passwordUtil.verifyPassword("admin123", testUser.getPasswordHash())).thenReturn(true);
        when(jwtUtil.generateToken(anyString(), any(UserType.class), any(), any(), org.mockito.ArgumentMatchers.anyLong())).thenReturn("access-token");
        when(jwtUtil.generateRefreshToken(anyString(), org.mockito.ArgumentMatchers.anyLong())).thenReturn("refresh-token");

        LoginResponse response = authService.login(request);

        assertThat(response).isNotNull();
        assertThat(response.getToken()).isEqualTo("access-token");
        assertThat(response.getRefreshToken()).isEqualTo("refresh-token");
        assertThat(response.getUser()).isNotNull();
        assertThat(response.getUser().getUsername()).isEqualTo("admin");
        assertThat(response.getUser().getUserType()).isEqualTo(UserType.HEAD_OFFICE_HR);
    }

    @Test
    void login_unknownUsername_throwsUnauthorizedException() {
        LoginRequest request = new LoginRequest();
        request.setUsername("unknown");
        request.setPassword("password");

        when(userRepository.findByUsername("unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("İstifadəçi adı");
    }

    @Test
    void login_wrongPassword_throwsUnauthorizedException() {
        LoginRequest request = new LoginRequest();
        request.setUsername("admin");
        request.setPassword("wrongpassword");

        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(testUser));
        when(passwordUtil.verifyPassword("wrongpassword", testUser.getPasswordHash())).thenReturn(false);

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("İstifadəçi adı");
    }

    @Test
    void login_initialSetupRequired_throwsBeforePasswordVerification() {
        LoginRequest request = new LoginRequest();
        request.setUsername("admin");
        request.setPassword("admin123");
        testUser.setPasswordSetupRequired(true);

        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(testUser));

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("İlkin administrator");
        verify(passwordUtil, never()).verifyPassword(anyString(), anyString());
    }

    @Test
    void login_emailAsLoginId_failsUnlessUsernameMatches() {
        LoginRequest request = new LoginRequest();
        request.setUsername("admin@hic.az");
        request.setPassword("admin123");

        when(userRepository.findByUsername("admin@hic.az")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(UnauthorizedException.class);
    }

    // ---- Initial admin setup tests ----

    @Test
    void getInitialSetupStatus_seededAdminRequiresSetup_returnsTrue() {
        testUser.setPasswordSetupRequired(true);
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(testUser));

        InitialSetupStatusResponse status = authService.getInitialSetupStatus();

        assertThat(status.setupRequired()).isTrue();
        assertThat(status.username()).isEqualTo("admin");
    }

    @Test
    void completeInitialAdminSetup_validPassword_updatesAdminAndReturnsTokens() {
        InitialAdminSetupRequest request = new InitialAdminSetupRequest();
        request.setPassword("new-password1");
        request.setConfirmPassword("new-password1");
        testUser.setPasswordSetupRequired(true);

        when(userRepository.findByUsernameForUpdate("admin")).thenReturn(Optional.of(testUser));
        when(passwordUtil.hashPassword("new-password1")).thenReturn("new-hash");
        when(userRepository.save(testUser)).thenReturn(testUser);
        when(jwtUtil.generateToken(anyString(), any(UserType.class), any(), any(), org.mockito.ArgumentMatchers.anyLong())).thenReturn("access-token");
        when(jwtUtil.generateRefreshToken(org.mockito.ArgumentMatchers.eq("admin"), org.mockito.ArgumentMatchers.anyLong())).thenReturn("refresh-token");

        LoginResponse response = authService.completeInitialAdminSetup(request);

        assertThat(testUser.getPasswordHash()).isEqualTo("new-hash");
        assertThat(testUser.getSessionVersion()).isEqualTo(1);
        assertThat(testUser.isPasswordSetupRequired()).isFalse();
        assertThat(response.getToken()).isEqualTo("access-token");
        assertThat(response.getUser().getUsername()).isEqualTo("admin");
        verify(userRepository).save(testUser);
    }

    @Test
    void completeInitialAdminSetup_passwordMismatch_rejectsWithoutLockingAdmin() {
        InitialAdminSetupRequest request = new InitialAdminSetupRequest();
        request.setPassword("new-password1");
        request.setConfirmPassword("different-password2");

        assertThatThrownBy(() -> authService.completeInitialAdminSetup(request))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("uyğun gəlmir");
        verify(userRepository, never()).findByUsernameForUpdate(anyString());
    }

    @Test
    void completeInitialAdminSetup_reusedDefaultPassword_isRejected() {
        InitialAdminSetupRequest request = new InitialAdminSetupRequest();
        request.setPassword("admin123");
        request.setConfirmPassword("admin123");
        testUser.setPasswordSetupRequired(true);

        when(userRepository.findByUsernameForUpdate("admin")).thenReturn(Optional.of(testUser));
        when(passwordUtil.verifyPassword("admin123", testUser.getPasswordHash())).thenReturn(true);

        assertThatThrownBy(() -> authService.completeInitialAdminSetup(request))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("fərqli olmalıdır");
        verify(passwordUtil, never()).hashPassword(anyString());
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void completeInitialAdminSetup_alreadyCompleted_rejectsPasswordChange() {
        InitialAdminSetupRequest request = new InitialAdminSetupRequest();
        request.setPassword("new-password1");
        request.setConfirmPassword("new-password1");

        when(userRepository.findByUsernameForUpdate("admin")).thenReturn(Optional.of(testUser));

        assertThatThrownBy(() -> authService.completeInitialAdminSetup(request))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("artıq tamamlanıb");
        verify(passwordUtil, never()).hashPassword(anyString());
        verify(userRepository, never()).save(any(User.class));
    }

    // ---- Password change tests ----

    @Test
    void changePassword_validCurrentPassword_updatesHash() {
        ChangePasswordRequest request = changePasswordRequest(
                "current-password1", "new-password2", "new-password2");

        when(userRepository.findByUsernameForUpdate("admin")).thenReturn(Optional.of(testUser));
        when(passwordUtil.verifyPassword("current-password1", testUser.getPasswordHash())).thenReturn(true);
        when(passwordUtil.verifyPassword("new-password2", testUser.getPasswordHash())).thenReturn(false);
        when(passwordUtil.hashPassword("new-password2")).thenReturn("new-hash");

        authService.changePassword("admin", request);

        assertThat(testUser.getPasswordHash()).isEqualTo("new-hash");
        verify(userRepository).save(testUser);
    }

    @Test
    void changePassword_wrongCurrentPassword_isRejected() {
        ChangePasswordRequest request = changePasswordRequest(
                "wrong-password1", "new-password2", "new-password2");

        when(userRepository.findByUsernameForUpdate("admin")).thenReturn(Optional.of(testUser));
        when(passwordUtil.verifyPassword("wrong-password1", testUser.getPasswordHash())).thenReturn(false);

        assertThatThrownBy(() -> authService.changePassword("admin", request))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Cari şifrə yanlışdır");
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void changePassword_mismatchedConfirmation_isRejectedBeforeUserLookup() {
        ChangePasswordRequest request = changePasswordRequest(
                "current-password1", "new-password2", "different-password3");

        assertThatThrownBy(() -> authService.changePassword("admin", request))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("uyğun gəlmir");
        verify(userRepository, never()).findByUsernameForUpdate(anyString());
    }

    @Test
    void changePassword_reusedCurrentPassword_isRejected() {
        ChangePasswordRequest request = changePasswordRequest(
                "current-password1", "current-password1", "current-password1");

        when(userRepository.findByUsernameForUpdate("admin")).thenReturn(Optional.of(testUser));
        when(passwordUtil.verifyPassword("current-password1", testUser.getPasswordHash())).thenReturn(true);

        assertThatThrownBy(() -> authService.changePassword("admin", request))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("fərqli olmalıdır");
        verify(passwordUtil, never()).hashPassword(anyString());
        verify(userRepository, never()).save(any(User.class));
    }

    // ---- Signup / RBAC tests ----

    @Test
    void signup_headOfficeHrCreatesOfficeHr_succeeds() {
        com.hic.util.TenantContext.setTenantId(1L);
        com.hic.model.Tenant tenant = new com.hic.model.Tenant();
        tenant.setId(1L);

        SignupRequest request = new SignupRequest();
        request.setUsername("new.user");
        request.setFirstName("New");
        request.setLastName("User");
        request.setPassword("password1");
        request.setRole("OFFICE_HR");

        when(userRepository.existsByUsername("new.user")).thenReturn(false);
        when(tenantRepository.findByTenantCode("DEFAULT")).thenReturn(Optional.of(tenant));
        when(passwordUtil.hashPassword("password1")).thenReturn("hash");
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(jwtUtil.generateToken(anyString(), any(), any(), any(), org.mockito.ArgumentMatchers.anyLong())).thenReturn("tok");
        when(jwtUtil.generateRefreshToken(anyString(), org.mockito.ArgumentMatchers.anyLong())).thenReturn("ref");

        LoginResponse response = authService.signup(request, UserType.HEAD_OFFICE_HR);

        assertThat(response).isNotNull();
        assertThat(response.getUser().getUserType()).isEqualTo(UserType.OFFICE_HR);
        assertThat(response.getUser().getUsername()).isEqualTo("new.user");
    }

    @Test
    void signup_callerCannotCreateEqualRole_throws() {
        SignupRequest request = new SignupRequest();
        request.setUsername("peer.user");
        request.setFirstName("Peer");
        request.setLastName("User");
        request.setPassword("password1");
        request.setRole("OFFICE_HR");

        when(userRepository.existsByUsername("peer.user")).thenReturn(false);

        assertThatThrownBy(() -> authService.signup(request, UserType.OFFICE_HR))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("OFFICE_HR");
    }

    @Test
    void signup_callerCannotCreateHigherRole_throws() {
        SignupRequest request = new SignupRequest();
        request.setUsername("boss.user");
        request.setFirstName("Boss");
        request.setLastName("User");
        request.setPassword("password1");
        request.setRole("HEAD_OFFICE_HR");

        when(userRepository.existsByUsername("boss.user")).thenReturn(false);

        assertThatThrownBy(() -> authService.signup(request, UserType.DEPARTMENT_HR))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void signup_duplicateUsername_throws() {
        SignupRequest request = new SignupRequest();
        request.setUsername("existing");
        request.setFirstName("Ex");
        request.setLastName("Ist");
        request.setPassword("password1");

        when(userRepository.existsByUsername("existing")).thenReturn(true);

        assertThatThrownBy(() -> authService.signup(request, UserType.HEAD_OFFICE_HR))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("istifadəçi adı");
    }

    @Test
    void signup_anonymousWhenUsersExist_throwsUnauthorized() {
        SignupRequest request = new SignupRequest();
        request.setUsername("second.admin");
        request.setFirstName("Second");
        request.setLastName("User");
        request.setPassword("password1");
        request.setRole("HEAD_OFFICE_HR");

        when(userRepository.existsByUsername("second.admin")).thenReturn(false);
        when(userRepository.count()).thenReturn(1L);

        assertThatThrownBy(() -> authService.signup(request, null))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("authentication");
    }

    @Test
    void signup_anonymousBootstrap_createsHeadOffice() {
        com.hic.model.Tenant tenant = new com.hic.model.Tenant();
        tenant.setId(1L);

        SignupRequest request = new SignupRequest();
        request.setUsername("first.admin");
        request.setFirstName("First");
        request.setLastName("Admin");
        request.setPassword("password1");
        request.setRole("HEAD_OFFICE_HR");

        when(userRepository.existsByUsername("first.admin")).thenReturn(false);
        when(userRepository.count()).thenReturn(0L);
        when(tenantRepository.findByTenantCode("DEFAULT")).thenReturn(Optional.of(tenant));
        when(passwordUtil.hashPassword("password1")).thenReturn("hash");
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(jwtUtil.generateToken(anyString(), any(), any(), any(), org.mockito.ArgumentMatchers.anyLong())).thenReturn("tok");
        when(jwtUtil.generateRefreshToken(anyString(), org.mockito.ArgumentMatchers.anyLong())).thenReturn("ref");

        LoginResponse response = authService.signup(request, null);

        assertThat(response.getUser().getUserType()).isEqualTo(UserType.HEAD_OFFICE_HR);
    }

    @Test
    void signup_headOfficeCannotCreatePeerHeadOffice_throws() {
        SignupRequest request = new SignupRequest();
        request.setUsername("peer.head");
        request.setFirstName("Peer");
        request.setLastName("Head");
        request.setPassword("password1");
        request.setRole("HEAD_OFFICE_HR");

        when(userRepository.existsByUsername("peer.head")).thenReturn(false);

        assertThatThrownBy(() -> authService.signup(request, UserType.HEAD_OFFICE_HR))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("HEAD_OFFICE_HR");
    }

    // ---- Token utility tests ----

    @Test
    void verifyToken_validToken_returnsTrue() {
        when(jwtUtil.validateToken("valid-token")).thenReturn(true);
        when(jwtUtil.isAccessToken("valid-token")).thenReturn(true);
        when(jwtUtil.extractUsername("valid-token")).thenReturn("admin");
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(testUser));
        assertThat(authService.verifyToken("valid-token")).isTrue();
    }

    @Test
    void verifyToken_invalidToken_returnsFalse() {
        when(jwtUtil.validateToken("invalid-token")).thenReturn(false);
        assertThat(authService.verifyToken("invalid-token")).isFalse();
    }

    @Test
    void passwordVersionChangeRejectsOldAccessAndRefreshTokens() {
        testUser.setSessionVersion(1);
        when(jwtUtil.validateToken("old")).thenReturn(true);
        when(jwtUtil.isAccessToken("old")).thenReturn(true);
        when(jwtUtil.isRefreshToken("old")).thenReturn(true);
        when(jwtUtil.extractUsername("old")).thenReturn("admin");
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(testUser));
        assertThat(authService.verifyToken("old")).isFalse();
        assertThatThrownBy(() -> authService.refreshToken("old")).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void refreshToken_validRefreshToken_returnsNewAccessToken() {
        when(jwtUtil.validateToken("valid-refresh-token")).thenReturn(true);
        when(jwtUtil.isRefreshToken("valid-refresh-token")).thenReturn(true);
        when(jwtUtil.extractUsername("valid-refresh-token")).thenReturn("admin");
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(testUser));
        when(jwtUtil.generateToken(anyString(), any(UserType.class), any(), any(), org.mockito.ArgumentMatchers.anyLong())).thenReturn("new-access-token");

        String newToken = authService.refreshToken("valid-refresh-token");

        assertThat(newToken).isEqualTo("new-access-token");
    }

    @Test
    void refreshToken_invalidRefreshToken_throwsUnauthorizedException() {
        when(jwtUtil.validateToken("bad-token")).thenReturn(false);

        assertThatThrownBy(() -> authService.refreshToken("bad-token"))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("Invalid or expired refresh token");
    }

    @Test
    void refreshToken_accessToken_throwsUnauthorizedException() {
        when(jwtUtil.validateToken("access-token")).thenReturn(true);
        when(jwtUtil.isRefreshToken("access-token")).thenReturn(false);

        assertThatThrownBy(() -> authService.refreshToken("access-token"))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("Invalid or expired refresh token");
    }

    @Test
    void getUserFromToken_validToken_returnsUserDTO() {
        when(jwtUtil.validateToken("valid-token")).thenReturn(true);
        when(jwtUtil.isAccessToken("valid-token")).thenReturn(true);
        when(jwtUtil.extractUsername("valid-token")).thenReturn("admin");
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(testUser));

        UserDTO userDTO = authService.getUserFromToken("valid-token");

        assertThat(userDTO).isNotNull();
        assertThat(userDTO.getUsername()).isEqualTo("admin");
    }

    @Test
    void getUserFromToken_invalidToken_throwsUnauthorizedException() {
        when(jwtUtil.validateToken("bad-token")).thenReturn(false);

        assertThatThrownBy(() -> authService.getUserFromToken("bad-token"))
                .isInstanceOf(UnauthorizedException.class);
    }

    private static ChangePasswordRequest changePasswordRequest(
            String currentPassword, String newPassword, String confirmPassword) {
        ChangePasswordRequest request = new ChangePasswordRequest();
        request.setCurrentPassword(currentPassword);
        request.setNewPassword(newPassword);
        request.setConfirmPassword(confirmPassword);
        return request;
    }
}
