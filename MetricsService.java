package net.solostudio.skillgrind.utils;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import java.util.zip.GZIPOutputStream;

@SuppressWarnings("deprecation")
public final class MetricsService {
    private static final String CONFIG_PATH = "bStats/config.yml";
    private static final String API_ENDPOINT = "https://bStats.org/api/v2/data/bukkit";
    private static final String METRICS_VERSION = "3.2.0";

    private final Plugin plugin;
    private final MetricsConfig config;
    private final HttpClientService httpClient;
    private final MetricsScheduler scheduler;
    private final Set<MetricsChart> charts = ConcurrentHashMap.newKeySet();

    public MetricsService(Plugin plugin, int serviceId) {
        this.plugin = Objects.requireNonNull(plugin);
        this.config = new MetricsConfig(plugin);
        this.httpClient = new HttpClientService(config);
        this.scheduler = new MetricsScheduler(config);

        if (config.enabled()) {
            scheduler.schedule(this::collectAndSend);
            registerCoreCharts(serviceId);
        }
    }

    public void registerChart(MetricsChart chart) {
        charts.add(Objects.requireNonNull(chart));
    }

    public void shutdown() {
        scheduler.shutdown();
    }

    private void registerCoreCharts(int serviceId) {
        registerChart(new PlatformChart(serviceId));
        registerChart(new ServiceVersionChart(plugin));
    }

    private void collectAndSend() {
        try {
            JsonObject payload = new JsonObject()
                    .add("serverUUID", config.serverUUID())
                    .add("metricsVersion", METRICS_VERSION)
                    .add("platform", collectPlatformData())
                    .add("service", collectServiceData())
                    .add("customCharts", collectCharts());

            httpClient.post(payload)
                    .thenAccept(this::logResponse)
                    .exceptionally(this::handleError);

        } catch (Exception exception) {
            config.logger().log(Level.WARNING, "Metrics collection failed", exception);
        }
    }

    private JsonObject collectPlatformData() {
        return new JsonObject()
                .add("playerCount", Bukkit.getOnlinePlayers().size())
                .add("onlineMode", Bukkit.getOnlineMode())
                .add("bukkitVersion", Bukkit.getBukkitVersion())
                .add("osInfo", System.getProperty("os.name") + " " + System.getProperty("os.version"))
                .add("javaVersion", System.getProperty("java.version"))
                .add("coreCount", Runtime.getRuntime().availableProcessors());
    }

    private JsonObject collectServiceData() {
        return new JsonObject()
                .add("pluginVersion", plugin.getDescription().getVersion());
    }

    private JsonArray collectCharts() {
        return charts.stream()
                .map(chart -> chart.collect(config.logger()))
                .filter(Objects::nonNull)
                .collect(JsonArray.collector());
    }

    private void logResponse(HttpResponse<String> response) {
        if (config.logResponse()) {
            config.logger().info(() -> "Metrics response: %d %s".formatted(
                    response.statusCode(), response.body()));
        }
    }

    private @Nullable Void handleError(Throwable exception) {
        config.logger().log(Level.WARNING, "Metrics submission error", exception);
        return null;
    }

    private record MetricsConfig(
            boolean enabled,
            String serverUUID,
            boolean logResponse,
            boolean logData,
            Logger logger
    ) {
        MetricsConfig(Plugin plugin) {
            this(
                    loadBoolean(plugin, "enabled", true),
                    loadServerUUID(plugin),
                    loadBoolean(plugin, "logResponseStatusText", false),
                    loadBoolean(plugin, "logSentData", false),
                    plugin.getLogger()
            );
        }

        private static boolean loadBoolean(@NotNull Plugin plugin, String key, boolean def) {
            File configFile = new File(plugin.getDataFolder(), CONFIG_PATH);
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(configFile);
            return yaml.getBoolean(key, def);
        }

        private static String loadServerUUID(@NotNull Plugin plugin) {
            File configFile = new File(plugin.getDataFolder(), CONFIG_PATH);
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(configFile);

            if (!yaml.contains("serverUuid")) {
                yaml.set("serverUuid", UUID.randomUUID().toString());
                try {
                    yaml.save(configFile);
                } catch (IOException exception) {
                    plugin.getLogger().log(Level.WARNING, "Failed to save server UUID", exception);
                }
            }
            return yaml.getString("serverUuid");
        }
    }

    private static class MetricsScheduler {
        private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("metrics-scheduler-", 0).factory()
        );
        private final MetricsConfig config;

        MetricsScheduler(MetricsConfig config) {
            this.config = config;
        }

        void schedule(Runnable task) {
            long initialDelay = ThreadLocalRandom.current().nextLong(180_000, 360_000);
            long interval = 1_800_000; // 30 minutes

            executor.scheduleAtFixedRate(
                    () -> { if(config.enabled()) task.run(); },
                    initialDelay,
                    interval,
                    TimeUnit.MILLISECONDS
            );
        }

        void shutdown() {
            executor.close();
        }
    }

    private record HttpClientService(MetricsConfig config) {
            private static final HttpClient CLIENT = HttpClient.newHttpClient();

        CompletableFuture<HttpResponse<String>> post(JsonObject payload) {
                try {
                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create(API_ENDPOINT))
                            .header("Content-Encoding", "gzip")
                            .header("User-Agent", "MetricsService/" + METRICS_VERSION)
                            .POST(createBody(payload))
                            .build();

                    return CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString());

                } catch (Exception exception) {
                    return CompletableFuture.failedFuture(exception);
                }
            }

            private HttpRequest.@NotNull BodyPublisher createBody(JsonObject payload) throws IOException {
                if (config.logData()) {
                    config.logger().info(() -> "Sending metrics data:\n" + payload.toJson());
                }
                return HttpRequest.BodyPublishers.ofByteArray(compress(payload.toJson()));
            }

            private static byte @NotNull [] compress(@NotNull String data) throws IOException {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                try (GZIPOutputStream gzip = new GZIPOutputStream(buffer)) {
                    gzip.write(data.getBytes(StandardCharsets.UTF_8));
                }
                return buffer.toByteArray();
            }
        }

    public sealed interface JsonValue permits JsonObject, JsonArray, JsonPrimitive {
        String toJson();

        static JsonValue of(Object value) {
            if (value == null) return new JsonPrimitive(null);
            if (value instanceof JsonValue) return (JsonValue) value;
            if (value instanceof Map) return parseMap((Map<?, ?>) value);
            if (value instanceof Collection) return parseCollection((Collection<?>) value);
            return new JsonPrimitive(value);
        }

        private static @NotNull JsonObject parseMap(@NotNull Map<?, ?> map) {
            JsonObject obj = new JsonObject();
            map.forEach((k, v) -> obj.add(k.toString(), v));
            return obj;
        }

        private static @NotNull JsonArray parseCollection(@NotNull Collection<?> col) {
            JsonArray arr = new JsonArray();
            col.forEach(v -> arr.add(JsonValue.of(v)));
            return arr;
        }
    }

    public static final class JsonObject implements JsonValue {
        private final Map<String, JsonValue> fields = new LinkedHashMap<>();

        public JsonObject add(String key, Object value) {
            fields.put(key, JsonValue.of(value));
            return this;
        }

        @Override
        public String toJson() {
            return fields.entrySet().stream()
                    .map(e -> "\"%s\":%s".formatted(escape(e.getKey()), e.getValue().toJson()))
                    .collect(Collectors.joining(",", "{", "}"));
        }
    }

    public static final class JsonArray implements JsonValue {
        private final List<JsonValue> items = new ArrayList<>();

        public JsonArray add(Object value) {
            items.add(JsonValue.of(value));
            return this;
        }

        public static @NotNull Collector<JsonValue, ?, JsonArray> collector() {
            return Collector.of(
                    JsonArray::new,
                    JsonArray::add,
                    (left, right) -> { left.items.addAll(right.items); return left; }
            );
        }

        @Override
        public String toJson() {
            return items.stream()
                    .map(JsonValue::toJson)
                    .collect(Collectors.joining(",", "[", "]"));
        }
    }

    public record JsonPrimitive(Object value) implements JsonValue {
        @Override
        public String toJson() {
            return switch (value) {
                case null -> "null";
                case Boolean ignored -> value.toString();
                case Number ignored -> value.toString();
                default -> "\"%s\"".formatted(escape(value.toString()));
            };
        }
    }

    public sealed interface MetricsChart permits AbstractChart {
        JsonObject collect(Logger logger);
    }

    private static abstract non-sealed class AbstractChart implements MetricsChart {
        protected final String chartId;

        AbstractChart(String chartId) {
            this.chartId = Objects.requireNonNull(chartId);
        }

        @Override
        public JsonObject collect(Logger logger) {
            try {
                JsonValue data = collectData();
                return data != null ?
                        new JsonObject().add("chartId", chartId).add("data", data) :
                        null;
            } catch (Exception exception) {
                logger.log(Level.WARNING, "Failed to collect chart: " + chartId, exception);
                return null;
            }
        }

        protected abstract JsonValue collectData();
    }

    public static final class SimplePieChart extends AbstractChart {
        private final Supplier<String> valueSupplier;

        public SimplePieChart(String chartId, Supplier<String> valueSupplier) {
            super(chartId);
            this.valueSupplier = valueSupplier;
        }

        @Contract(" -> new")
        @Override
        protected @NotNull JsonValue collectData() {
            return new JsonPrimitive(valueSupplier.get());
        }
    }

    private static final class PlatformChart extends AbstractChart {
        private final int serviceId;

        PlatformChart(int serviceId) {
            super("platform");
            this.serviceId = serviceId;
        }

        @Override
        protected JsonValue collectData() {
            return new JsonObject()
                    .add("serviceId", serviceId)
                    .add("serverType", detectServerType());
        }

        private @NotNull String detectServerType() {
            try {
                Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
                return "Folia";
            } catch (ClassNotFoundException ignored) {
                return "Bukkit";
            }
        }
    }

    private static final class ServiceVersionChart extends AbstractChart {
        private final Plugin plugin;

        ServiceVersionChart(Plugin plugin) {
            super("serviceVersion");
            this.plugin = plugin;
        }

        @Contract(" -> new")
        @Override
        protected @NotNull JsonValue collectData() {
            return new JsonPrimitive(plugin.getDescription().getVersion());
        }
    }

    private static @NotNull String escape(@NotNull String input) {
        return input.chars()
                .collect(StringBuilder::new, (sb, c) -> {
                    switch (c) {
                        case '"' -> sb.append("\\\"");
                        case '\\' -> sb.append("\\\\");
                        case '\b' -> sb.append("\\b");
                        case '\f' -> sb.append("\\f");
                        case '\n' -> sb.append("\\n");
                        case '\r' -> sb.append("\\r");
                        case '\t' -> sb.append("\\t");
                        default -> {
                            if (c <= 0x1F) sb.append("\\u%04x".formatted(c));
                            else sb.append((char) c);
                        }
                    }
                }, StringBuilder::append)
                .toString();
    }
}