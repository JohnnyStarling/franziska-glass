package lab.franziska.pixelbridge;

import java.io.IOException;

/** Deliberately content-free errors: never propagate peer messages, URLs or credentials. */
public final class GatewayException extends IOException {
    public final String code;
    public final String pairingRequestId;
    /** Stable UI category; callers must never display a raw peer exception. */
    public final String category;
    GatewayException(String code) { this(code, null); }
    GatewayException(String code, String requestId) {
        super(code);
        this.code = code;
        this.pairingRequestId = requestId;
        this.category = "PAIRING_REQUIRED".equals(code) ? "PAIRING_REQUIRED"
            : (code.startsWith("AUTH_") || code.startsWith("BOOTSTRAP_") || code.startsWith("DEVICE_AUTH_") || code.startsWith("DEVICE_TOKEN_") || "SCOPE_MISMATCH".equals(code)) ? "AUTH_REQUIRED"
            : ("TIMEOUT".equals(code) || "TLS_FAILED".equals(code) || "DNS_FAILED".equals(code) || "CONNECTION_FAILED".equals(code) || "CONNECTION_CLOSED".equals(code) || "NETWORK_FAILED".equals(code) || "UPGRADE_REJECTED".equals(code)) ? "NETWORK_FAILURE"
            : "CANCELLED".equals(code) || "CREDENTIAL_REVOKED".equals(code) ? "CANCELLED"
            : "REQUEST_FAILED";
    }
}
