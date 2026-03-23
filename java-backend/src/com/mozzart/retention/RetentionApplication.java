package com.mozzart.retention;

import com.mozzart.retention.RetentionDomain.ActivityEvent;
import com.mozzart.retention.RetentionDomain.ExperimentSummaryRow;
import com.mozzart.retention.RetentionDomain.Intervention;
import com.mozzart.retention.RetentionDomain.Kpis;
import com.mozzart.retention.RetentionDomain.Player;
import com.mozzart.retention.RetentionDomain.UpdatePatch;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RetentionApplication {
  private static final Pattern PLAYER_ACTIVITY_PATH =
      Pattern.compile("^/api/players/([^/]+)/activity$");
  private static final Map<String, String> MIME_TYPES =
      Map.of(
          ".css", "text/css; charset=utf-8",
          ".html", "text/html; charset=utf-8",
          ".js", "text/javascript; charset=utf-8",
          ".json", "application/json; charset=utf-8",
          ".png", "image/png",
          ".svg", "image/svg+xml",
          ".webp", "image/webp");

  private static final Object STATE_LOCK = new Object();
  private static final CopyOnWriteArrayList<SseClient> STREAM_CLIENTS = new CopyOnWriteArrayList<>();
  private static final ScheduledExecutorService SCHEDULER = Executors.newScheduledThreadPool(2);
  private static final Random RANDOM = new Random();

  private static Path projectRoot;
  private static Path distRoot;
  private static Map<String, String> envFile = Map.of();
  private static HttpServer server;
  private static PlayerStore playerStore;
  private static List<Player> players = new ArrayList<>();
  private static String lastUpdatedAt;
  private static String persistenceMode = "memory";
  private static String apiHost = "127.0.0.1";
  private static int apiPort = 8787;
  private static long streamIntervalMs = 2200;

  private RetentionApplication() {}

  public static void main(String[] args) throws Exception {
    projectRoot = Path.of("").toAbsolutePath().normalize();
    distRoot = projectRoot.resolve("dist");
    envFile = loadEnvFile(projectRoot.resolve(".env"));

    apiHost = env("RETENTION_API_HOST", "127.0.0.1");
    apiPort = parseInt(env("RETENTION_API_PORT", "8787"), 8787);
    streamIntervalMs = parseLong(env("RETENTION_STREAM_MS", "2200"), 2200L);

    playerStore = createPlayerStore();
    persistenceMode = playerStore.mode();
    players = playerStore.listPlayers();
    lastUpdatedAt = playerStore.lastUpdatedAt();

    server = HttpServer.create(new InetSocketAddress(apiHost, apiPort), 0);
    server.createContext("/", new Router());
    server.setExecutor(Executors.newCachedThreadPool());
    server.start();

    startSimulation();
    startHeartbeats();

    Runtime.getRuntime().addShutdownHook(new Thread(RetentionApplication::shutdown));

    System.out.println(
        "Retention backend running on http://"
            + apiHost
            + ":"
            + apiPort
            + " (runtime: java, persistence: "
            + persistenceMode
            + ")");
  }

  private static void shutdown() {
    for (SseClient client : STREAM_CLIENTS) {
      client.closeQuietly();
    }
    STREAM_CLIENTS.clear();

    SCHEDULER.shutdownNow();
    if (server != null) {
      server.stop(0);
    }
    if (playerStore != null) {
      playerStore.close();
    }
  }

  private static void startSimulation() {
    SCHEDULER.scheduleAtFixedRate(
        () -> {
          try {
            UpdatePatch update;
            synchronized (STATE_LOCK) {
              update = RetentionDomain.randomUpdate(players, RANDOM);
            }
            if (update == null) {
              return;
            }

            Map<String, Object> event = applyUpdateInternal(update, "simulation");
            if (event != null) {
              broadcast(event);
            }
          } catch (Exception error) {
            System.err.println("Simulation update failed: " + error.getMessage());
          }
        },
        streamIntervalMs,
        streamIntervalMs,
        TimeUnit.MILLISECONDS);
  }

  private static void startHeartbeats() {
    SCHEDULER.scheduleAtFixedRate(
        () -> {
          for (SseClient client : STREAM_CLIENTS) {
            try {
              client.comment("keep-alive");
            } catch (IOException error) {
              STREAM_CLIENTS.remove(client);
              client.closeQuietly();
            }
          }
        },
        15,
        15,
        TimeUnit.SECONDS);
  }

  private static Map<String, Object> applyUpdateInternal(UpdatePatch update, String source) {
    synchronized (STATE_LOCK) {
      int index = indexOfPlayer(update.playerId);
      if (index < 0) {
        return null;
      }

      Player updatedPlayer = RetentionDomain.applyActivityUpdate(players.get(index), update);
      List<Player> nextPlayers = new ArrayList<>(players);
      nextPlayers.set(index, updatedPlayer);
      players = nextPlayers;
      lastUpdatedAt = Instant.now().toString();

      playerStore.upsertPlayer(updatedPlayer);
      playerStore.recordActivityEvent(update.playerId, source, update.toMap(), lastUpdatedAt);
      playerStore.setLastUpdatedAt(lastUpdatedAt);

      Map<String, Object> event = new LinkedHashMap<>();
      event.put("type", "activity");
      event.put("update", update.toMap());
      event.put("source", source);
      event.put("lastUpdatedAt", lastUpdatedAt);
      return event;
    }
  }

  private static int indexOfPlayer(String playerId) {
    for (int index = 0; index < players.size(); index += 1) {
      if (Objects.equals(players.get(index).id, playerId)) {
        return index;
      }
    }
    return -1;
  }

  private static void broadcast(Map<String, Object> payload) {
    for (SseClient client : STREAM_CLIENTS) {
      try {
        client.send(payload);
      } catch (IOException error) {
        STREAM_CLIENTS.remove(client);
        client.closeQuietly();
      }
    }
  }

  private static Map<String, Object> snapshotPayload() {
    List<Player> playerSnapshot;
    String updatedAt;
    synchronized (STATE_LOCK) {
      playerSnapshot = RetentionDomain.copyPlayers(players);
      updatedAt = lastUpdatedAt;
    }

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("players", playerMaps(playerSnapshot));
    payload.put("interventions", interventionMaps(topInterventions(playerSnapshot, 10)));
    payload.put("kpis", RetentionDomain.buildKpis(playerSnapshot).toMap());
    payload.put("experimentSummary", experimentSummaryMaps(RetentionDomain.buildExperimentSummary(playerSnapshot)));
    payload.put("lastUpdatedAt", updatedAt);
    payload.put("persistenceMode", persistenceMode);
    return payload;
  }

  private static List<Intervention> topInterventions(List<Player> playerSnapshot, int limit) {
    List<Intervention> interventions = RetentionDomain.buildInterventions(playerSnapshot);
    return interventions.subList(0, Math.min(limit, interventions.size()));
  }

  private static List<Map<String, Object>> playerMaps(List<Player> playerSnapshot) {
    List<Map<String, Object>> payload = new ArrayList<>();
    for (Player player : playerSnapshot) {
      payload.add(player.toMap());
    }
    return payload;
  }

  private static List<Map<String, Object>> interventionMaps(List<Intervention> interventions) {
    List<Map<String, Object>> payload = new ArrayList<>();
    for (Intervention item : interventions) {
      payload.add(item.toMap());
    }
    return payload;
  }

  private static List<Map<String, Object>> experimentSummaryMaps(List<ExperimentSummaryRow> rows) {
    List<Map<String, Object>> payload = new ArrayList<>();
    for (ExperimentSummaryRow row : rows) {
      payload.add(row.toMap());
    }
    return payload;
  }

  private static List<Map<String, Object>> activityEventMaps(List<ActivityEvent> events) {
    List<Map<String, Object>> payload = new ArrayList<>();
    for (ActivityEvent event : events) {
      payload.add(event.toMap());
    }
    return payload;
  }

  private static void sendJson(HttpExchange exchange, int statusCode, Object payload) throws IOException {
    byte[] body = JsonSupport.stringify(payload).getBytes(StandardCharsets.UTF_8);
    Headers headers = exchange.getResponseHeaders();
    setCorsHeaders(headers);
    headers.set("Content-Type", "application/json; charset=utf-8");
    exchange.sendResponseHeaders(statusCode, body.length);
    try (OutputStream responseBody = exchange.getResponseBody()) {
      responseBody.write(body);
    }
  }

  private static void sendStatic(HttpExchange exchange, int statusCode, byte[] body, String contentType)
      throws IOException {
    Headers headers = exchange.getResponseHeaders();
    headers.set("Content-Type", contentType);
    exchange.sendResponseHeaders(statusCode, body.length);
    try (OutputStream responseBody = exchange.getResponseBody()) {
      responseBody.write(body);
    }
  }

  private static void sendNoContent(HttpExchange exchange) throws IOException {
    Headers headers = exchange.getResponseHeaders();
    setCorsHeaders(headers);
    exchange.sendResponseHeaders(204, -1);
    exchange.close();
  }

  private static void setCorsHeaders(Headers headers) {
    headers.set("Access-Control-Allow-Origin", "*");
    headers.set("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
    headers.set("Access-Control-Allow-Headers", "Content-Type");
  }

  private static String readBody(HttpExchange exchange) throws IOException {
    try (InputStream body = exchange.getRequestBody()) {
      return new String(body.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static void openSse(HttpExchange exchange) throws IOException {
    Headers headers = exchange.getResponseHeaders();
    setCorsHeaders(headers);
    headers.set("Content-Type", "text/event-stream; charset=utf-8");
    headers.set("Cache-Control", "no-cache, no-transform");
    headers.set("Connection", "keep-alive");
    exchange.sendResponseHeaders(200, 0);

    SseClient client = new SseClient(exchange);
    STREAM_CLIENTS.add(client);
    try {
      client.send(snapshotEventPayload());
    } catch (IOException error) {
      STREAM_CLIENTS.remove(client);
      client.closeQuietly();
      throw error;
    }
  }

  private static Map<String, Object> snapshotEventPayload() {
    List<Player> playerSnapshot;
    String updatedAt;
    synchronized (STATE_LOCK) {
      playerSnapshot = RetentionDomain.copyPlayers(players);
      updatedAt = lastUpdatedAt;
    }

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("type", "snapshot");
    payload.put("players", playerMaps(playerSnapshot));
    payload.put("lastUpdatedAt", updatedAt);
    payload.put("persistenceMode", persistenceMode);
    return payload;
  }

  private static boolean serveStaticAsset(HttpExchange exchange, String path) throws IOException {
    String normalizedPath = "/".equals(path) ? "/index.html" : path;
    String decoded = URLDecoder.decode(normalizedPath, StandardCharsets.UTF_8);
    String relativePath = decoded.startsWith("/") ? decoded.substring(1) : decoded;
    Path target = distRoot.resolve(relativePath).normalize();

    if (!target.startsWith(distRoot) && !target.equals(distRoot.resolve("index.html"))) {
      sendJson(exchange, 403, Map.of("error", "forbidden"));
      return true;
    }

    if (Files.isRegularFile(target)) {
      sendStatic(exchange, 200, Files.readAllBytes(target), mimeType(target));
      return true;
    }

    Path indexPath = distRoot.resolve("index.html");
    if (!Files.isRegularFile(indexPath)) {
      sendJson(
          exchange,
          404,
          Map.of(
              "error", "frontend-not-built",
              "hint", "Run `npm run build` before `npm run start`."));
      return true;
    }

    sendStatic(exchange, 200, Files.readAllBytes(indexPath), "text/html; charset=utf-8");
    return true;
  }

  private static String mimeType(Path path) {
    String fileName = path.getFileName().toString();
    int extensionIndex = fileName.lastIndexOf('.');
    if (extensionIndex < 0) {
      return "application/octet-stream";
    }
    return MIME_TYPES.getOrDefault(fileName.substring(extensionIndex), "application/octet-stream");
  }

  private static Map<String, String> loadEnvFile(Path envPath) throws IOException {
    if (!Files.isRegularFile(envPath)) {
      return Map.of();
    }

    List<String> lines = Files.readAllLines(envPath, StandardCharsets.UTF_8);
    Map<String, String> values = new LinkedHashMap<>();
    for (String rawLine : lines) {
      String line = rawLine.trim();
      if (line.isEmpty() || line.startsWith("#")) {
        continue;
      }

      int separatorIndex = line.indexOf('=');
      if (separatorIndex < 1) {
        continue;
      }

      String key = line.substring(0, separatorIndex).trim();
      String value = line.substring(separatorIndex + 1).trim();
      if ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))) {
        value = value.substring(1, value.length() - 1);
      }
      values.put(key, value);
    }
    return values;
  }

  private static String env(String key, String fallback) {
    String systemValue = System.getenv(key);
    if (systemValue != null && !systemValue.isBlank()) {
      return systemValue;
    }
    String fileValue = envFile.get(key);
    if (fileValue != null && !fileValue.isBlank()) {
      return fileValue;
    }
    return fallback;
  }

  private static int parseInt(String value, int fallback) {
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException ignored) {
      return fallback;
    }
  }

  private static long parseLong(String value, long fallback) {
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException ignored) {
      return fallback;
    }
  }

  private static long parseLong(Object value, long fallback) {
    if (value == null) {
      return fallback;
    }
    if (value instanceof Number number) {
      return number.longValue();
    }
    return parseLong(String.valueOf(value), fallback);
  }

  private static int parseLimit(String value, int fallback) {
    int parsed = parseInt(value, fallback);
    return Math.max(1, Math.min(200, parsed));
  }

  private static Map<String, String> queryParams(URI uri) {
    Map<String, String> params = new LinkedHashMap<>();
    String rawQuery = uri.getRawQuery();
    if (rawQuery == null || rawQuery.isBlank()) {
      return params;
    }

    for (String pair : rawQuery.split("&")) {
      int separatorIndex = pair.indexOf('=');
      if (separatorIndex < 0) {
        params.put(URLDecoder.decode(pair, StandardCharsets.UTF_8), "");
        continue;
      }
      params.put(
          URLDecoder.decode(pair.substring(0, separatorIndex), StandardCharsets.UTF_8),
          URLDecoder.decode(pair.substring(separatorIndex + 1), StandardCharsets.UTF_8));
    }
    return params;
  }

  private static PlayerStore createPlayerStore() throws IOException {
    String databaseUrl = env("DATABASE_URL", "");
    if (!databaseUrl.isBlank()) {
      System.out.println(
          "DATABASE_URL is set, but the bundled Java backend runs without a PostgreSQL driver. "
              + "Falling back to local file persistence.");
    }

    String persistence = env("RETENTION_PERSISTENCE", "file").toLowerCase(Locale.ROOT);
    if ("memory".equals(persistence)) {
      return new MemoryPlayerStore();
    }

    String configuredPath = env("RETENTION_DATA_FILE", ".retention-java-backend.json");
    Path dataFile =
        Path.of(configuredPath).isAbsolute()
            ? Path.of(configuredPath)
            : projectRoot.resolve(configuredPath).normalize();
    return new FilePlayerStore(dataFile);
  }

  private interface PlayerStore {
    List<Player> listPlayers();

    void upsertPlayer(Player player);

    ActivityEvent recordActivityEvent(
        String playerId, String source, Map<String, Object> update, String createdAt);

    List<ActivityEvent> listActivityEvents(int limit);

    String mode();

    String lastUpdatedAt();

    void setLastUpdatedAt(String value);

    void close();
  }

  private static final class MemoryPlayerStore implements PlayerStore {
    private List<Player> storedPlayers = RetentionDomain.enrichPlayers(RetentionDomain.seedPlayers());
    private List<ActivityEvent> events = new ArrayList<>();
    private long nextEventId = 1;
    private String updatedAt;

    @Override
    public synchronized List<Player> listPlayers() {
      return RetentionDomain.copyPlayers(storedPlayers);
    }

    @Override
    public synchronized void upsertPlayer(Player player) {
      List<Player> next = new ArrayList<>(storedPlayers);
      boolean replaced = false;
      for (int index = 0; index < next.size(); index += 1) {
        if (Objects.equals(next.get(index).id, player.id)) {
          next.set(index, player.copy());
          replaced = true;
          break;
        }
      }
      if (!replaced) {
        next.add(player.copy());
      }
      storedPlayers = next;
    }

    @Override
    public synchronized ActivityEvent recordActivityEvent(
        String playerId, String source, Map<String, Object> update, String createdAt) {
      ActivityEvent event = new ActivityEvent(nextEventId++, playerId, source, new LinkedHashMap<>(update), createdAt);
      List<ActivityEvent> next = new ArrayList<>();
      next.add(event);
      next.addAll(events);
      events = next;
      return event;
    }

    @Override
    public synchronized List<ActivityEvent> listActivityEvents(int limit) {
      return new ArrayList<>(events.subList(0, Math.min(limit, events.size())));
    }

    @Override
    public String mode() {
      return "memory";
    }

    @Override
    public synchronized String lastUpdatedAt() {
      return updatedAt;
    }

    @Override
    public synchronized void setLastUpdatedAt(String value) {
      updatedAt = value;
    }

    @Override
    public void close() {}
  }

  private static final class FilePlayerStore implements PlayerStore {
    private final Path dataFile;
    private List<Player> storedPlayers;
    private List<ActivityEvent> events;
    private long nextEventId;
    private String updatedAt;

    private FilePlayerStore(Path dataFile) throws IOException {
      this.dataFile = dataFile;
      if (Files.isRegularFile(dataFile)) {
        load();
      } else {
        storedPlayers = RetentionDomain.enrichPlayers(RetentionDomain.seedPlayers());
        events = new ArrayList<>();
        nextEventId = 1;
        updatedAt = null;
        persist();
      }
    }

    @Override
    public synchronized List<Player> listPlayers() {
      return RetentionDomain.copyPlayers(storedPlayers);
    }

    @Override
    public synchronized void upsertPlayer(Player player) {
      List<Player> next = new ArrayList<>(storedPlayers);
      boolean replaced = false;
      for (int index = 0; index < next.size(); index += 1) {
        if (Objects.equals(next.get(index).id, player.id)) {
          next.set(index, player.copy());
          replaced = true;
          break;
        }
      }
      if (!replaced) {
        next.add(player.copy());
      }
      storedPlayers = next;
      persistQuietly();
    }

    @Override
    public synchronized ActivityEvent recordActivityEvent(
        String playerId, String source, Map<String, Object> update, String createdAt) {
      ActivityEvent event = new ActivityEvent(nextEventId++, playerId, source, new LinkedHashMap<>(update), createdAt);
      List<ActivityEvent> next = new ArrayList<>();
      next.add(event);
      next.addAll(events);
      events = next;
      persistQuietly();
      return event;
    }

    @Override
    public synchronized List<ActivityEvent> listActivityEvents(int limit) {
      return new ArrayList<>(events.subList(0, Math.min(limit, events.size())));
    }

    @Override
    public String mode() {
      return "file";
    }

    @Override
    public synchronized String lastUpdatedAt() {
      return updatedAt;
    }

    @Override
    public synchronized void setLastUpdatedAt(String value) {
      updatedAt = value;
      persistQuietly();
    }

    @Override
    public void close() {}

    @SuppressWarnings("unchecked")
    private void load() throws IOException {
      String content = Files.readString(dataFile, StandardCharsets.UTF_8);
      Object parsed = JsonSupport.parse(content);
      if (!(parsed instanceof Map<?, ?> rootMap)) {
        throw new IOException("Invalid persisted backend state");
      }

      Map<String, Object> root = (Map<String, Object>) rootMap;
      storedPlayers = new ArrayList<>();
      if (root.get("players") instanceof Collection<?> savedPlayers) {
        for (Object item : savedPlayers) {
          if (item instanceof Map<?, ?> playerMap) {
            storedPlayers.add(Player.fromMap((Map<String, Object>) playerMap));
          }
        }
      }
      if (storedPlayers.isEmpty()) {
        storedPlayers = RetentionDomain.enrichPlayers(RetentionDomain.seedPlayers());
      }

      events = new ArrayList<>();
      if (root.get("activityEvents") instanceof Collection<?> savedEvents) {
        for (Object item : savedEvents) {
          if (item instanceof Map<?, ?> eventMap) {
            events.add(ActivityEvent.fromMap((Map<String, Object>) eventMap));
          }
        }
      }

      nextEventId = parseLong(root.get("nextEventId"), events.size() + 1L);
      updatedAt = root.get("lastUpdatedAt") == null ? null : String.valueOf(root.get("lastUpdatedAt"));
    }

    private void persistQuietly() {
      try {
        persist();
      } catch (IOException error) {
        throw new IllegalStateException("Failed to persist backend state", error);
      }
    }

    private void persist() throws IOException {
      if (dataFile.getParent() != null) {
        Files.createDirectories(dataFile.getParent());
      }

      Map<String, Object> root = new LinkedHashMap<>();
      root.put("players", playerMaps(storedPlayers));
      root.put("activityEvents", activityEventMaps(events));
      root.put("nextEventId", nextEventId);
      root.put("lastUpdatedAt", updatedAt);

      Path tempFile = dataFile.resolveSibling(dataFile.getFileName().toString() + ".tmp");
      Files.writeString(tempFile, JsonSupport.stringify(root), StandardCharsets.UTF_8);
      try {
        Files.move(tempFile, dataFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException ignored) {
        Files.move(tempFile, dataFile, StandardCopyOption.REPLACE_EXISTING);
      }
    }
  }

  private static final class Router implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      try {
        route(exchange);
      } catch (Exception error) {
        sendJson(
            exchange,
            500,
            Map.of(
                "error", "internal-server-error",
                "message", error.getMessage() == null ? "unknown-error" : error.getMessage()));
      }
    }

    private void route(HttpExchange exchange) throws IOException {
      String method = exchange.getRequestMethod();
      String path = exchange.getRequestURI().getPath();

      if ("OPTIONS".equalsIgnoreCase(method)) {
        sendNoContent(exchange);
        return;
      }

      if ("GET".equalsIgnoreCase(method) && "/api/health".equals(path)) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", "ok");
        payload.put("service", "mozzart-retention-engine-backend");
        payload.put("runtime", "java");
        payload.put("persistenceMode", persistenceMode);
        payload.put("players", players.size());
        payload.put("now", Instant.now().toString());
        sendJson(exchange, 200, payload);
        return;
      }

      if ("GET".equalsIgnoreCase(method) && "/api/state".equals(path)) {
        sendJson(exchange, 200, snapshotPayload());
        return;
      }

      if ("GET".equalsIgnoreCase(method) && "/api/players".equals(path)) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("players", snapshotPayload().get("players"));
        payload.put("lastUpdatedAt", lastUpdatedAt);
        payload.put("persistenceMode", persistenceMode);
        sendJson(exchange, 200, payload);
        return;
      }

      if ("GET".equalsIgnoreCase(method) && "/api/interventions".equals(path)) {
        List<Player> playerSnapshot;
        synchronized (STATE_LOCK) {
          playerSnapshot = RetentionDomain.copyPlayers(players);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("interventions", interventionMaps(topInterventions(playerSnapshot, 10)));
        payload.put("lastUpdatedAt", lastUpdatedAt);
        payload.put("persistenceMode", persistenceMode);
        sendJson(exchange, 200, payload);
        return;
      }

      if ("GET".equalsIgnoreCase(method) && "/api/activity".equals(path)) {
        int limit = parseLimit(queryParams(exchange.getRequestURI()).getOrDefault("limit", "50"), 50);
        List<ActivityEvent> events = playerStore.listActivityEvents(limit);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("events", activityEventMaps(events));
        payload.put("count", events.size());
        payload.put("persistenceMode", persistenceMode);
        sendJson(exchange, 200, payload);
        return;
      }

      Matcher playerActivityMatcher = PLAYER_ACTIVITY_PATH.matcher(path);
      if ("POST".equalsIgnoreCase(method) && playerActivityMatcher.matches()) {
        String rawBody = readBody(exchange);
        Object parsedBody = rawBody.isBlank() ? Map.of() : JsonSupport.parse(rawBody);
        UpdatePatch update = UpdatePatch.fromObject(parsedBody);
        update.playerId = URLDecoder.decode(playerActivityMatcher.group(1), StandardCharsets.UTF_8);

        try {
          Map<String, Object> event = applyUpdateInternal(update, "api");
          if (event == null) {
            sendJson(exchange, 404, Map.of("error", "player-not-found"));
            return;
          }

          broadcast(event);
          sendJson(exchange, 200, Map.of("ok", true, "event", event));
          return;
        } catch (RuntimeException error) {
          sendJson(
              exchange,
              500,
              Map.of(
                  "error", "update-persistence-failed",
                  "message", error.getMessage() == null ? "unknown-error" : error.getMessage()));
          return;
        }
      }

      if ("GET".equalsIgnoreCase(method) && "/api/stream".equals(path)) {
        openSse(exchange);
        return;
      }

      if (path.startsWith("/api/")) {
        sendJson(exchange, 404, Map.of("error", "not-found"));
        return;
      }

      serveStaticAsset(exchange, path);
    }
  }

  private static final class SseClient {
    private final HttpExchange exchange;
    private final OutputStream body;

    private SseClient(HttpExchange exchange) {
      this.exchange = exchange;
      this.body = exchange.getResponseBody();
    }

    private synchronized void send(Map<String, Object> payload) throws IOException {
      String chunk = "data: " + JsonSupport.stringify(payload) + "\n\n";
      body.write(chunk.getBytes(StandardCharsets.UTF_8));
      body.flush();
    }

    private synchronized void comment(String text) throws IOException {
      String chunk = ":" + text + "\n\n";
      body.write(chunk.getBytes(StandardCharsets.UTF_8));
      body.flush();
    }

    private synchronized void closeQuietly() {
      try {
        body.close();
      } catch (IOException ignored) {
      }
      exchange.close();
    }
  }
}
