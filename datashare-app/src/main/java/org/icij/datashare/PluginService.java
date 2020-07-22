package org.icij.datashare;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.ArchiveException;
import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.ArchiveStreamFactory;
import org.apache.commons.io.IOUtils;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import static java.util.Arrays.stream;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toSet;
import static org.apache.commons.io.FilenameUtils.getExtension;

public class PluginService {
    final Logger logger = LoggerFactory.getLogger(getClass());
    public static final String DEFAULT_PLUGIN_REGISTRY_FILENAME = "plugins.json";
    public static final String PLUGINS_BASE_URL = "/plugins";
    public static final String TMP_PREFIX = "tmp";

    private final Path pluginsDir;
    final PluginRegistry pluginRegistry;

    public PluginService() throws IOException {
       this(Paths.get(getCurrentDirPluginDirectory()));
    }

    public PluginService(PropertiesProvider propertiesProvider) throws IOException {
        this(Paths.get(propertiesProvider.get(PropertiesProvider.PLUGINS_DIR).orElse(getCurrentDirPluginDirectory())));
    }

    public PluginService(Path pluginsDir) throws IOException {
        this(pluginsDir, ClassLoader.getSystemResourceAsStream(DEFAULT_PLUGIN_REGISTRY_FILENAME));
    }

    PluginService(Path pluginsDir, InputStream inputStream) throws IOException {
        this.pluginsDir = pluginsDir;
        this.pluginRegistry = getPluginRegistry(inputStream);
    }

    public String addPlugins(String stringContent, List<String> userProjects) {
        File[] dirs = ofNullable(pluginsDir.toFile().listFiles(File::isDirectory)).
                orElseThrow(() -> new IllegalStateException("invalid path for plugins: " + pluginsDir));
        String scriptsString = stream(dirs).
                map(d -> projectFilter(d.toPath(), userProjects)).filter(Objects::nonNull).
                map(this::getPluginUrl).filter(Objects::nonNull).
                map(s -> "<script src=\"" + s + "\"></script>").collect(joining());
        String cssString = stream(dirs).
                map(d -> getCssPluginUrl(d.toPath())).filter(Objects::nonNull).
                map(s -> "<link rel=\"stylesheet\" href=\"" + s + "\">").collect(joining());
        return stringContent.
                replace("</body>", scriptsString + "</body>").
                replace("</head>", cssString + "</head>");
    }

    public Set<Plugin> list() { return list(".*");}

    public Set<Plugin> list(String patternString) {
        return pluginRegistry.get().stream().
                filter(p -> Pattern.compile(patternString).matcher(p.id).matches()).
                collect(toSet());
    }

    public void downloadAndInstall(String pluginId) throws IOException, ArchiveException {
        downloadAndInstall(pluginRegistry.get(pluginId).getDeliverableUrl());
    }

    public void downloadAndInstall(URL pluginUrl) throws IOException, ArchiveException {
        File pluginFile = download(pluginUrl);
        install(pluginFile);
    }

    String getPluginUrl(Path pluginDir) {
        Path packageJson = pluginDir.resolve("package.json");
        if (packageJson.toFile().isFile()) {
            Path pluginMain = getPluginProperty(packageJson, "main");
            if (pluginMain == null) return null;
            logger.info("detected plugin <{}> with package.json", pluginDir.getParent().relativize(pluginDir));
            return relativeToPlugins(pluginDir, pluginMain).toString();
        }
        Path indexJs = pluginDir.resolve("index.js");
        if (indexJs.toFile().isFile()) {
            logger.info("detected plugin <{}> with index.js", pluginDir.getParent().relativize(pluginDir));
            return relativeToPlugins(pluginDir, indexJs).toString();
        }
        return null;
    }

    Path projectFilter(Path pluginDir, List<String> projects) {
        try {
            Path packageJson = pluginDir.resolve("package.json");
            if (packageJson.toFile().isFile()) {
                Map<String, Object> packageMap = new ObjectMapper().readValue(packageJson.toFile(), new TypeReference<HashMap<String, Object>>() {});
                if (packageMap.containsKey("private")) {
                    if (!Boolean.parseBoolean((packageMap.get("private").toString()))) {
                        return pluginDir;
                    }
                    if (packageMap.containsKey("datashare")) {
                        LinkedHashMap datashareMap = (LinkedHashMap) packageMap.get("datashare");
                        List<String> pluginProjects = ofNullable((List<String>) datashareMap.get("projects")).orElse(Collections.emptyList());
                        return !Collections.disjoint(pluginProjects, projects) ? pluginDir : null;
                    }
                    return null;
                }
            }
            return pluginDir;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    String getCssPluginUrl(Path pluginDir) {
        Path packageJson = pluginDir.resolve("package.json");
        if (packageJson.toFile().isFile()) {
            Path pluginMain = getPluginProperty(packageJson, "style");
            if (pluginMain == null) return null;
            logger.info("detected css plugin <{}> with package.json", pluginDir.getParent().relativize(pluginDir));
            return relativeToPlugins(pluginDir, pluginMain).toString();
        }
        return null;
    }

    @NotNull
    File download(URL url) throws IOException {
        ReadableByteChannel readableByteChannel = Channels.newChannel(url.openStream());
        File tmpFile = Files.createTempFile(TMP_PREFIX, "." + getExtension(url.toString())).toFile();
        logger.info("downloading from url {}", url);
        try (FileOutputStream fileOutputStream = new FileOutputStream(tmpFile)) {
            fileOutputStream.getChannel().transferFrom(readableByteChannel, 0, Long.MAX_VALUE);
            return tmpFile;
        }
    }

    void install(File pluginFile) throws IOException, ArchiveException {
        logger.info("installing plugin from file {} into {}", pluginFile, pluginsDir);

        InputStream is = new BufferedInputStream(new FileInputStream(pluginFile));
        if (pluginFile.getName().endsWith("gz")) {
            is = new BufferedInputStream(new GZIPInputStream(is));
        }
        try (ArchiveInputStream zippedArchiveInputStream = new ArchiveStreamFactory().createArchiveInputStream(is)) {
            ArchiveEntry entry;
            while ((entry = zippedArchiveInputStream.getNextEntry()) != null) {
                final File outputFile = new File(pluginsDir.toFile(), entry.getName());
                if (entry.isDirectory()) {
                    if (!outputFile.exists()) {
                        if (!outputFile.mkdirs()) {
                            throw new IllegalStateException(String.format("Couldn't create directory %s.", outputFile.getAbsolutePath()));
                        }
                    }
                } else {
                    final OutputStream outputFileStream = new FileOutputStream(outputFile);
                    IOUtils.copy(zippedArchiveInputStream, outputFileStream);
                    outputFileStream.close();
                }
            }
        }
        if (pluginFile.getName().startsWith(TMP_PREFIX)) pluginFile.delete();
    }

    private Path relativeToPlugins(Path pluginDir, Path pluginMain) {
        return Paths.get(PLUGINS_BASE_URL).resolve(pluginDir.getParent().relativize(pluginMain));
    }

    private Path getPluginProperty(Path packageJson, String property) {
        try {
            Map<String, Object> packageJsonMap = new ObjectMapper().readValue(packageJson.toFile(), new TypeReference<HashMap<String, Object>>() {});
            String value = (String) packageJsonMap.get(property);
            return value == null ? null : packageJson.getParent().resolve(value);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private PluginRegistry getPluginRegistry(InputStream pluginJsonContent) throws IOException {
        return new ObjectMapper().readValue(pluginJsonContent, PluginRegistry.class);
    }

    @NotNull
    private static String getCurrentDirPluginDirectory() {
        return "." + PLUGINS_BASE_URL;
    }
}
