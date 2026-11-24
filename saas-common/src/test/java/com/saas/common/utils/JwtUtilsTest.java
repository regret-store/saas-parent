package com.saas.common.utils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

/** JwtUtils 是纯逻辑类，不想起 Spring 上下文，直接反射塞 @Value 字段 */
class JwtUtilsTest {

    private static final String SECRET = "unit-test-only-secret-0123456789abcdef";

    private JwtUtils jwtUtils;

    private JwtUtils build(String secret, long ttlMillis) {
        JwtUtils u = new JwtUtils();
        ReflectionTestUtils.setField(u, "secret", secret);
        ReflectionTestUtils.setField(u, "expirationTime", ttlMillis);
        u.init();   // @PostConstruct 要手动调，否则 key 是 null
        return u;
    }

    @BeforeEach
    void setUp() {
        jwtUtils = build(SECRET, 86400000L);
    }

    @Test
    @DisplayName("签发后能解析回来，tenantId / role / subject 三个字段一个都不能丢")
    void tokenCarriesTenantAndRole() {
        String token = jwtUtils.generateToken("t_001", "alice", "TENANT_ADMIN");
        Claims claims = jwtUtils.parseToken(token);

        assertEquals("t_001", claims.get("tenantId", String.class));
        assertEquals("TENANT_ADMIN", claims.get("role", String.class));
        assertEquals("alice", claims.getSubject());
    }

    @Test
    @DisplayName("同一条 token 每次生成都不同（含 iat），但都能解析")
    void tokensAreDistinctButParsable() {
        String a = jwtUtils.generateToken("t_001", "alice", "USER");
        String b = jwtUtils.generateToken("t_001", "alice", "USER");
        assertNotEquals(a, b);
        assertEquals(jwtUtils.parseToken(a).getSubject(), jwtUtils.parseToken(b).getSubject());
    }

    @Test
    @DisplayName("篡改签名位 → 必须解析失败，不能被当成合法用户")
    void tamperedTokenIsRejected() {
        String token = jwtUtils.generateToken("t_001", "alice", "USER");
        String tampered = token.substring(0, token.length() - 2) + "xx";
        assertThrows(JwtException.class, () -> jwtUtils.parseToken(tampered));
    }

    @Test
    @DisplayName("换了密钥的服务端解析不了原 token（三个服务 secret 必须一致）")
    void otherSecretCannotParse() {
        String token = jwtUtils.generateToken("t_001", "alice", "USER");
        JwtUtils evil = build("AnotherSecretKeyForHmacSha256SigningMustBe32Bytes!", 86400000L);
        assertThrows(JwtException.class, () -> evil.parseToken(token));
    }

    @Test
    @DisplayName("TTL 过期后必须拒绝（当前 expiration=24h，意味着 token 发出去收不回）")
    void expiredTokenIsRejected() {
        JwtUtils shortTtl = build(SECRET, -1000L);   // 负数：签发即过期，省掉 sleep
        String token = shortTtl.generateToken("t_001", "alice", "USER");
        JwtUtils parser = build(SECRET, 86400000L);
        assertThrows(JwtException.class, () -> parser.parseToken(token));
    }

    @Test
    @DisplayName("secret 短于 256bit 时初始化就该失败，避免上线才发现签不出 token")
    void tooShortSecretFailsFast() {
        assertThrows(IllegalArgumentException.class,
                () -> build("short", 1000L).generateToken("t_001", "alice", "USER"));
    }
}