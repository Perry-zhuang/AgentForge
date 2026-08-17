package io.agentforge.model;

/** One repository-relative path expectation in the pre-execution intent contract. */
public record ExpectedChange(String path, ChangeExpectation expectation) {
    public ExpectedChange {
        if (path == null || path.isBlank() || expectation == null) {
            throw new IllegalArgumentException("Expected change path and expectation are required");
        }
        path = path.replace('\\', '/');
        if (path.startsWith("/") || path.equals("..") || path.startsWith("../") || path.contains("/../")) {
            throw new IllegalArgumentException("Expected change path must be repository-relative: " + path);
        }
    }
}
