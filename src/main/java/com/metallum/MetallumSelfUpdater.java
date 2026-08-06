package com.metallum;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 启动时自更新：通过 GitHub Actions Artifacts API 检查最新构建（按 tag 触发），
 * 若与本 jar 的 Implementation-Build（构建时的 GITHUB_REF_NAME）不同，自动下载
 * artifact 中的主 jar 替换 mods 目录旧版本，重启后生效。
 *
 * <p>认证：读取环境变量 GH_TOKEN（Personal Access Token，建议 fine-grained、
 * 仅本仓库 Actions: Read + Metadata: Read，无写权限）。iOS 上 Amethyst 支持在
 * 启动器内配置自定义环境变量（日志 "Reading custom environment variables"）。
 * 无 token 或本地构建（dev）时静默跳过。
 *
 * <p>时序：onInitialize 启动 daemon 线程，不阻塞渲染线程；全部异常静默处理，
 * 不影响游戏启动。
 */
public final class MetallumSelfUpdater {
    private static final String REPO = "PigeonCoders/MetalUniversal";
    private static final String ARTIFACTS_URL =
            "https://api.github.com/repos/" + REPO + "/actions/artifacts?per_page=5";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofSeconds(60);

    private MetallumSelfUpdater() {
    }

    public static void startIfNeeded() {
        String buildTag = readBuildTag();
        // buildTag 为 null（历史 jar 无 Implementation-Build manifest，如
        // fix-autoupdate 之前的版本）时也检查更新，否则旧 jar 永远无法被自更新拉新。
        if (buildTag != null && ("dev".equals(buildTag) || buildTag.isBlank())) {
            return;
        }
        String token = System.getenv("GH_TOKEN");
        if (token == null || token.isBlank()) {
            return;
        }
        Thread updater = new Thread(() -> checkForUpdate(buildTag, token), "metallum-self-updater");
        updater.setDaemon(true);
        updater.start();
    }

    /**
     * 读取本 jar manifest 的 Implementation-Build（= CI 触发 tag；本地构建为 dev/null）。
     */
    private static String readBuildTag() {
        try {
            var location = MetallumSelfUpdater.class.getProtectionDomain().getCodeSource().getLocation();
            if (location == null) {
                return null;
            }
            Path jarPath = Path.of(location.toURI());
            if (!Files.isRegularFile(jarPath) || !jarPath.toString().endsWith(".jar")) {
                return null;
            }
            try (JarFile jar = new JarFile(jarPath.toFile())) {
                var attrs = jar.getManifest() != null ? jar.getManifest().getMainAttributes() : null;
                return attrs != null ? attrs.getValue("Implementation-Build") : null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static void checkForUpdate(String buildTag, String token) {
        Logger logger = Metallum.LOGGER;
        try {
            JsonObject artifact = findLatestArtifact(token);
            if (artifact == null) {
                return;
            }
            String latestTag = artifact.getAsJsonObject("workflow_run").get("head_branch").getAsString();
            if (latestTag == null || latestTag.isBlank() || latestTag.equals(buildTag)) {
                return;
            }

            long artifactId = artifact.get("id").getAsLong();
            long expectedSize = artifact.get("size_in_bytes").getAsLong();
            Path modsDir = FabricLoader.getInstance().getGameDir().resolve("mods");
            Files.createDirectories(modsDir);

            // 1. 下载 artifact zip（.tmp 后缀，Fabric loader 不扫描）
            Path zipTmp = modsDir.resolve("metallum-update.zip.tmp");
            downloadArtifact(token, artifactId, zipTmp);

            // 2. 解压提取主 jar（非 sources），落盘为 zip 内原名
            String jarName = extractMainJar(zipTmp, modsDir);
            if (jarName == null) {
                Files.deleteIfExists(zipTmp);
                return;
            }
            Path newJar = modsDir.resolve(jarName);

            // 3. 大小校验（对比 API 返回的 size_in_bytes）
            if (Files.size(newJar) != expectedSize) {
                logger.warn("[metallum] 自更新大小校验失败（{} != {}），回滚", newJar.getFileName(), expectedSize);
                Files.deleteIfExists(newJar);
                Files.deleteIfExists(zipTmp);
                return;
            }

            // 4. 删除旧版本 jar（unlink 运行中文件：POSIX/APFS 允许，进程持有旧 inode）
            deleteOldJars(modsDir, newJar);
            Files.deleteIfExists(zipTmp);

            logger.info("[metallum] 检测到新版 {}（当前 {}），已自动更新为 {}，重启游戏后生效",
                    latestTag, buildTag, jarName);
        } catch (Exception e) {
            logger.debug("[metallum] 自更新检查失败（非致命）", e);
        }
    }

    private static JsonObject findLatestArtifact(String token) throws Exception {
        String json = httpGet(ARTIFACTS_URL, token, DOWNLOAD_TIMEOUT);
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        for (var element : root.getAsJsonArray("artifacts")) {
            JsonObject artifact = element.getAsJsonObject();
            if (!artifact.has("expired") || artifact.get("expired").getAsBoolean()) {
                continue;
            }
            if (artifact.has("workflow_run")
                    && artifact.getAsJsonObject("workflow_run").has("head_branch")) {
                return artifact;
            }
        }
        return null;
    }

    private static void downloadArtifact(String token, long artifactId, Path target) throws Exception {
        String url = "https://api.github.com/repos/" + REPO + "/actions/artifacts/" + artifactId + "/zip";
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(DOWNLOAD_TIMEOUT)
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        HttpResponse<Path> response = client.send(request, HttpResponse.BodyHandlers.ofFile(target));
        if (response.statusCode() != 200) {
            throw new IllegalStateException("artifact download HTTP " + response.statusCode());
        }
    }

    /**
     * 解压 artifact zip，提取名为 metallum-*.jar 且不含 sources 的条目到 modsDir。
     *
     * @return 落盘 jar 的文件名；未找到返回 null
     */
    private static String extractMainJar(Path zipPath, Path modsDir) throws Exception {
        String mainJarName = null;
        Path mainJarTmp = null;
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipPath))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = Path.of(entry.getName()).getFileName().toString();
                if (name.startsWith("metallum-") && name.endsWith(".jar") && !name.contains("sources")) {
                    mainJarName = name;
                    mainJarTmp = modsDir.resolve(name + ".tmp");
                    Files.copy(zis, mainJarTmp, StandardCopyOption.REPLACE_EXISTING);
                    break;
                }
            }
        }
        if (mainJarName == null || mainJarTmp == null) {
            return null;
        }
        // 以 .jar 原名落盘（先 .tmp 后 move，避免半成品被 loader 扫描）
        Path finalPath = modsDir.resolve(mainJarName);
        Files.move(mainJarTmp, finalPath, StandardCopyOption.REPLACE_EXISTING);
        return mainJarName;
    }

    private static void deleteOldJars(Path modsDir, Path keep) throws Exception {
        try (Stream<Path> list = Files.list(modsDir)) {
            for (Path p : list.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().startsWith("metallum-"))
                    .filter(p -> p.getFileName().toString().endsWith(".jar"))
                    .filter(p -> !p.equals(keep))
                    .toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    private static String httpGet(String url, String token, Duration timeout) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("GitHub API HTTP " + response.statusCode());
        }
        return response.body();
    }
}
