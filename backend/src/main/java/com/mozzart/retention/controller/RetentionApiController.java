package com.mozzart.retention.controller;

import com.mozzart.retention.service.RetentionStateService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api")
public class RetentionApiController {
  private final RetentionStateService retentionStateService;

  public RetentionApiController(RetentionStateService retentionStateService) {
    this.retentionStateService = retentionStateService;
  }

  @GetMapping("/health")
  public Map<String, Object> health() {
    return retentionStateService.healthPayload();
  }

  @GetMapping("/state")
  public Map<String, Object> state() {
    return retentionStateService.statePayload();
  }

  @GetMapping("/players")
  public Map<String, Object> players() {
    return retentionStateService.playersPayload();
  }

  @GetMapping("/interventions")
  public Map<String, Object> interventions() {
    return retentionStateService.interventionsPayload();
  }

  @GetMapping("/activity")
  public Map<String, Object> activity(@RequestParam(name = "limit", defaultValue = "50") int limit) {
    return retentionStateService.activityPayload(limit);
  }

  @PostMapping("/players/{playerId}/activity")
  public ResponseEntity<Map<String, Object>> updatePlayerActivity(
      @PathVariable String playerId, @RequestBody(required = false) Map<String, Object> payload) {
    try {
      Map<String, Object> event = retentionStateService.applyActivityUpdate(playerId, payload);
      if (event == null) {
        return ResponseEntity.status(404).body(Map.of("error", "player-not-found"));
      }

      Map<String, Object> response = new LinkedHashMap<>();
      response.put("ok", true);
      response.put("event", event);
      return ResponseEntity.ok(response);
    } catch (IllegalStateException error) {
      return ResponseEntity.status(500)
          .body(
              Map.of(
                  "error", "update-persistence-failed",
                  "message", error.getMessage() == null ? "unknown-error" : error.getMessage()));
    }
  }

  @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter stream() {
    return retentionStateService.openStream();
  }
}
