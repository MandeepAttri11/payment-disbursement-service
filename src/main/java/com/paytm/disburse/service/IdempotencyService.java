package com.paytm.disburse.service;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Service
public class IdempotencyService {

    public String hash(String body) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(body.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public void verifyOrThrow(String existingKey, String existingHash,
                              String requestKey, String requestHash) {
        if (existingKey == null || requestKey == null) return;
        if (!existingKey.equals(requestKey)) return;
        if (!existingHash.equals(requestHash)) {
            throw new IdempotencyConflictException(
                "Idempotency-Key '" + requestKey + "' reused with different request body");
        }
    }
}
