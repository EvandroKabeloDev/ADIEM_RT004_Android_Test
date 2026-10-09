package org.gradle.wrapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Minimal bootstrap launcher for this prototype ZIP. Downloads the pinned Gradle distribution. */
public final class GradleWrapperMain {
    public static void main(String[] args) throws Exception {
        Path projectDir = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path propertiesFile = projectDir.resolve("gradle/wrapper/gradle-wrapper.properties");
        if (!Files.exists(propertiesFile)) {
            throw new IllegalStateException("Não encontrei " + propertiesFile + ". Execute o wrapper na raiz do projeto.");
        }
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(propertiesFile)) { properties.load(in); }
        String distributionUrl = properties.getProperty("distributionUrl");
        if (distributionUrl == null || distributionUrl.isBlank()) {
            throw new IllegalStateException("distributionUrl ausente em gradle-wrapper.properties");
        }
        String archiveName = distributionUrl.substring(distributionUrl.lastIndexOf('/') + 1);
        String gradleFolder = archiveName.replaceAll("-(bin|all)\\.zip$", "");
        Path installRoot = Paths.get(System.getProperty("user.home"), ".gradle", "adiem-wrapper", gradleFolder);
        Path gradleHome = installRoot.resolve(gradleFolder);
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        Path executable = gradleHome.resolve("bin").resolve(windows ? "gradle.bat" : "gradle");
        if (!Files.exists(executable)) {
            Files.createDirectories(installRoot);
            Path archive = installRoot.resolve(archiveName);
            if (!Files.exists(archive) || Files.size(archive) == 0) {
                System.out.println("Baixando Gradle " + gradleFolder + " na primeira execução...");
                HttpURLConnection connection = (HttpURLConnection) URI.create(distributionUrl).toURL().openConnection();
                connection.setConnectTimeout(20000);
                connection.setReadTimeout(60000);
                connection.setInstanceFollowRedirects(true);
                int code = connection.getResponseCode();
                if (code < 200 || code >= 300) throw new IOException("Download Gradle falhou: HTTP " + code);
                try (InputStream in = connection.getInputStream()) {
                    Files.copy(in, archive, StandardCopyOption.REPLACE_EXISTING);
                } finally { connection.disconnect(); }
            }
            System.out.println("Extraindo Gradle...");
            try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    Path target = installRoot.resolve(entry.getName()).normalize();
                    if (!target.startsWith(installRoot)) throw new IOException("Entrada inválida no arquivo Gradle");
                    if (entry.isDirectory()) Files.createDirectories(target);
                    else {
                        Files.createDirectories(target.getParent());
                        Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                    zip.closeEntry();
                }
            }
        }
        if (!Files.exists(executable)) throw new IOException("Executável Gradle não encontrado: " + executable);
        if (!windows) executable.toFile().setExecutable(true, true);
        List<String> command = new ArrayList<>();
        if (windows) { command.add("cmd.exe"); command.add("/c"); }
        command.add(executable.toString());
        for (String arg : args) command.add(arg);
        Process process = new ProcessBuilder(command).directory(projectDir.toFile()).inheritIO().start();
        System.exit(process.waitFor());
    }
}
