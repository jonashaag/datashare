package org.icij.datashare;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static java.util.Arrays.stream;
import static java.util.Optional.ofNullable;
import static org.apache.commons.io.FilenameUtils.*;

public class Extension implements Deliverable {
    enum Type {NLP, WEB,PLUGIN}
    static Pattern endsWithVersion = Pattern.compile("([a-zA-Z\\-.]*)-([0-9.]*)$");
    public static final String TMP_PREFIX = "tmp";
    final Logger logger = LoggerFactory.getLogger(getClass());
    public final String id;
    public final String name;
    public final String description;
    public final URL url;
    public final String version;
    public Type type;

    @JsonCreator
    public Extension(@JsonProperty("id") String id,
                  @JsonProperty("name") String name,
                  @JsonProperty("version") String version,
                  @JsonProperty("description") String description,
                  @JsonProperty("url") URL url,
                  @JsonProperty("type") Type type){
        this.id = id;
        this.name = name;
        this.version = version;
        this.description = description;
        this.url = url;
        this.type = type;
    }

    public Extension(URL url){
        this.id = null;
        this.name = null;
        this.description = null;
        this.version = null;
        this.url = url;
        this.type = null;
    }

    @NotNull
    public File download() throws IOException {
        ReadableByteChannel readableByteChannel = Channels.newChannel(url.openStream());
        File tmpFile = Files.createTempFile(TMP_PREFIX, "." + getExtension(url.toString())).toFile();
        logger.info("downloading from url {}", url);
        try (FileOutputStream fileOutputStream = new FileOutputStream(tmpFile)) {
            fileOutputStream.getChannel().transferFrom(readableByteChannel, 0, Long.MAX_VALUE);
            return tmpFile;
        }
    }

    public void install(File extensionFile, Path extensionsDir) throws IOException {
        List<File> previousVersionInstalled = getPreviousVersionInstalled(extensionsDir, getBaseName(getFileName()));
        if (previousVersionInstalled.size() > 0) {
            logger.info("removing previous versions {}", previousVersionInstalled);
            previousVersionInstalled.forEach(File::delete);
        }
        Files.move(extensionFile.toPath(), extensionsDir.resolve(getFileName()));
    }

    static List<File> getPreviousVersionInstalled(Path extensionsDir, String baseName) {
        File[] jars = ofNullable(extensionsDir.toFile().listFiles((file, s) -> s.endsWith(".jar"))).orElse(new File[0]);
        return stream(jars).filter(f -> f.getName().startsWith(removeVersion(baseName)) && endsWithVersion.matcher(getBaseName(f.getName())).matches()).collect(Collectors.toList());
    }

    static String removeVersion(String baseName) {
        Matcher matcher = endsWithVersion.matcher(baseName);
        if (matcher.matches()) {
            return matcher.group(1);
        }
        return baseName;
    }

    @Override
    public void delete(Path installDir) throws IOException {
        Path extensionPath = installDir.resolve(getFileName());
        logger.info("removing extension {} jar {}", id, extensionPath);
        extensionPath.toFile().delete();
    }

    @Override public URL getUrl() { return url;}

    protected String getFileName() { return getName(url.getFile());}

    @Override public String getId() { return this.id;
    }

    @Override
    public String toString() {
        return "Extension id='" + id + '\'' + '\'' + ", version='" + version + '\'' + "url=" + url + '\'' + "type=" + type;
    }

    public void displayInformation() {
        System.out.println("extension " + id);
        System.out.println("\t" + name);
        System.out.println("\t" + version);
        System.out.println("\t" + url);
        System.out.println("\t" + description);
        System.out.println("\t" + type);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Plugin)) return false;
        Plugin plugin = (Plugin) o;
        return id.equals(plugin.id) &&
                version.equals(plugin.version);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, version);
    }
}
