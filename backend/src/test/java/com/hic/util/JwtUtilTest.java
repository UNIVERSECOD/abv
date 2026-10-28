package com.hic.util;

import com.hic.model.User.UserType;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JwtUtilTest {

    private static final String SECRET =
            "TestJwtSecretKeyThatIsLongEnoughForHmacShaSigningAndMustStayPrivate123456789";

    private JwtUtil jwtUtil;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(
                jwtUtil,
                "secret",
                SECRET);
        ReflectionTestUtils.setField(jwtUtil, "expiration", 60_000L);
        ReflectionTestUtils.setField(jwtUtil, "refreshExpiration", 120_000L);
    }

    @Test
    void generatedAccessTokenCannotBeUsedAsRefreshToken() {
        String token = jwtUtil.generateToken("admin", UserType.HEAD_OFFICE_HR, 3L, 7L);

        assertThat(jwtUtil.validateToken(token)).isTrue();
        assertThat(jwtUtil.isAccessToken(token)).isTrue();
        assertThat(jwtUtil.isRefreshToken(token)).isFalse();
        assertThat(jwtUtil.extractUsername(token)).isEqualTo("admin");
    }

    @Test
    void sessionVersionSurvivesBothTokenRoundTrips() {
        String access = jwtUtil.generateToken("admin", UserType.HEAD_OFFICE_HR, 3L, 7L, 9L);
        String refresh = jwtUtil.generateRefreshToken("admin", 9L);
        assertThat(jwtUtil.extractSessionVersion(access)).isEqualTo(9L);
        assertThat(jwtUtil.extractSessionVersion(refresh)).isEqualTo(9L);
    }

    @Test
    void generatedRefreshTokenCannotAuthenticateApiRequests() {
        String token = jwtUtil.generateRefreshToken("admin");

        assertThat(jwtUtil.validateToken(token)).isTrue();
        assertThat(jwtUtil.isRefreshToken(token)).isTrue();
        assertThat(jwtUtil.isAccessToken(token)).isFalse();
    }

    @Test
    void invalidTokenHasNoAcceptedTokenType() {
        assertThat(jwtUtil.isAccessToken("not-a-token")).isFalse();
        assertThat(jwtUtil.isRefreshToken("not-a-token")).isFalse();
    }

    @Test
    void legacyTokensRemainValidOnlyForTheirOriginalPurpose() {
        Date expiry = new Date(System.currentTimeMillis() + 60_000L);
        var signingKey = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        String legacyAccessToken = Jwts.builder()
                .claims(Map.of("userType", UserType.HEAD_OFFICE_HR.name()))
                .subject("admin")
                .expiration(expiry)
                .signWith(signingKey)
                .compact();
        String legacyRefreshToken = Jwts.builder()
                .subject("admin")
                .expiration(expiry)
                .signWith(signingKey)
                .compact();

        assertThat(jwtUtil.isAccessToken(legacyAccessToken)).isTrue();
        assertThat(jwtUtil.isRefreshToken(legacyAccessToken)).isFalse();
        assertThat(jwtUtil.isRefreshToken(legacyRefreshToken)).isTrue();
        assertThat(jwtUtil.isAccessToken(legacyRefreshToken)).isFalse();
    }
}
