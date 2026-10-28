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
import com.hic.repository.TenantRepository;
import com.hic.repository.UserRepository;
import com.hic.util.JwtUtil;
import com.hic.util.PasswordUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class AuthService {

    private static final String INITIAL_ADMIN_USERNAME = "admin";

    private final UserRepository userRepository;
    private final TenantRepository tenantRepository;
    private final JwtUtil jwtUtil;
    private final PasswordUtil passwordUtil;
    private final InitialSetupGuard initialSetupGuard;

    /**
     * Role hierarchy: HEAD_OFFICE_HR > OFFICE_HR > DEPARTMENT_HR > EMPLOYEE.
     * Higher ordinal = lower authority.
     */
    private static int roleRank(User.UserType type) {
        return switch (type) {
            case HEAD_OFFICE_HR -> 0;
            case OFFICE_HR -> 1;
            case DEPARTMENT_HR -> 2;
            case EMPLOYEE -> 3;
        };
    }

    /**
     * @param callerRole authenticated caller's role, or {@code null} for anonymous bootstrap
     */
    public LoginResponse signup(SignupRequest request, User.UserType callerRole) {
        String username = request.getUsername() != null ? request.getUsername().trim() : "";
        if (!StringUtils.hasText(username)) {
            throw new BadRequestException("Username is required");
        }
        if (userRepository.existsByUsername(username)) {
            throw new BadRequestException("Bu istifadəçi adı artıq mövcuddur");
        }
        if (StringUtils.hasText(request.getEmail()) && userRepository.existsByEmail(request.getEmail().trim())) {
            throw new BadRequestException("Bu e-poçt artıq mövcuddur");
        }

        User.UserType targetRole = User.UserType.EMPLOYEE;
        if (request.getRole() != null && !request.getRole().isBlank()) {
            try {
                targetRole = User.UserType.valueOf(request.getRole().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new BadRequestException("Invalid role: " + request.getRole());
            }
        }

        if (callerRole == null) {
            if (userRepository.count() > 0) {
                throw new UnauthorizedException("Signup requires authentication after the first admin is created");
            }
            if (targetRole != User.UserType.HEAD_OFFICE_HR) {
                throw new BadRequestException("First account must use role HEAD_OFFICE_HR");
            }
            initialSetupGuard.verify(request.getSetupToken());
        } else if (roleRank(callerRole) >= roleRank(targetRole)) {
            throw new BadRequestException(
                    "You cannot create an account with role " + targetRole + " as your own role is " + callerRole);
        }

        var defaultTenant = tenantRepository.findByTenantCode("DEFAULT")
                .orElseThrow(() -> new BadRequestException("Default tenant not configured"));

        User user = new User();
        user.setUsername(username);
        user.setEmail(StringUtils.hasText(request.getEmail()) ? request.getEmail().trim() : null);
        user.setFirstName(request.getFirstName());
        user.setLastName(request.getLastName());
        user.setPasswordHash(passwordUtil.hashPassword(request.getPassword()));
        user.setUserType(targetRole);
        Long tenantId = callerRole == null ? defaultTenant.getId() : com.hic.util.TenantContext.getTenantId();
        if (tenantId == null) {
            throw new UnauthorizedException("Şirkət konteksti müəyyən edilmədi");
        }
        user.setTenantId(tenantId);
        userRepository.save(user);

        return createLoginResponse(user);
    }

    public LoginResponse login(LoginRequest request) {
        String username = request.getUsername() != null ? request.getUsername().trim() : "";
        if (!StringUtils.hasText(username)) {
            throw new UnauthorizedException("İstifadəçi adı və ya şifrə yanlışdır");
        }

        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UnauthorizedException("İstifadəçi adı və ya şifrə yanlışdır"));

        if (user.isPasswordSetupRequired()) {
            throw new UnauthorizedException("İlkin administrator şifrəsi yaradılmalıdır");
        }

        if (!passwordUtil.verifyPassword(request.getPassword(), user.getPasswordHash())) {
            throw new UnauthorizedException("İstifadəçi adı və ya şifrə yanlışdır");
        }

        return createLoginResponse(user);
    }

    @Transactional(readOnly = true)
    public InitialSetupStatusResponse getInitialSetupStatus() {
        boolean setupRequired = userRepository.findByUsername(INITIAL_ADMIN_USERNAME)
                .map(User::isPasswordSetupRequired)
                .orElse(false);
        return new InitialSetupStatusResponse(setupRequired, INITIAL_ADMIN_USERNAME);
    }

    @Transactional
    public LoginResponse completeInitialAdminSetup(InitialAdminSetupRequest request) {
        if (!request.getPassword().equals(request.getConfirmPassword())) {
            throw new BadRequestException("Şifrələr uyğun gəlmir");
        }

        User admin = userRepository.findByUsernameForUpdate(INITIAL_ADMIN_USERNAME)
                .orElseThrow(() -> new BadRequestException("İlkin administrator hesabı tapılmadı"));

        if (!admin.isPasswordSetupRequired()) {
            throw new BadRequestException("İlkin administrator quraşdırması artıq tamamlanıb");
        }

        initialSetupGuard.verify(request.getSetupToken());

        if (passwordUtil.verifyPassword(request.getPassword(), admin.getPasswordHash())) {
            throw new BadRequestException("Yeni şifrə standart şifrədən fərqli olmalıdır");
        }

        admin.setPasswordHash(passwordUtil.hashPassword(request.getPassword()));
        admin.setSessionVersion(admin.getSessionVersion() + 1);
        admin.setPasswordSetupRequired(false);
        userRepository.save(admin);

        return createLoginResponse(admin);
    }

    @Transactional
    public void changePassword(String username, ChangePasswordRequest request) {
        if (!request.getNewPassword().equals(request.getConfirmPassword())) {
            throw new BadRequestException("Şifrələr uyğun gəlmir");
        }

        User user = userRepository.findByUsernameForUpdate(username)
                .orElseThrow(() -> new UnauthorizedException("İstifadəçi tapılmadı"));

        if (!passwordUtil.verifyPassword(request.getCurrentPassword(), user.getPasswordHash())) {
            throw new BadRequestException("Cari şifrə yanlışdır");
        }
        if (passwordUtil.verifyPassword(request.getNewPassword(), user.getPasswordHash())) {
            throw new BadRequestException("Yeni şifrə cari şifrədən fərqli olmalıdır");
        }

        user.setPasswordHash(passwordUtil.hashPassword(request.getNewPassword()));
        user.setSessionVersion(user.getSessionVersion() + 1);
        userRepository.save(user);
    }

    public boolean verifyToken(String token) {
        try {
            getUserFromToken(token);
            return true;
        } catch (RuntimeException invalidSession) {
            return false;
        }
    }

    public String refreshToken(String refreshToken) {
        if (!jwtUtil.validateToken(refreshToken) || !jwtUtil.isRefreshToken(refreshToken)) {
            throw new UnauthorizedException("Invalid or expired refresh token");
        }
        String username = jwtUtil.extractUsername(refreshToken);
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UnauthorizedException("User not found"));
        if (user.isPasswordSetupRequired() || jwtUtil.extractSessionVersion(refreshToken) != user.getSessionVersion()) {
            throw new UnauthorizedException("Sessiya bitib. Yenidən daxil olun");
        }
        return jwtUtil.generateToken(user.getUsername(), user.getUserType(), user.getTenantId(), user.getId(), user.getSessionVersion());
    }

    public UserDTO getUserFromToken(String token) {
        if (!jwtUtil.validateToken(token) || !jwtUtil.isAccessToken(token)) {
            throw new UnauthorizedException("Invalid or expired token");
        }
        String username = jwtUtil.extractUsername(token);
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UnauthorizedException("User not found"));
        if (user.isPasswordSetupRequired() || jwtUtil.extractSessionVersion(token) != user.getSessionVersion()) {
            throw new UnauthorizedException("Sessiya bitib. Yenidən daxil olun");
        }
        return toDTO(user);
    }

    private LoginResponse createLoginResponse(User user) {
        String token = jwtUtil.generateToken(user.getUsername(), user.getUserType(), user.getTenantId(), user.getId(), user.getSessionVersion());
        String refreshToken = jwtUtil.generateRefreshToken(user.getUsername(), user.getSessionVersion());
        return new LoginResponse(token, refreshToken, toDTO(user));
    }

    private UserDTO toDTO(User user) {
        return new UserDTO(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getFirstName(),
                user.getLastName(),
                user.getUserType(),
                user.getBranchId(),
                user.getDepartmentId(),
                user.getTenantId()
        );
    }
}
