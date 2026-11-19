package com.geno1024.ai.buildcount;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One {@code .properties} file per project, holding three dimensions:
 *
 * <pre>
 * total=42
 * module.app=30
 * day.2026-11-19=42
 * day.2026-11-19.module.app=30
 * </pre>
 *
 * {@code total == sum(module.*)} and {@code day.D == sum(day.D.module.*)}.
 * Writes hold a {@link FileLock} on the project file and publish via an atomic
 * rename, so readers never observe a half-written file and concurrent builds
 * cannot lose an increment.
 */
public final class CounterStore {

    public static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Shanghai");

    private static final Pattern DAY_KEY = Pattern.compile("day\\.(\\d{4}-\\d{2}-\\d{2})");

    private final Path dataDir;
    private final Clock clock;

    public CounterStore(Path dataDir) {
        this(dataDir, Clock.system(DEFAULT_ZONE));
    }

    public CounterStore(Path dataDir, Clock clock) {
        this.dataDir = dataDir;
        this.clock = clock;
        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create data directory " + dataDir, e);
        }
    }

    /** Records one build. Every call counts; retries and CI re-runs are intentional. */
    public long increment(String project, String module) {
        Path file = file(project);
        try {
            try (FileChannel channel = FileChannel.open(file,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {

                Properties props = load(project);
                String date = LocalDate.now(clock).toString();
                long total = number(props, "total") + 1;
                long moduleTotal = number(props, "module." + module) + 1;
                long dayTotal = number(props, "day." + date) + 1;
                long dayModule = number(props, "day." + date + ".module." + module) + 1;

                props.setProperty("total", Long.toString(total));
                props.setProperty("module." + module, Long.toString(moduleTotal));
                props.setProperty("day." + date, Long.toString(dayTotal));
                props.setProperty("day." + date + ".module." + module, Long.toString(dayModule));

                Path tmp = Files.createTempFile(dataDir, project + ".", ".tmp");
                try {
                    try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                        props.store(writer, null);
                    }
                    try {
                        Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE,
                                StandardCopyOption.REPLACE_EXISTING);
                    } catch (AtomicMoveNotSupportedException e) {
                        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                    }
                } finally {
                    Files.deleteIfExists(tmp);
                }
                return moduleTotal;
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to increment " + project + "/" + module, e);
        }
    }

    public List<String> projects() throws IOException {
        List<String> names = new ArrayList<>();
        if (!Files.isDirectory(dataDir)) return names;
        try (var stream = Files.list(dataDir)) {
            stream.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".properties"))
                    .map(n -> n.substring(0, n.length() - ".properties".length()))
                    .sorted()
                    .forEach(names::add);
        }
        return names;
    }

    public boolean hasProject(String project) {
        return Files.isRegularFile(file(project));
    }

    public long total(String project) throws IOException {
        return number(load(project), "total");
    }

    public List<String> modules(String project) throws IOException {
        List<String> names = new ArrayList<>();
        for (String key : load(project).stringPropertyNames()) {
            if (key.startsWith("module.")) names.add(key.substring("module.".length()));
        }
        Collections.sort(names);
        return names;
    }

    /** {@code null} when the module has never reported a build. */
    public Long moduleTotal(String project, String module) throws IOException {
        String value = load(project).getProperty("module." + module);
        return value == null ? null : parse(value, "module." + module);
    }

    /** Project totals by date, ascending. */
    public Map<String, Long> calendar(String project) throws IOException {
        Properties props = load(project);
        Map<String, Long> result = new TreeMap<>();
        for (String key : props.stringPropertyNames()) {
            Matcher matcher = DAY_KEY.matcher(key);
            if (matcher.matches()) result.put(matcher.group(1), parse(props.getProperty(key), key));
        }
        return result;
    }

    /** One module's totals by date, ascending. */
    public Map<String, Long> calendar(String project, String module) throws IOException {
        Pattern keyPattern = Pattern.compile(
                "day\\.(\\d{4}-\\d{2}-\\d{2})\\.module\\." + Pattern.quote(module));
        Properties props = load(project);
        Map<String, Long> result = new TreeMap<>();
        for (String key : props.stringPropertyNames()) {
            Matcher matcher = keyPattern.matcher(key);
            if (matcher.matches()) result.put(matcher.group(1), parse(props.getProperty(key), key));
        }
        return result;
    }

    private Path file(String project) {
        return dataDir.resolve(project + ".properties");
    }

    private Properties load(String project) throws IOException {
        Properties props = new Properties();
        Path file = file(project);
        if (Files.isRegularFile(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                props.load(reader);
            }
        }
        return props;
    }

    private static long number(Properties props, String key) {
        String value = props.getProperty(key);
        return value == null ? 0L : parse(value, key);
    }

    private static long parse(String value, String key) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Corrupt counter key " + key + " = " + value, e);
        }
    }
}
