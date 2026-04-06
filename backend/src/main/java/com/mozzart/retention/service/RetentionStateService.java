package com.mozzart.retention.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mozzart.retention.RetentionDomain;
import com.mozzart.retention.RetentionDomain.ActivityEvent;
import com.mozzart.retention.RetentionDomain.ExperimentSummaryRow;
import com.mozzart.retention.RetentionDomain.Intervention;
import com.mozzart.retention.RetentionDomain.Kpis;
import com.mozzart.retention.RetentionDomain.Player;
import com.mozzart.retention.RetentionDomain.UpdatePatch;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class RetentionStateService {
  private final ObjectMapper objectMapper;
  private final String configuredPersistenceMode;
  private final Path dataFile;
  private final boolean microservicesEnabled;
  private final String riskServiceUrl;
  private final String crmServiceUrl;
  private final HttpClient httpClient = HttpClient.newHttpClient();
  private final Random random = new Random();
  private final Object stateLock = new Object();
  private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();

  private List<Player> players = new ArrayList<>();
  private List<ActivityEvent> activityEvents = new ArrayList<>();
  private String lastUpdatedAt;
  private long nextEventId = 1;
  private String persistenceMode;

  public RetentionStateService(
      ObjectMapper objectMapper,
      @Value("${retention.persistence:file}") String configuredPersistenceMode,
      @Value("${retention.data-file:../.retention-spring-backend.json}") String dataFilePath,
      @Value("${retention.microservices.enabled:false}") boolean microservicesEnabled,
      @Value("${retention.microservices.risk-url:http://127.0.0.1:8792}") String riskServiceUrl,
      @Value("${retention.microservices.crm-url:http://127.0.0.1:8793}") String crmServiceUrl) {
    this.objectMapper = objectMapper;
    this.configuredPersistenceMode = configuredPersistenceMode == null ? "file" : configuredPersistenceMode;
    this.dataFile = Path.of(dataFilePath).normalize().toAbsolutePath();
    this.microservicesEnabled = microservicesEnabled;
    this.riskServiceUrl = riskServiceUrl == null ? "http://127.0.0.1:8792" : riskServiceUrl;
    this.crmServiceUrl = crmServiceUrl == null ? "http://127.0.0.1:8793" : crmServiceUrl;
  }

  @PostConstruct
  void initialize() {
    synchronized (stateLock) {
      if ("memory".equalsIgnoreCase(configuredPersistenceMode)) {
        persistenceMode = "memory";
        seedFreshState();
        return;
      }

      persistenceMode = "file";
      try {
        loadFromDisk();
      } catch (IOException error) {
        seedFreshState();
        persistQuietly();
      }
    }
  }

  public Map<String, Object> healthPayload() {
    synchronized (stateLock) {
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("status", "ok");
      payload.put("service", "mozzart-retention-engine-backend");
      payload.put("runtime", "spring-boot");
      payload.put("persistenceMode", persistenceMode);
      payload.put("microservicesEnabled", microservicesEnabled);
      payload.put("riskServiceUrl", riskServiceUrl);
      payload.put("crmServiceUrl", crmServiceUrl);
      payload.put("players", players.size());
      payload.put("now", Instant.now().toString());
      return payload;
    }
  }

  public Map<String, Object> statePayload() {
    synchronized (stateLock) {
      return snapshotPayload();
    }
  }

  public Map<String, Object> playersPayload() {
    synchronized (stateLock) {
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("players", playerMaps(players));
      payload.put("lastUpdatedAt", lastUpdatedAt);
      payload.put("persistenceMode", persistenceMode);
      return payload;
    }
  }

  public Map<String, Object> interventionsPayload() {
    synchronized (stateLock) {
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("interventions", resolveInterventionMaps(players, 10));
      payload.put("lastUpdatedAt", lastUpdatedAt);
      payload.put("persistenceMode", persistenceMode);
      return payload;
    }
  }

  public Map<String, Object> activityPayload(int limit) {
    synchronized (stateLock) {
      int safeLimit = Math.max(1, Math.min(200, limit));
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("events", activityEventMaps(activityEvents.subList(0, Math.min(safeLimit, activityEvents.size()))));
      payload.put("count", Math.min(safeLimit, activityEvents.size()));
      payload.put("persistenceMode", persistenceMode);
      return payload;
    }
  }

  public Map<String, Object> applyActivityUpdate(String playerId, Map<String, Object> payload) {
    UpdatePatch patch = UpdatePatch.fromObject(payload == null ? Map.of() : payload);
    patch.playerId = playerId;
    Map<String, Object> event = applyUpdateInternal(patch, "api");
    if (event != null) {
      broadcast(event);
    }
    return event;
  }

  public SseEmitter openStream() {
    SseEmitter emitter = new SseEmitter(0L);
    emitters.add(emitter);
    emitter.onCompletion(() -> emitters.remove(emitter));
    emitter.onTimeout(() -> emitters.remove(emitter));
    emitter.onError((error) -> emitters.remove(emitter));

    try {
      emitter.send(SseEmitter.event().data(snapshotEventPayload(), MediaType.APPLICATION_JSON));
    } catch (IOException error) {
      emitters.remove(emitter);
      emitter.completeWithError(error);
    }

    return emitter;
  }

  @Scheduled(
      fixedDelayString = "${retention.stream-interval-ms:2200}",
      initialDelayString = "${retention.stream-interval-ms:2200}")
  public void simulateActivity() {
    Map<String, Object> event;
    synchronized (stateLock) {
      UpdatePatch patch = RetentionDomain.randomUpdate(players, random);
      if (patch == null) {
        return;
      }
      event = applyUpdateInternalLocked(patch, "simulation");
    }

    if (event != null) {
      broadcast(event);
    }
  }

  private Map<String, Object> applyUpdateInternal(UpdatePatch patch, String source) {
    synchronized (stateLock) {
      return applyUpdateInternalLocked(patch, source);
    }
  }

  private Map<String, Object> applyUpdateInternalLocked(UpdatePatch patch, String source) {
    int playerIndex = indexOfPlayer(patch.playerId);
    if (playerIndex < 0) {
      return null;
    }

    Player updatedPlayer = applyUpdateViaRiskService(players.get(playerIndex), patch);
    List<Player> nextPlayers = new ArrayList<>(players);
    nextPlayers.set(playerIndex, updatedPlayer);
    players = nextPlayers;
    lastUpdatedAt = Instant.now().toString();

    ActivityEvent eventRecord =
        new ActivityEvent(nextEventId++, patch.playerId, source, new LinkedHashMap<>(patch.toMap()), lastUpdatedAt);
    List<ActivityEvent> nextEvents = new ArrayList<>();
    nextEvents.add(eventRecord);
    nextEvents.addAll(activityEvents);
    activityEvents = nextEvents;

    persistQuietly();

    Map<String, Object> event = new LinkedHashMap<>();
    event.put("type", "activity");
    event.put("update", patch.toMap());
    event.put("source", source);
    event.put("lastUpdatedAt", lastUpdatedAt);
    return event;
  }

  private int indexOfPlayer(String playerId) {
    for (int index = 0; index < players.size(); index += 1) {
      if (Objects.equals(players.get(index).id, playerId)) {
        return index;
      }
    }
    return -1;
  }

  private void broadcast(Map<String, Object> payload) {
    for (SseEmitter emitter : emitters) {
      try {
        emitter.send(SseEmitter.event().data(payload, MediaType.APPLICATION_JSON));
      } catch (IOException error) {
        emitters.remove(emitter);
        emitter.completeWithError(error);
      }
    }
  }

  private Map<String, Object> snapshotPayload() {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("players", playerMaps(players));
    payload.put("interventions", resolveInterventionMaps(players, 10));
    Kpis kpis = RetentionDomain.buildKpis(players);
    payload.put("kpis", kpis.toMap());
    payload.put("experimentSummary", experimentSummaryMaps(RetentionDomain.buildExperimentSummary(players)));
    payload.put("lastUpdatedAt", lastUpdatedAt);
    payload.put("persistenceMode", persistenceMode);
    return payload;
  }

  private Map<String, Object> snapshotEventPayload() {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("type", "snapshot");
    payload.put("players", playerMaps(players));
    payload.put("lastUpdatedAt", lastUpdatedAt);
    payload.put("persistenceMode", persistenceMode);
    return payload;
  }

  private List<Intervention> topInterventions(List<Player> sourcePlayers, int limit) {
    List<Intervention> interventions = RetentionDomain.buildInterventions(sourcePlayers);
    return interventions.subList(0, Math.min(limit, interventions.size()));
  }

  private List<Map<String, Object>> playerMaps(List<Player> sourcePlayers) {
    List<Map<String, Object>> result = new ArrayList<>();
    for (Player player : sourcePlayers) {
      result.add(player.toMap());
    }
    return result;
  }

  private List<Map<String, Object>> interventionMaps(List<Intervention> interventions) {
    List<Map<String, Object>> result = new ArrayList<>();
    for (Intervention intervention : interventions) {
      result.add(intervention.toMap());
    }
    return result;
  }

  private List<Map<String, Object>> experimentSummaryMaps(List<ExperimentSummaryRow> rows) {
    List<Map<String, Object>> result = new ArrayList<>();
    for (ExperimentSummaryRow row : rows) {
      result.add(row.toMap());
    }
    return result;
  }

  private List<Map<String, Object>> activityEventMaps(List<ActivityEvent> events) {
    List<Map<String, Object>> result = new ArrayList<>();
    for (ActivityEvent event : events) {
      result.add(event.toMap());
    }
    return result;
  }

  private void seedFreshState() {
    players = enrichPlayersViaRiskService(RetentionDomain.seedPlayers());
    activityEvents = new ArrayList<>();
    nextEventId = 1;
    lastUpdatedAt = null;
  }

  private void persistQuietly() {
    if (!"file".equalsIgnoreCase(persistenceMode)) {
      return;
    }

    try {
      persistToDisk();
    } catch (IOException error) {
      throw new IllegalStateException("Failed to persist Spring backend state", error);
    }
  }

  private void persistToDisk() throws IOException {
    if (dataFile.getParent() != null) {
      Files.createDirectories(dataFile.getParent());
    }

    Map<String, Object> root = new LinkedHashMap<>();
    root.put("players", playerMaps(players));
    root.put("activityEvents", activityEventMaps(activityEvents));
    root.put("nextEventId", nextEventId);
    root.put("lastUpdatedAt", lastUpdatedAt);

    Path tempFile = dataFile.resolveSibling(dataFile.getFileName() + ".tmp");
    objectMapper.writerWithDefaultPrettyPrinter().writeValue(tempFile.toFile(), root);
    try {
      Files.move(tempFile, dataFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException ignored) {
      Files.move(tempFile, dataFile, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  @SuppressWarnings("unchecked")
  private void loadFromDisk() throws IOException {
    if (!Files.isRegularFile(dataFile)) {
      seedFreshState();
      persistToDisk();
      return;
    }

    Map<String, Object> root =
        objectMapper.readValue(dataFile.toFile(), new TypeReference<Map<String, Object>>() {});

    List<Player> loadedPlayers = new ArrayList<>();
    Object savedPlayers = root.get("players");
    if (savedPlayers instanceof Collection<?> collection) {
      for (Object item : collection) {
        if (item instanceof Map<?, ?> playerMap) {
          loadedPlayers.add(Player.fromMap((Map<String, Object>) playerMap));
        }
      }
    }

    players =
        loadedPlayers.isEmpty()
            ? enrichPlayersViaRiskService(RetentionDomain.seedPlayers())
            : enrichPlayersViaRiskService(loadedPlayers);

    List<ActivityEvent> loadedEvents = new ArrayList<>();
    Object savedEvents = root.get("activityEvents");
    if (savedEvents instanceof Collection<?> collection) {
      for (Object item : collection) {
        if (item instanceof Map<?, ?> eventMap) {
          loadedEvents.add(ActivityEvent.fromMap((Map<String, Object>) eventMap));
        }
      }
    }

    activityEvents = loadedEvents;
    Object nextEventIdValue = root.get("nextEventId");
    nextEventId =
        nextEventIdValue instanceof Number number ? number.longValue() : Math.max(1, activityEvents.size() + 1L);
    Object lastUpdatedAtValue = root.get("lastUpdatedAt");
    lastUpdatedAt = lastUpdatedAtValue == null ? null : String.valueOf(lastUpdatedAtValue);
  }

  private List<Player> enrichPlayersViaRiskService(List<Player> source) {
    if (!microservicesEnabled) {
      return RetentionDomain.enrichPlayers(source);
    }

    try {
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("players", playerMaps(source));
      Map<String, Object> response = postJson(riskServiceUrl + "/api/risk/enrich", payload);
      List<Map<String, Object>> enrichedMaps = listOfMaps(response, "players");
      if (enrichedMaps.isEmpty()) {
        return RetentionDomain.enrichPlayers(source);
      }
      List<Player> enriched = new ArrayList<>();
      for (Map<String, Object> map : enrichedMaps) {
        enriched.add(Player.fromMap(map));
      }
      return enriched;
    } catch (Exception error) {
      return RetentionDomain.enrichPlayers(source);
    }
  }

  private Player applyUpdateViaRiskService(Player current, UpdatePatch patch) {
    if (!microservicesEnabled) {
      return RetentionDomain.applyActivityUpdate(current, patch);
    }

    try {
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("player", current == null ? Map.of() : current.toMap());
      payload.put("update", patch == null ? Map.of() : patch.toMap());
      Map<String, Object> response = postJson(riskServiceUrl + "/api/risk/apply-update", payload);
      Map<String, Object> playerMap = mapValue(response, "player");
      if (playerMap.isEmpty()) {
        return RetentionDomain.applyActivityUpdate(current, patch);
      }
      return Player.fromMap(playerMap);
    } catch (Exception error) {
      return RetentionDomain.applyActivityUpdate(current, patch);
    }
  }

  private List<Map<String, Object>> resolveInterventionMaps(List<Player> sourcePlayers, int limit) {
    if (!microservicesEnabled) {
      return interventionMaps(topInterventions(sourcePlayers, limit));
    }

    try {
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("players", playerMaps(sourcePlayers));
      payload.put("limit", limit);
      Map<String, Object> response = postJson(crmServiceUrl + "/api/crm/interventions", payload);
      List<Map<String, Object>> interventions = listOfMaps(response, "interventions");
      if (!interventions.isEmpty()) {
        return interventions;
      }
      return interventionMaps(topInterventions(sourcePlayers, limit));
    } catch (Exception error) {
      return interventionMaps(topInterventions(sourcePlayers, limit));
    }
  }

  private Map<String, Object> postJson(String url, Map<String, Object> payload) throws IOException, InterruptedException {
    String body = objectMapper.writeValueAsString(payload == null ? Map.of() : payload);
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      throw new IOException("Service request failed with status " + response.statusCode());
    }
    return objectMapper.readValue(response.body(), new TypeReference<Map<String, Object>>() {});
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> listOfMaps(Map<String, Object> payload, String key) {
    if (payload == null) {
      return List.of();
    }
    Object value = payload.get(key);
    if (value instanceof List<?> listValue) {
      List<Map<String, Object>> result = new ArrayList<>();
      for (Object item : listValue) {
        if (item instanceof Map<?, ?> mapItem) {
          result.add(new LinkedHashMap<>((Map<String, Object>) mapItem));
        }
      }
      return result;
    }
    return List.of();
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> mapValue(Map<String, Object> payload, String key) {
    if (payload == null) {
      return Map.of();
    }
    Object value = payload.get(key);
    if (value instanceof Map<?, ?> mapValue) {
      return new LinkedHashMap<>((Map<String, Object>) mapValue);
    }
    return Map.of();
  }
}
