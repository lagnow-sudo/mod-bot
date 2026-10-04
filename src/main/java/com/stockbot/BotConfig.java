package com.stockbot;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

/** Typed access to config.properties with environment-variable overrides. */
public final class BotConfig {

    private final Properties props = new Properties();

    private BotConfig() {}

    public static BotConfig load(String path) throws IOException {
        BotConfig c = new BotConfig();
        Path p = Path.of(path);
        if (Files.exists(p)) {
            try (InputStream in = Files.newInputStream(p)) {
                c.props.load(in);
            }
        } else {
            try (InputStream in = BotConfig.class.getResourceAsStream("/config.properties")) {
                if (in == null) {
                    throw new IOException("config.properties not found at " + p.toAbsolutePath());
                }
                c.props.load(in);
            }
        }
        return c;
    }

    public String str(String key, String def) {
        String env = System.getenv(key.toUpperCase(Locale.ROOT).replace('.', '_'));
        if (env != null && !env.isBlank()) return env.trim();
        String v = props.getProperty(key);
        return (v == null || v.isBlank()) ? def : v.trim();
    }

    public int integer(String key, int def) {
        return Integer.parseInt(str(key, String.valueOf(def)));
    }

    public long lng(String key, long def) {
        return Long.parseLong(str(key, String.valueOf(def)));
    }

    public double dbl(String key, double def) {
        return Double.parseDouble(str(key, String.valueOf(def)));
    }

    public Set<String> csv(String key) {
        String v = str(key, "");
        if (v.isBlank()) return Set.of();
        return Arrays.stream(v.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }
}
