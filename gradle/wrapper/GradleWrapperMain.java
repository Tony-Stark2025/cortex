package org.gradle.wrapper;

import java.io.*;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.jar.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

public class GradleWrapperMain {
    public static void main(String[] args) throws Exception {
        File projectDir = new File(System.getProperty("user.dir"));
        File wrapperDir = new File(projectDir, "gradle/wrapper");
        File wrapperJar = new File(wrapperDir, "gradle-wrapper.jar");
        File sourceFile = new File(wrapperDir, "GradleWrapperMain.java");

        // Self-bootstrap gradle-wrapper.jar if launched via JEP-330 source execution
        if (!wrapperJar.exists() && sourceFile.exists()) {
            try {
                bootstrapWrapperJar(sourceFile, wrapperDir, wrapperJar);
            } catch (Exception ignored) {
                // Proceed with direct execution even if JDK compiler is unavailable
            }
        }

        File propsFile = new File(wrapperDir, "gradle-wrapper.properties");
        Properties props = new Properties();
        try (InputStream in = new FileInputStream(propsFile)) {
            props.load(in);
        }

        String distUrl = props.getProperty("distributionUrl");
        if (distUrl == null || distUrl.isEmpty()) {
            throw new IllegalStateException("Missing distributionUrl in gradle-wrapper.properties");
        }
        distUrl = distUrl.replace("\\:", ":");

        String gradleUserHome = System.getenv("GRADLE_USER_HOME");
        if (gradleUserHome == null || gradleUserHome.isEmpty()) {
            gradleUserHome = new File(System.getProperty("user.home"), ".gradle").getAbsolutePath();
        }

        String distPath = props.getProperty("distributionPath", "wrapper/dists");
        String zipName = distUrl.substring(distUrl.lastIndexOf('/') + 1);
        String distName = zipName.endsWith(".zip") ? zipName.substring(0, zipName.length() - 4) : zipName;
        String hash = md5Base36(distUrl);

        File distDir = new File(new File(gradleUserHome, distPath), distName + "/" + hash);
        distDir.mkdirs();

        File markerFile = new File(distDir, zipName + ".ok");
        if (!markerFile.exists()) {
            File zipFile = new File(distDir, zipName);
            System.out.println("Downloading " + distUrl);
            downloadWithRedirects(distUrl, zipFile);
            unzip(zipFile, distDir);
            markerFile.createNewFile();
        }

        File launcherJar = findGradleLauncherJar(distDir);
        if (launcherJar != null) {
            java.net.URLClassLoader classLoader = new java.net.URLClassLoader(
                new java.net.URL[]{launcherJar.toURI().toURL()},
                ClassLoader.getPlatformClassLoader()
            );
            Thread.currentThread().setContextClassLoader(classLoader);
            Class<?> mainClass = classLoader.loadClass("org.gradle.launcher.GradleMain");
            java.lang.reflect.Method mainMethod = mainClass.getMethod("main", String[].class);
            mainMethod.invoke(null, (Object) args);
            return;
        }

        File gradleBin = findGradleExecutable(distDir);
        if (gradleBin == null) {
            throw new IllegalStateException("Could not locate gradle binary inside " + distDir);
        }
        gradleBin.setExecutable(true);

        boolean isWindows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        List<String> cmd = new ArrayList<>();
        if (isWindows) {
            cmd.add("cmd.exe");
            cmd.add("/c");
        }
        cmd.add(gradleBin.getAbsolutePath());
        cmd.addAll(Arrays.asList(args));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(projectDir);
        pb.inheritIO();
        Process process = pb.start();
        System.exit(process.waitFor());
    }

    private static File findGradleLauncherJar(File distDir) {
        File[] children = distDir.listFiles();
        if (children == null) return null;
        for (File child : children) {
            if (child.isDirectory()) {
                File libDir = new File(child, "lib");
                File[] jars = libDir.listFiles();
                if (jars != null) {
                    for (File jar : jars) {
                        if (jar.getName().startsWith("gradle-launcher-") && jar.getName().endsWith(".jar")) {
                            return jar;
                        }
                    }
                }
            }
        }
        return null;
    }

    private static void bootstrapWrapperJar(File sourceFile, File wrapperDir, File wrapperJar) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) return;
        File tempClasses = new File(wrapperDir, "tmp_classes");
        tempClasses.mkdirs();
        int res = compiler.run(null, null, null, "--release", "17", "-d", tempClasses.getAbsolutePath(), sourceFile.getAbsolutePath());
        if (res == 0) {
            Manifest manifest = new Manifest();
            manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
            manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "org.gradle.wrapper.GradleWrapperMain");
            try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(wrapperJar), manifest)) {
                File classFile = new File(tempClasses, "org/gradle/wrapper/GradleWrapperMain.class");
                if (classFile.exists()) {
                    JarEntry entry = new JarEntry("org/gradle/wrapper/GradleWrapperMain.class");
                    jos.putNextEntry(entry);
                    try (InputStream in = new FileInputStream(classFile)) {
                        byte[] buf = new byte[4096];
                        int len;
                        while ((len = in.read(buf)) != -1) {
                            jos.write(buf, 0, len);
                        }
                    }
                    jos.closeEntry();
                }
            }
        }
        deleteRecursively(tempClasses);
    }

    private static void deleteRecursively(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File c : children) deleteRecursively(c);
            }
        }
        file.delete();
    }

    private static String md5Base36(String input) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
        return new BigInteger(1, digest).toString(36);
    }

    private static void downloadWithRedirects(String urlStr, File dest) throws Exception {
        String currentUrl = urlStr;
        for (int i = 0; i < 10; i++) {
            HttpURLConnection conn = (HttpURLConnection) URI.create(currentUrl).toURL().openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(60000);
            int status = conn.getResponseCode();
            if (status >= 300 && status < 400) {
                String location = conn.getHeaderField("Location");
                conn.disconnect();
                currentUrl = URI.create(currentUrl).resolve(location).toString();
                continue;
            }
            if (status != 200) {
                throw new IOException("Failed to download Gradle distribution: HTTP " + status);
            }
            try (InputStream in = conn.getInputStream();
                 OutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
            }
            return;
        }
        throw new IOException("Too many redirects for " + urlStr);
    }

    private static void unzip(File zipFile, File targetDir) throws Exception {
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                File outFile = new File(targetDir, entry.getName());
                if (!outFile.getCanonicalPath().startsWith(targetDir.getCanonicalPath())) {
                    throw new IOException("Zip entry outside target dir: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    outFile.mkdirs();
                } else {
                    outFile.getParentFile().mkdirs();
                    try (OutputStream out = new FileOutputStream(outFile)) {
                        byte[] buf = new byte[8192];
                        int len;
                        while ((len = zis.read(buf)) != -1) {
                            out.write(buf, 0, len);
                        }
                    }
                    if (outFile.getName().equals("gradle")) {
                        outFile.setExecutable(true);
                    }
                }
            }
        }
    }

    private static File findGradleExecutable(File distDir) {
        boolean isWindows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        String exeName = isWindows ? "gradle.bat" : "gradle";
        File[] children = distDir.listFiles();
        if (children == null) return null;
        for (File child : children) {
            if (child.isDirectory()) {
                File candidate = new File(new File(child, "bin"), exeName);
                if (candidate.exists()) return candidate;
            }
        }
        return null;
    }
}
