package io.agentforge.agent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class AgentProcessFixture {
    private static final int LARGE_OUTPUT_SIZE = 128 * 1024;

    private AgentProcessFixture() {}

    public static void main(String[] arguments) throws Exception {
        byte[] prompt = System.in.readAllBytes();
        String mode = arguments[0];
        switch (mode) {
            case "facts" -> facts(prompt);
            case "non-zero" -> nonZero();
            case "large-stdout" -> largeStdout();
            case "large-stderr" -> largeStderr();
            case "large-both" -> largeBoth();
            case "timeout" -> timeout();
            case "environment" -> environment();
            default -> throw new IllegalArgumentException("Unknown fixture mode: " + mode);
        }
    }

    private static void facts(byte[] prompt) throws Exception {
        System.out.println("PWD=" + Path.of("").toRealPath());
        System.out.println("PROMPT_SHA256=" + sha256(prompt));
        System.out.println("PROMPT_BYTES=" + prompt.length);
        System.err.print("stderr-captured");
    }

    private static void nonZero() {
        System.out.print("non-zero-stdout");
        System.err.print("non-zero-stderr");
        System.out.flush();
        System.err.flush();
        System.exit(23);
    }

    private static void largeStdout() {
        System.out.print("O".repeat(LARGE_OUTPUT_SIZE));
    }

    private static void largeStderr() {
        System.err.print("E".repeat(LARGE_OUTPUT_SIZE));
    }

    private static void largeBoth() throws InterruptedException {
        Thread stdout = new Thread(() -> System.out.print("O".repeat(LARGE_OUTPUT_SIZE)));
        Thread stderr = new Thread(() -> System.err.print("E".repeat(LARGE_OUTPUT_SIZE)));
        stdout.start();
        stderr.start();
        stdout.join();
        stderr.join();
    }

    private static void timeout() throws InterruptedException {
        System.out.println("started");
        System.out.flush();
        Thread.sleep(30_000);
    }

    private static void environment() {
        System.out.println("PATH_INHERITED=" + (System.getenv("PATH") != null));
        System.out.println("PATH_VALUE=" + System.getenv("PATH"));
        System.out.println("OVERRIDE=" + System.getenv("AGENTFORGE_TEST_OVERRIDE"));
    }

    private static String sha256(byte[] content) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }
}
