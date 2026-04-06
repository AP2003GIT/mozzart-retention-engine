package com.mozzart.crm.controller;

import com.mozzart.crm.RetentionDomain;
import com.mozzart.crm.RetentionDomain.Intervention;
import com.mozzart.crm.RetentionDomain.Player;
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
@RequestMapping("/api/crm")
public class CrmController {
  @GetMapping("/health")
  public Map<String, Object> health() {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("status", "ok");
    payload.put("service", "mozzart-crm-service");
    payload.put("runtime", "spring-boot");
    payload.put("now", Instant.now().toString());
    return payload;
  }

  @PostMapping("/interventions")
  public Map<String, Object> interventions(@RequestBody(required = false) Map<String, Object> payload) {
    List<Player> players = readPlayers(payload);
    int limit = intValue(payload == null ? null : payload.get("limit"), 10);
    List<Intervention> interventions = RetentionDomain.buildInterventions(players);

    List<Map<String, Object>> mapped = new ArrayList<>();
    int capped = Math.min(limit, interventions.size());
    for (int index = 0; index < capped; index += 1) {
      mapped.add(interventions.get(index).toMap());
    }

    Map<String, Object> response = new LinkedHashMap<>();
    response.put("interventions", mapped);
    response.put("count", mapped.size());
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

  private int intValue(Object value, int fallback) {
    if (value instanceof Number number) {
      return number.intValue();
    }
    if (value instanceof String text) {
      try {
        return Integer.parseInt(text);
      } catch (NumberFormatException ignored) {
        return fallback;
      }
    }
    return fallback;
  }
}
