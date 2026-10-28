package com.hic.service;

import com.hic.exception.UnauthorizedException;
import com.hic.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class InitialSetupGuardTest {
    @TempDir Path directory;

    @Test
    void missingAndIncorrectCodesCannotBootstrap() {
        var guard = new InitialSetupGuard(mock(UserRepository.class), directory.toString(), "synthetic-only-setup-secret-with-32-characters");
        assertThatThrownBy(() -> guard.verify(null)).isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> guard.verify("wrong")).isInstanceOf(UnauthorizedException.class);
        assertThatCode(() -> guard.verify("synthetic-only-setup-secret-with-32-characters")).doesNotThrowAnyException();
    }

    @Test
    void generatedCodePersistsWithoutReplacingItAfterRestart() throws Exception {
        var users = mock(UserRepository.class);
        var first = new InitialSetupGuard(users, directory.toString(), "");
        first.prepare();
        String code = Files.readString(directory.resolve("initial-setup-token"));
        assertThat(code).hasSize(64);
        var restarted = new InitialSetupGuard(users, directory.toString(), "");
        restarted.prepare();
        assertThatCode(() -> restarted.verify(code)).doesNotThrowAnyException();
        assertThat(Files.readString(directory.resolve("initial-setup-token"))).isEqualTo(code);
    }
}
