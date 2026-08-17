package io.agentforge.discovery;

public final class DiscoveryException extends RuntimeException {
    private final DiscoveryEvidence evidence;
    public DiscoveryException(String message) { this(message, null, null); }
    public DiscoveryException(String message, Throwable cause) { this(message, null, cause); }
    public DiscoveryException(String message, DiscoveryEvidence evidence) { this(message, evidence, null); }
    public DiscoveryException(String message, DiscoveryEvidence evidence, Throwable cause) {
        super(message, cause);
        this.evidence = evidence;
    }
    public java.util.Optional<DiscoveryEvidence> evidence() { return java.util.Optional.ofNullable(evidence); }
}
