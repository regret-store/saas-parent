package com.saas.common.utils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Component // 🌟 交给 Spring 管理
public class JwtUtils {

    // 从配置文件读取密钥
    @Value("${saas.jwt.secret}")
    private String secret;

    // 从配置文件读取过期时间（毫秒），默认 24 小时
    @Value("${saas.jwt.expiration:86400000}")
    private long expirationTime;

    private Key key;

    // 在依赖注入完成后，初始化 Key
    @PostConstruct
    public void init()
    {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException(
                    "saas.jwt.secret 长度不足 256bit（至少 32 个 ASCII 字符），请检查配置");
        }
        // 生产环境建议使用 256 位以上的密钥
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

//    // 生产环境请放在配置中心，且长度必须大于 256 位
//    // 过期时间：24小时
//    private static final long EXPIRATION_TIME = 86400000;

    // 生成 Token，注意把 tenantId 和 role 塞进去
    public String generateToken(String tenantId, String username, String role) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("tenantId", tenantId);
        claims.put("role", role);

        return Jwts.builder()
                .setClaims(claims)
                .setSubject(username)
                .setId(UUID.randomUUID().toString())   // 🌟 唯一标识，保证每次不同
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + expirationTime))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    // 解析 Token
    public Claims parseToken(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(key)
                .build()
                .parseClaimsJws(token)
                .getBody();
    }
}
