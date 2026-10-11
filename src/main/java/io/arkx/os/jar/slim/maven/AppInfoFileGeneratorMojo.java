package io.arkx.os.jar.slim.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.*;
import org.apache.maven.project.MavenProject;
import org.apache.maven.artifact.Artifact;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.Set;
import java.util.HashSet;

/**
 * 生成依赖文件的Maven插件
 * 生成两种文件：
 * 1. app-info.json - v2 应用描述符，launchCommand 为 argfile 形式
 * 2. .runtime/launch.args - v2 命令模板（argfile，classpath 完整展开，运行时只做占位符替换）
 */
@Mojo(name = "generate-appinfo-json", defaultPhase = LifecyclePhase.PACKAGE,
      requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME)
public class AppInfoFileGeneratorMojo extends AbstractMojo {

    /**
     * 当前的Maven项目
     */
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    public MavenProject project;

    /**
     * 输出目录
     */
    @Parameter(defaultValue = "${project.build.directory}", required = true)
    public File outputDirectory;

    /**
     * 是否包含测试依赖
     */
    @Parameter(defaultValue = "false", required = false)
    private boolean includeTestDependencies;

    /**
     * 目标JDK版本（可选，不设置则自动从项目编译配置中读取）
     * 优先级：显式配置 > maven.compiler.target > java.version > maven.compiler.release > maven.compiler.source > 当前JVM版本 > "11"
     */
    @Parameter(required = false)
    private String targetJdkVersion;

    /**
     * 应用类型（DESIGN.md §6.1）：ARK_SLIM / SPRING_BOOT_FAT_JAR / BASIC
     */
    @Parameter(defaultValue = "ARK_SLIM", required = false)
    String appType;

    /**
     * 资源目录列表（相对 bundle 根目录），如 resources/runtimes、resources/static —— DESIGN.md §6.1
     */
    @Parameter(required = false)
    java.util.List<String> appResources;

    /**
     * 启动参数模板，支持 {app.home} 占位符 —— DESIGN.md §6.1
     */
    @Parameter(required = false)
    String launchArgsTemplate;

    /**
     * BASIC 类型应用的启动命令模板（仅 appType=BASIC 时使用）—— v2 DESIGN.md §5
     */
    @Parameter(required = false)
    String basicLaunchCommand;

    /**
     * BASIC 类型应用的停止命令模板（仅 appType=BASIC 时使用）—— v2 DESIGN.md §5
     */
    @Parameter(required = false)
    String basicStopCommand;

    public void execute() throws MojoExecutionException, MojoFailureException {
        getLog().info("开始生成依赖文件...");

        try {
            // 确保输出目录存在
            if (!outputDirectory.exists()) {
                outputDirectory.mkdirs();
            }

            // 1. 生成 v2 命令模板 .runtime/launch.args（argfile，classpath 完整展开，DESIGN.md §5）
            File launchArgsDir = new File(outputDirectory, ".runtime");
            if (!launchArgsDir.exists()) {
                launchArgsDir.mkdirs();
            }
            File launchArgsFile = new File(launchArgsDir, "launch.args");
            getLog().info("正在生成命令模板: " + launchArgsFile.getAbsolutePath());
            generateLaunchArgsTemplate(launchArgsFile);

            // 2. 生成 v2 应用描述符 app-info.json（DESIGN.md §5 / §12 阶段 2.1）
            File appInfoFile = new File(outputDirectory, "app-info.json");
            getLog().info("正在生成 v2 app-info: " + appInfoFile.getAbsolutePath());
            generateAppInfoJson(appInfoFile);

            getLog().info("依赖文件已生成:");
            getLog().info("  launch.args: " + launchArgsFile.getAbsolutePath());
            getLog().info("  app-info: " + appInfoFile.getAbsolutePath());

        } catch (IOException e) {
            throw new MojoExecutionException("生成依赖文件失败", e);
        }
    }

    /**
     * 生成 v2 命令模板 .runtime/launch.args（DESIGN.md §5 / §12 阶段 2.2）
     * <p>
     * 模板结构：首行 -cp（主文件用 {app.home}，依赖用 {m2.repo} 相对路径，分隔符 {path.sep}），
     * 次行 mainClass，其后 launchArgsTemplate 按空白拆分逐行追加。
     * 运行期由 command-core/boot 做占位符替换后即可直接执行（java @xxx.args 标准 argfile 用法）。
     */
    private void generateLaunchArgsTemplate(File launchArgsFile) throws IOException {
        String localRepoPath = getLocalRepositoryPath();
        String mainJarName = project.getArtifactId() + "-" + project.getVersion() + ".jar";

        StringBuilder sb = new StringBuilder();
        sb.append("-cp \"").append("{app.home}/").append(mainJarName).append("{path.sep}");
        Set<Artifact> artifacts = new HashSet<>(project.getArtifacts());
        boolean first = true;
        for (Artifact artifact : artifacts) {
            String scope = artifact.getScope();
            if (includeTestDependencies || !"test".equals(scope)) {
                if (!"provided".equals(scope) && !"system".equals(scope)) {
                    if (artifact.getFile() != null && artifact.getFile().exists()) {
                        String relPath = toRelativeRepoPath(artifact, localRepoPath);
                        if (!first) {
                            sb.append("{path.sep}");
                        }
                        sb.append("{m2.repo}/").append(relPath);
                        first = false;
                    }
                }
            }
        }
        sb.append("\"\n");
        sb.append(getMainClass()).append("\n");
        if (launchArgsTemplate != null && !launchArgsTemplate.isEmpty()) {
            for (String token : launchArgsTemplate.trim().split("\\s+")) {
                sb.append(token).append("\n");
            }
        }
        try (FileWriter writer = new FileWriter(launchArgsFile, false)) {
            writer.write(sb.toString());
        }
    }

    /**
     * 计算文件 SHA-256（十六进制小写）；计算失败返回空串
     */
    private static String calcSha256(File file) {
        try (FileInputStream in = new FileInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    /**
     * 生成 v2 应用描述符 app-info.json（DESIGN.md §5 / §12 阶段 2.1）
     * <p>
     * 主文件名固定为 {artifactId}-{version}.jar（DESIGN.md §4.1），sha256 对原 jar 计算；
     * .runtime/launch.args 为 argfile 命令模板（首行 -cp 完整展开、次行 mainClass）；
     * resources 目录作为 files 目录条目（sha256 为空，下载侧目录整体复制）。
     */
    private void generateAppInfoJson(File appInfoFile) throws IOException {
        String localRepoPath = getLocalRepositoryPath();
        String mainJarName = project.getArtifactId() + "-" + project.getVersion() + ".jar";
        File mainJar = new File(outputDirectory, mainJarName);

        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"name\": \"").append(escapeJson(project.getName() != null ? project.getName() : project.getArtifactId())).append("\",\n");
        json.append("  \"version\": \"").append(escapeJson(project.getVersion())).append("\",\n");
        json.append("  \"type\": \"").append(escapeJson(appType != null ? appType : "ARK_SLIM")).append("\",\n");
        json.append("  \"groupId\": \"").append(escapeJson(project.getGroupId())).append("\",\n");
        json.append("  \"artifactId\": \"").append(escapeJson(project.getArtifactId())).append("\",\n");
        json.append("  \"mainClass\": \"").append(escapeJson(getMainClass())).append("\",\n");
        json.append("  \"jdkVersion\": \"").append(escapeJson(resolveTargetJdkVersion())).append("\",\n");
        // v2 统一命令：java @{app.home}/.runtime/launch.args（argfile，完整命令行模板，运行期替换占位符后直接执行）
        json.append("  \"launchCommand\": \"").append(escapeJson(resolveLaunchCommand())).append("\",\n");
        String stopCmd = resolveStopCommand();
        if (stopCmd != null && !stopCmd.isEmpty()) {
            json.append("  \"stopCommand\": \"").append(escapeJson(stopCmd)).append("\",\n");
        }
        // files 清单：主文件 + .runtime/launch.args + 资源目录
        json.append("  \"files\": [\n");
        json.append("    {\"path\": \"").append(escapeJson(mainJarName)).append("\", \"sha256\": \"")
            .append(calcSha256(mainJar)).append("\"},\n");
        json.append("    {\"path\": \".runtime/launch.args\", \"sha256\": \"")
            .append(calcSha256(new File(outputDirectory, ".runtime/launch.args"))).append("\"}");
        if (appResources != null) {
            for (String r : appResources) {
                json.append(",\n    {\"path\": \"").append(escapeJson(r)).append("\", \"sha256\": \"\"}");
            }
        }
        json.append("\n  ],\n");
        // dependencies（坐标 + sha256 + m2 相对路径）
        json.append("  \"dependencies\": [\n");
        Set<Artifact> artifacts = new HashSet<>(project.getArtifacts());
        boolean first = true;
        for (Artifact artifact : artifacts) {
            String scope = artifact.getScope();
            if (includeTestDependencies || !"test".equals(scope)) {
                if (!"provided".equals(scope) && !"system".equals(scope)) {
                    if (artifact.getFile() != null && artifact.getFile().exists()) {
                        if (!first) {
                            json.append(",\n");
                        }
                        String relPath = toRelativeRepoPath(artifact, localRepoPath);
                        json.append("    {\"groupId\": \"").append(escapeJson(artifact.getGroupId()))
                            .append("\", \"artifactId\": \"").append(escapeJson(artifact.getArtifactId()))
                            .append("\", \"version\": \"").append(escapeJson(artifact.getVersion()))
                            .append("\", \"sha256\": \"").append(calcSha256(artifact.getFile()))
                            .append("\", \"path\": \"").append(escapeJson(relPath)).append("\"}");
                        first = false;
                    }
                }
            }
        }
        json.append("\n  ]\n");
        json.append("}\n");

        try (FileWriter writer = new FileWriter(appInfoFile, false)) {
            writer.write(json.toString());
        }
    }

    /**
     * 解析 app-info 的 launchCommand（DESIGN.md §5）
     * <p>
     * BASIC：取插件参数 basicLaunchCommand；
     * ARK_SLIM / SPRING_BOOT_FAT_JAR：默认经 argfile 启动
     * （.runtime/launch.args 为完整命令行模板，运行时替换占位符后 java @xxx.args 直接执行）。
     */
    private String resolveLaunchCommand() {
        if (appType != null && appType.equalsIgnoreCase("BASIC")) {
            return basicLaunchCommand != null ? basicLaunchCommand : "";
        }
        return "java @{app.home}/.runtime/launch.args";
    }

    /**
     * 解析 app-info 的 stopCommand；Java 类型按 PID 停止，app-info 不输出该字段
     */
    private String resolveStopCommand() {
        if (appType != null && appType.equalsIgnoreCase("BASIC")) {
            return basicStopCommand;
        }
        return null;
    }

    /**
     * 获取Maven本地仓库路径
     */
    private String getLocalRepositoryPath() {
        // 方式1：尝试通过系统属性获取
        String repoPath = System.getProperty("maven.repo.local");
        if (repoPath != null) {
            return repoPath.replace("\\", "/");
        }
        // 方式2：从任意一个依赖的路径推断（找到repository/目录的父路径）
        for (Artifact artifact : project.getArtifacts()) {
            if (artifact.getFile() != null) {
                String absPath = artifact.getFile().getAbsolutePath().replace("\\", "/");
                int repoIdx = absPath.indexOf("/repository/");
                if (repoIdx >= 0) {
                    return absPath.substring(0, repoIdx + "/repository".length());
                }
            }
        }
        // 方式3：回退到用户目录下的.m2/repository
        String userHome = System.getProperty("user.home");
        if (userHome != null) {
            return userHome.replace("\\", "/") + "/.m2/repository";
        }
        return null;
    }

    /**
     * 依赖相对路径（v2 跨机一致性,调整于 2026-08-19）：
     * 优先从绝对路径相对化到 m2 仓库；无法相对化（如同项目 reactor 模块的 target/ 产物）时
     * 回退为按坐标推导的 m2 布局 groupId/artifactId/version/artifactId-version[-classifier].jar。
     */
    private String toRelativeRepoPath(Artifact artifact, String repoPath) {
        String absPath = artifact.getFile().getAbsolutePath().replace("\\", "/");
        String rel = toRelativeRepoPath(absPath, repoPath);
        if (!rel.equals(absPath)) {
            return rel;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(artifact.getGroupId().replace('.', '/')).append('/')
          .append(artifact.getArtifactId()).append('/')
          .append(artifact.getVersion()).append('/')
          .append(artifact.getArtifactId()).append('-').append(artifact.getVersion());
        if (artifact.getClassifier() != null && !artifact.getClassifier().isEmpty()) {
            sb.append('-').append(artifact.getClassifier());
        }
        sb.append(".jar");
        return sb.toString();
    }

    /**
     * 将绝对路径转为相对于Maven仓库的路径
     * D:/.m2/repository/org/springframework/spring-jcl/5.3.20/spring-jcl-5.3.20.jar
     * → org/springframework/spring-jcl/5.3.20/spring-jcl-5.3.20.jar
     */
    private String toRelativeRepoPath(String absPath, String repoPath) {
        if (repoPath == null || repoPath.isEmpty()) return absPath;

        // 标准化路径
        String normalizedAbs = absPath.replace("\\", "/");
        String normalizedRepo = repoPath.replace("\\", "/");

        // 查找repository/目录的位置
        int repoIdx = normalizedAbs.indexOf("repository/");
        if (repoIdx >= 0) {
            return normalizedAbs.substring(repoIdx + "repository/".length());
        }

        // 回退：尝试移除仓库前缀
        if (normalizedAbs.startsWith(normalizedRepo)) {
            String rel = normalizedAbs.substring(normalizedRepo.length());
            if (rel.startsWith("/")) rel = rel.substring(1);
            return rel;
        }

        // 无法识别，保持原样
        return normalizedAbs;
    }

    /**
     * 解析目标JDK版本。
     * 优先级：显式参数配置 > maven.compiler.target > java.version > maven.compiler.release > maven.compiler.source > 当前JVM主版本 > "11"
     */
    private String resolveTargetJdkVersion() {
        // 1. 用户显式配置
        if (targetJdkVersion != null && !targetJdkVersion.isEmpty()) {
            getLog().info("使用用户配置的目标JDK版本: " + targetJdkVersion);
            return targetJdkVersion;
        }

        java.util.Properties props = project.getProperties();

        // 2. maven.compiler.target（标准Maven）
        String version = props.getProperty("maven.compiler.target");
        if (version != null && !version.isEmpty()) {
            getLog().info("从 maven.compiler.target 读取到JDK版本: " + version);
            return version;
        }

        // 3. java.version（Spring Boot风格）
        version = props.getProperty("java.version");
        if (version != null && !version.isEmpty()) {
            getLog().info("从 java.version 读取到JDK版本: " + version);
            return version;
        }

        // 4. maven.compiler.release（Java 9+）
        version = props.getProperty("maven.compiler.release");
        if (version != null && !version.isEmpty()) {
            getLog().info("从 maven.compiler.release 读取到JDK版本: " + version);
            return version;
        }

        // 5. maven.compiler.source
        version = props.getProperty("maven.compiler.source");
        if (version != null && !version.isEmpty()) {
            getLog().info("从 maven.compiler.source 读取到JDK版本: " + version);
            return version;
        }

        // 6. 从当前运行JVM获取主版本号
        // java.version 可能是 "1.8.0_202"、"11.0.16"、"17.0.1" 等格式
        String javaVersion = System.getProperty("java.version");
        if (javaVersion != null && !javaVersion.isEmpty()) {
            String major;
            if (javaVersion.startsWith("1.")) {
                // Java 8及以前: "1.8.0_202" → "8"
                major = javaVersion.substring(2, 3);
            } else {
                // Java 9+: "11.0.16" → "11", "17.0.1" → "17"
                int dotIdx = javaVersion.indexOf('.');
                major = dotIdx > 0 ? javaVersion.substring(0, dotIdx) : javaVersion;
            }
            getLog().info("从当前JVM读取到主版本: " + major + " (原始: " + javaVersion + ")");
            return major;
        }

        // 7. 最终回退
        getLog().warn("无法自动检测JDK版本，使用默认值: 11");
        return "11";
    }

    /**
     * 获取主类名
     */
    private String getMainClass() {
        // 尝试从Maven配置获取
        String mainClass = null;

        // 首先尝试从MANIFEST.MF文件读取Main-Class
        try {
            String originalJarName = project.getArtifactId() + "-" + project.getVersion() + ".jar";
            File originalJar = new File(outputDirectory, originalJarName);
            if (originalJar.exists()) {
                try (java.util.jar.JarFile jarFile = new java.util.jar.JarFile(originalJar)) {
                    java.util.jar.Manifest manifest = jarFile.getManifest();
                    if (manifest != null) {
                        mainClass = manifest.getMainAttributes().getValue("Main-Class");
                        if (mainClass != null && !mainClass.trim().isEmpty()) {
                            getLog().info("从MANIFEST.MF读取到Main-Class: " + mainClass);
                            return mainClass;
                        }
                    }
                }
            }
        } catch (Exception e) {
            getLog().warn("无法从MANIFEST.MF读取Main-Class: " + e.getMessage());
        }

        // 尝试从spring-boot插件配置获取
        try {
            org.apache.maven.model.Plugin springBootPlugin = project.getPlugin("org.springframework.boot:spring-boot-maven-plugin");
            if (springBootPlugin != null && springBootPlugin.getConfiguration() != null) {
                // 尝试解析配置
                Object config = springBootPlugin.getConfiguration();
                // 这里可以进一步解析，暂时跳过
            }
        } catch (Exception e) {
            getLog().warn("无法从spring-boot插件获取Main-Class: " + e.getMessage());
        }

        // 从项目配置的 maven-jar-plugin <archive><manifest><mainClass> 读取（最可靠的来源）
        try {
            org.apache.maven.model.Plugin jarPlugin = project.getBuild().getPluginsAsMap().get("org.apache.maven.plugins:maven-jar-plugin");
            if (jarPlugin != null && jarPlugin.getConfiguration() != null) {
                org.codehaus.plexus.util.xml.Xpp3Dom cfg = (org.codehaus.plexus.util.xml.Xpp3Dom) jarPlugin.getConfiguration();
                org.codehaus.plexus.util.xml.Xpp3Dom archive = cfg.getChild("archive");
                if (archive != null) {
                    org.codehaus.plexus.util.xml.Xpp3Dom manifest = archive.getChild("manifest");
                    if (manifest != null) {
                        org.codehaus.plexus.util.xml.Xpp3Dom mc = manifest.getChild("mainClass");
                        if (mc != null && mc.getValue() != null && !mc.getValue().trim().isEmpty()) {
                            mainClass = mc.getValue().trim();
                            getLog().info("从maven-jar-plugin配置读取到Main-Class: " + mainClass);
                            return mainClass;
                        }
                    }
                }
            }
        } catch (Exception e) {
            getLog().warn("无法从maven-jar-plugin配置读取Main-Class: " + e.getMessage());
        }

        // 如果还是获取不到，使用一个常见的默认值或者返回null
        if (mainClass == null) {
            // 检查是否有常见的Spring Boot主类
            String groupId = project.getGroupId();
            String artifactId = project.getArtifactId();

            // 尝试几种可能的组合
            String[] possibleMainClasses = {
                groupId + "." + artifactId.replace("-", "") + "Application",
                groupId + "." + artifactId + ".Application",
                artifactId.replace("-", "") + "Application"
            };

            for (String possibleClass : possibleMainClasses) {
                getLog().info("尝试使用可能的Main-Class: " + possibleClass);
                mainClass = possibleClass;
                break; // 使用第一个可能的类名
            }
        }

        getLog().info("最终使用的Main-Class: " + mainClass);
        return mainClass;
    }
}
