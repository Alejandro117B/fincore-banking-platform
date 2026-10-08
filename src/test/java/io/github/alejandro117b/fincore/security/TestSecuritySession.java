package io.github.alejandro117b.fincore.security;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

/** Test JVM only: ephemeral external keys and deterministic config, including when launched from an IDE. */
public class TestSecuritySession implements LauncherSessionListener {
    private Path directory;
    private Path privateFile;
    private Path publicFile;
    private final Map<String, String> previous = new LinkedHashMap<>();

    @Override
    public void launcherSessionOpened(LauncherSession session) {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            var keys = generator.generateKeyPair();
            directory = Files.createTempDirectory("fincore-test-jwt-");
            privateFile = directory.resolve("private.pem");
            publicFile = directory.resolve("public.pem");
            Files.writeString(privateFile, pem("PRIVATE KEY", keys.getPrivate().getEncoded()), StandardCharsets.US_ASCII);
            Files.writeString(publicFile, pem("PUBLIC KEY", keys.getPublic().getEncoded()), StandardCharsets.US_ASCII);
            set("fincore.security.jwt.private-key", privateFile.toUri().toString());
            set("fincore.security.jwt.public-key", publicFile.toUri().toString());
            set("fincore.security.jwt.issuer", "fincore-test");
            set("fincore.security.jwt.audience", "fincore-test-api");
            set("fincore.demo.seed.alejandro-email", "alejandro.demo@example.test");
            set("fincore.demo.seed.fernando-email", "fernando.demo@example.test");
            set("fincore.demo.seed.alejandro-password", "test-Alejandro-credential");
            set("fincore.demo.seed.fernando-password", "test-Fernando-credential");
        } catch (Exception ex) { throw new IllegalStateException("Cannot initialize test security", ex); }
    }

    private void set(String key, String value) {
        previous.put(key, System.getProperty(key));
        System.setProperty(key, value);
    }

    private static String pem(String label, byte[] bytes) {
        return "-----BEGIN " + label + "-----\n" + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(bytes)
                + "\n-----END " + label + "-----\n";
    }

    @Override
    public void launcherSessionClosed(LauncherSession session) {
        previous.forEach((key, value) -> { if (value == null) { System.clearProperty(key); } else { System.setProperty(key, value); } });
        try {
            if (privateFile != null) { Files.deleteIfExists(privateFile); }
            if (publicFile != null) { Files.deleteIfExists(publicFile); }
            if (directory != null) { Files.deleteIfExists(directory); }
        } catch (java.io.IOException ex) { throw new IllegalStateException("Cannot remove temporary test keys", ex); }
    }
}
