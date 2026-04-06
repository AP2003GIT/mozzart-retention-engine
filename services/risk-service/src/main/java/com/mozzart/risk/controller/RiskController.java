package com.mozzart.risk.controller;

import com.mozzart.risk.RetentionDomain;
import com.mozzart.risk.RetentionDomain.Kpis;
import com.mozzart.risk.RetentionDomain.Player;
import com.mozzart.risk.RetentionDomain.UpdatePatch;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/risk")
public class RiskController {
  @GetMapping("/health")
  public Map<String, Object> health() {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("status", "ok");
    payload.put("service", "mozzart-risk-service");
    payload.put("runtime", "spring-boot");
    payload.put("now", Instant.now().toString());
    return payload;
  }

  @PostMapping("/enrich")
  public Map<String, Object> enrich(@RequestBody(required = false) Map<String, Object> payload) {
    List<Player> players = readPlayers(payload);
    List<Player> enriched = RetentionDomain.enrichPlayers(players);
    List<Map<String, Object>> result = new ArrayList<>();
    for (Player player : enriched) {
      result.add(player.toMap());
    }

    Map<String, Object> response = new LinkedHashMap<>();
    response.put("players", result);
    response.put("count", result.size());
    return response;
  }

  @PostMapping("/apply-update")
  public Map<String, Object> applyUpdate(@RequestBody(required = false) Map<String, Object> payload) {
    Map<String, Object> playerMap = mapValue(payload, "player");
    Map<String, Object> updateMap = mapValue(payload, "update");
    Player player = Player.fromMap(playerMap);
    UpdatePatch patch = UpdatePatch.fromObject(updateMap);
    Player updated = RetentionDomain.applyActivityUpdate(player, patch);

    Map<String, Object> response = new LinkedHashMap<>();
    response.put("player", updated == null ? null : updated.toMap());
    return response;
  }

  @PostMapping("/kpis")
  public Map<String, Object> kpis(@RequestBody(required = false) Map<String, Object> payload) {
    List<Player> players = readPlayers(payload);
    Kpis kpis = RetentionDomain.buildKpis(players);

    Map<String, Object> response = new LinkedHashMap<>();
    response.put("kpis", kpis.toMap());
    return response;
  }

  private List<Player> readPlayers(Map<String, Object> payload) {
    List<Map<String, Object>> playerMaps = listOfMaps(payload, "players");
    List<Player> players = new ArrayList<>();
    for (Map<String, Object> playerMap : playerMaps) {
      players.add(Player.fromMap(playerMap));
    }
    return players;
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
