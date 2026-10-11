package io.arkx.os.jar.slim.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.*;
import org.apache.maven.project.MavenProject;

import java.io.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.nio.file.Files;

/**
 * 打包瘦JAR的Maven插件
 */
@Mojo(name = "package-slim-jar", defaultPhase = LifecyclePhase.PACKAGE,
      requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME)
public class SlimJarMojo extends AbstractMojo {

    /**
     * 当前的Maven项目
     */
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    /**
     * 输出目录
     */
    @Parameter(defaultValue = "${project.build.directory}", required = true)
    private File outputDirectory;

    /**
     * 应用类型（透传给 app-info.json 生成器）：ARK_SLIM / SPRING_BOOT_FAT_JAR / BASIC
     */
    @Parameter(defaultValue = "ARK_SLIM", required = false)
    private String appType;

    /**
     * 资源目录列表（透传给 app-info.json 生成器）：相对 bundle 根目录，如 resources/runtimes、resources/static
     */
    @Parameter(required = false)
    private java.util.List<String> appResources;

    /**
     * 启动参数模板（透传给 app-info.json 生成器）：支持 {app.home} 占位符
     */
    @Parameter(required = false)
    private String launchArgsTemplate;

    /**
     * BASIC 类型应用的启动命令模板（透传给 descriptor 生成器）
     */
    @Parameter(required = false)
    private String basicLaunchCommand;

    /**
     * BASIC 类型应用的停止命令模板（透传给 descriptor 生成器）
     */
    @Parameter(required = false)
    private String basicStopCommand;

    public void execute() throws MojoExecutionException, MojoFailureException {
        getLog().info("开始打包瘦JAR...");

        try {
            // 确保输出目录存在
            if (!outputDirectory.exists()) {
                outputDirectory.mkdirs();
            }

            // 生成依赖描述文件
            AppInfoFileGeneratorMojo xmlGenerator = new AppInfoFileGeneratorMojo();
            xmlGenerator.setLog(getLog());
            xmlGenerator.project = this.project;
            xmlGenerator.outputDirectory = this.outputDirectory;
            xmlGenerator.appType = this.appType;
            xmlGenerator.appResources = this.appResources;
            xmlGenerator.launchArgsTemplate = this.launchArgsTemplate;
            xmlGenerator.basicLaunchCommand = this.basicLaunchCommand;
            xmlGenerator.basicStopCommand = this.basicStopCommand;
            xmlGenerator.execute();

            // 主JAR：直接向 maven-jar-plugin 打出的原始JAR追加依赖描述文件，产物即原始JAR本身
            String originalJarName = project.getArtifactId() + "-" + project.getVersion() + ".jar";
            File originalJar = new File(outputDirectory, originalJarName);

            if (originalJar.exists()) {
                // 收集生成的描述文件（ZIP 无法原地追加，需一次性全量重写 JAR）
                java.util.Map<String, File> filesToAdd = new java.util.LinkedHashMap<>();
                File appInfoFile = new File(outputDirectory, "app-info.json");
                if (appInfoFile.exists()) {
                    filesToAdd.put("app-info.json", appInfoFile);
                }
                File launchArgsFile = new File(outputDirectory, ".runtime" + File.separator + "launch.args");
                if (launchArgsFile.exists()) {
                    filesToAdd.put(".runtime/launch.args", launchArgsFile);
                }

                // 一次性写入全部描述文件（仅一次全量重写 + 一次替换）
                addFilesToJar(originalJar, filesToAdd);
                getLog().info("描述文件已写入JAR: " + filesToAdd.size() + " 个");

                getLog().info("瘦JAR已生成: " + originalJar.getAbsolutePath());
            } else {
                throw new MojoExecutionException("原始JAR文件不存在: " + originalJar);
            }

        } catch (IOException e) {
            throw new MojoExecutionException("打包瘦JAR失败", e);
        } catch (Exception e) {
            throw new MojoExecutionException("生成依赖XML失败", e);
        }
    }

    private void addFilesToJar(File jarFile, java.util.Map<String, File> filesToAdd) throws IOException {
        if (filesToAdd == null || filesToAdd.isEmpty()) {
            return;
        }
        // 创建临时文件
        File tempFile = new File(jarFile.getAbsolutePath() + ".tmp");

        try (JarFile jar = new JarFile(jarFile);
             JarOutputStream jos = new JarOutputStream(new FileOutputStream(tempFile))) {

            // 复制原有的所有条目（跳过与待添加文件同名的旧条目，避免重复添加）
            java.util.Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (filesToAdd.containsKey(entry.getName())) {
                    continue;
                }
                jos.putNextEntry(new JarEntry(entry.getName()));
                try (InputStream is = jar.getInputStream(entry)) {
                    byte[] buffer = new byte[1024];
                    int bytesRead;
                    while ((bytesRead = is.read(buffer)) != -1) {
                        jos.write(buffer, 0, bytesRead);
                    }
                }
                jos.closeEntry();
            }

            // 一次性添加所有新文件
            for (java.util.Map.Entry<String, File> entry : filesToAdd.entrySet()) {
                jos.putNextEntry(new JarEntry(entry.getKey()));
                try (InputStream is = new FileInputStream(entry.getValue())) {
                    byte[] buffer = new byte[1024];
                    int bytesRead;
                    while ((bytesRead = is.read(buffer)) != -1) {
                        jos.write(buffer, 0, bytesRead);
                    }
                }
                jos.closeEntry();
            }
        }

        // 替换原文件（仅一次）
        Files.delete(jarFile.toPath());
        Files.move(tempFile.toPath(), jarFile.toPath());
    }
}
