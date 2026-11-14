package com.baystudio.droide.core;

import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Properties;
import java.util.TreeSet;

/** Observes original class-resource inputs inside a Robolectric sandbox, not transformed bytecode. */
public final class CurrentAppRuntimeIdentity {
    private CurrentAppRuntimeIdentity() {}

    public static void verify() throws Exception {
        verifyFramework();
        String manifest = System.getProperty("droide.current.contract.identities");
        if (manifest == null) throw new AssertionError("Actual current-input manifest is required");
        Properties expected = new Properties();
        try (InputStream input = Files.newInputStream(Path.of(manifest))) { expected.load(input); }
        if (expected.isEmpty()) throw new AssertionError("Empty current-input manifest");
        for (String name : new TreeSet<>(expected.stringPropertyNames())) {
            Class<?> type = Class.forName(name, false, CurrentAppRuntimeIdentity.class.getClassLoader());
            URL resource = type.getResource("/" + name.replace('.', '/') + ".class");
            if (resource == null) throw new AssertionError("Missing class resource: " + name);
            byte[] bytes;
            try (InputStream input = resource.openStream()) { bytes = input.readAllBytes(); }
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder actual = new StringBuilder();
            for (byte value : digest) actual.append(String.format("%02x", value & 255));
            if (!actual.toString().equals(expected.getProperty(name))) {
                throw new AssertionError("Runtime resource differs from APK input: " + name);
            }
            String source = describeCodeSource(type.getProtectionDomain().getCodeSource());
            System.out.println("CURRENT_APP_SANDBOX_INPUT name=" + name + " codeSource=" + source +
                    " resource=" + resource + " sha256=" + actual + " executedBytecodeIdentity=false");
        }
    }
    private static void verifyFramework() throws Exception {
        String manifest = System.getProperty("droide.current.contract.frameworks");
        if (manifest == null) throw new AssertionError("Offline framework identity manifest required");
        Properties expected = new Properties();
        try (InputStream input = Files.newInputStream(Path.of(manifest))) { expected.load(input); }
        Class<?> build = Class.forName("android.os.Build", false, CurrentAppRuntimeIdentity.class.getClassLoader());
        URL resource = build.getResource("/android/os/Build.class");
        Path path = org.robolectric.RuntimeEnvironment.getAndroidFrameworkJarPath();
        Class<?> version = Class.forName("android.os.Build$VERSION", true, CurrentAppRuntimeIdentity.class.getClassLoader());
        int sdk = version.getField("SDK_INT").getInt(null);
        ClassLoader loader = build.getClassLoader();
        validateFrameworkSelection(path, sdk, org.robolectric.RuntimeEnvironment.getApiLevel(),
                loader == null ? null : loader.getClass().getName(), expected);
        String pinned = expected.getProperty(path.toString());
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[65536];
            for (int count; (count = input.read(buffer)) != -1;) digest.update(buffer, 0, count);
        }
        StringBuilder actual = new StringBuilder();
        for (byte value : digest.digest()) actual.append(String.format("%02x", value & 255));
        validateFrameworkDigest(pinned, actual.toString());
        System.out.println("CURRENT_APP_LOADED_FRAMEWORK file=" + path.getFileName() + " sha256=" + actual +
                " resource=" + resource + " observation=ROBOLECTRIC_RUNTIME_SDK_INPUT executedBytecodeIdentity=false");
    }

    static String describeCodeSource(java.security.CodeSource source) {
        return source == null || source.getLocation() == null ? "unavailable" : source.getLocation().toExternalForm();
    }

    static void validateFrameworkDigest(String expected, String actual) {
        if (expected == null || !expected.equals(actual)) throw new AssertionError("Selected sandbox SDK input digest differs");
    }

    /** Validates the selected sandbox input, not parent-visible resources or transformed bytecode. */
    static void validateFrameworkSelection(Path path, int sdk, int configuredSdk, String loader,
            Properties expected) {
        if (!"org.robolectric.internal.AndroidSandbox$SdkSandboxClassLoader".equals(loader))
            throw new AssertionError("Unexpected sandbox SDK loader");
        if (sdk != configuredSdk) throw new AssertionError("Sandbox SDK/API mismatch");
        String selected = switch (sdk) {
            case 29 -> "android-all-instrumented-10-robolectric-5803371-i7.jar";
            case 35 -> "android-all-instrumented-15-robolectric-12650502-i7.jar";
            default -> throw new AssertionError("Unexpected contract SDK: " + sdk);
        };
        if (path == null || !path.isAbsolute() || !path.equals(path.normalize()) ||
                !path.getFileName().toString().equals(selected))
            throw new AssertionError("SDK/framework selection mismatch");
        String pinned = expected.getProperty(path.toString());
        if (pinned == null || !pinned.matches("[0-9a-f]{64}"))
            throw new AssertionError("Unselected framework origin or invalid digest");
    }

}
