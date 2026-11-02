package com.glofox.memberships.idempotency.service.request;

import com.glofox.memberships.membership.api.PurchaseRequest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class CanonicalPurchaseRequestHasher {

    public String hash(PurchaseRequest request) {
        MessageDigest digest = newSha256();
        update(digest, request.memberName().trim().toLowerCase(Locale.ROOT));
        update(digest, request.planId().toString());
        update(digest, request.startDate().toString());
        return HexFormat.of().formatHex(digest.digest());
    }

    private void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
