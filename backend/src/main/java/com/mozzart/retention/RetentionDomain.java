package com.mozzart.retention;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

public final class RetentionDomain {
  private static final int ENTRY_RISK_THRESHOLD = 58;
  private static final int EXIT_RISK_THRESHOLD = 44;
  private static final double LOSS_BASELINE_MIN = 8;
  private static final double SESSION_BASELINE_MIN = 30;
  private static final int EXTREME_INACTIVITY_DAYS = 10;
  private static final double EXTREME_LOSS_PERCENT = 85;
  private static final int EXTREME_SESSION_MINUTES = 170;

  public static final Campaign CAMPAIGN_COOLDOWN =
      new Campaign("cooldown", "Cooldown suggestion", "Risk");
  public static final Campaign CAMPAIGN_ODDS_BOOST =
      new Campaign("oddsBoost", "VIP odds boost", "CRM");
  public static final Campaign CAMPAIGN_FREEBET =
      new Campaign("freebet", "Reactivation freebet", "CRM");
  public static final Campaign CAMPAIGN_BONUS =
      new Campaign("bonus", "Onboarding bonus", "CRM");
  public static final Campaign CAMPAIGN_LOYALTY =
      new Campaign("loyalty", "Loyalty mission", "CRM");

  private RetentionDomain() {}

  public static final class Player {
    public String id;
    public String name;
    public String market;
    public int daysSinceJoined;
    public int daysSinceLastBet;
    public int totalBets30d;
    public double lossChange24hPercent;
    public int avgSessionMinutes;
    public double netDeposit30d;
    public Experiment experiment;
    public String segment;
    public Triggers triggers;
    public Campaign campaign;
    public int riskScore;
    public RiskMeta riskMeta;
    public List<String> triggerLabels;

    public Player(
        String id,
        String name,
        String market,
        int daysSinceJoined,
        int daysSinceLastBet,
        int totalBets30d,
        double lossChange24hPercent,
        int avgSessionMinutes,
        double netDeposit30d,
        Experiment experiment) {
      this.id = id;
      this.name = name;
      this.market = market;
      this.daysSinceJoined = daysSinceJoined;
      this.daysSinceLastBet = daysSinceLastBet;
      this.totalBets30d = totalBets30d;
      this.lossChange24hPercent = lossChange24hPercent;
      this.avgSessionMinutes = avgSessionMinutes;
      this.netDeposit30d = netDeposit30d;
      this.experiment = experiment;
      this.triggerLabels = new ArrayList<>();
    }

    public Player copy() {
      Player player =
          new Player(
              id,
              name,
              market,
              daysSinceJoined,
              daysSinceLastBet,
              totalBets30d,
              lossChange24hPercent,
              avgSessionMinutes,
              netDeposit30d,
              experiment == null ? null : experiment.copy());
      player.segment = segment;
      player.triggers = triggers == null ? null : triggers.copy();
      player.campaign = campaign == null ? null : campaign.copy();
      player.riskScore = riskScore;
      player.riskMeta = riskMeta == null ? null : riskMeta.copy();
      player.triggerLabels = triggerLabels == null ? new ArrayList<>() : new ArrayList<>(triggerLabels);
      return player;
    }

    public boolean isEnriched() {
      return segment != null && triggers != null && campaign != null && riskMeta != null;
    }

    public Map<String, Object> toMap() {
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("id", id);
      result.put("name", name);
      result.put("market", market);
      result.put("daysSinceJoined", daysSinceJoined);
      result.put("daysSinceLastBet", daysSinceLastBet);
      result.put("totalBets30d", totalBets30d);
      result.put("lossChange24hPercent", lossChange24hPercent);
      result.put("avgSessionMinutes", avgSessionMinutes);
      result.put("netDeposit30d", netDeposit30d);
      result.put("experiment", experiment == null ? sanitizedExperiment(null).toMap() : experiment.toMap());
      if (segment != null) {
        result.put("segment", segment);
      }
      if (triggers != null) {
        result.put("triggers", triggers.toMap());
      }
      if (campaign != null) {
        result.put("campaign", campaign.toMap());
      }
      if (riskMeta != null) {
        result.put("riskMeta", riskMeta.toMap());
      }
      if (riskScore > 0 || segment != null) {
        result.put("riskScore", riskScore);
      }
      result.put("triggerLabels", triggerLabels == null ? List.of() : new ArrayList<>(triggerLabels));
      return result;
    }

    @SuppressWarnings("unchecked")
    public static Player fromMap(Map<String, Object> input) {
      Player player =
          new Player(
              stringValue(input.get("id"), ""),
              stringValue(input.get("name"), ""),
              stringValue(input.get("market"), ""),
              positiveInt(input.get("daysSinceJoined"), 0),
              positiveInt(input.get("daysSinceLastBet"), 0),
              positiveInt(input.get("totalBets30d"), 0),
              doubleValue(input.get("lossChange24hPercent"), 0),
              positiveInt(input.get("avgSessionMinutes"), 0),
              nonNegativeDouble(input.get("netDeposit30d"), 0),
              sanitizedExperiment(input.get("experiment")));
      player.segment = nullableString(input.get("segment"));
      if (input.get("triggers") instanceof Map<?, ?> triggerMap) {
        player.triggers = Triggers.fromMap((Map<String, Object>) triggerMap);
      }
      if (input.get("campaign") instanceof Map<?, ?> campaignMap) {
        player.campaign = Campaign.fromMap((Map<String, Object>) campaignMap);
      }
      if (input.get("riskMeta") instanceof Map<?, ?> metaMap) {
        player.riskMeta = RiskMeta.fromMap((Map<String, Object>) metaMap);
      }
      player.riskScore = intValue(input.get("riskScore"), 0);
      player.triggerLabels = stringList(input.get("triggerLabels"));
      return player;
    }
  }

  public static final class Experiment {
    public final String group;
    public final boolean accepted;

    public Experiment(String group, boolean accepted) {
      this.group = "B".equals(group) ? "B" : "A";
      this.accepted = accepted;
    }

    public Experiment copy() {
      return new Experiment(group, accepted);
    }

    public Map<String, Object> toMap() {
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("group", group);
      result.put("accepted", accepted);
      return result;
    }
  }

  public static final class RiskMeta {
    public final double baselineLossPct;
    public final double baselineSessionMinutes;
    public final double rollingLossPct;
    public final double rollingSessionMinutes;
    public final int atRiskStreak;
    public final int healthyStreak;
    public final int updates;

    public RiskMeta(
        double baselineLossPct,
        double baselineSessionMinutes,
        double rollingLossPct,
        double rollingSessionMinutes,
        int atRiskStreak,
        int healthyStreak,
        int updates) {
      this.baselineLossPct = baselineLossPct;
      this.baselineSessionMinutes = baselineSessionMinutes;
      this.rollingLossPct = rollingLossPct;
      this.rollingSessionMinutes = rollingSessionMinutes;
      this.atRiskStreak = atRiskStreak;
      this.healthyStreak = healthyStreak;
      this.updates = updates;
    }

    public RiskMeta copy() {
      return new RiskMeta(
          baselineLossPct,
          baselineSessionMinutes,
          rollingLossPct,
          rollingSessionMinutes,
          atRiskStreak,
          healthyStreak,
          updates);
    }

    public Map<String, Object> toMap() {
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("baselineLossPct", baselineLossPct);
      result.put("baselineSessionMinutes", baselineSessionMinutes);
      result.put("rollingLossPct", rollingLossPct);
      result.put("rollingSessionMinutes", rollingSessionMinutes);
      result.put("atRiskStreak", atRiskStreak);
      result.put("healthyStreak", healthyStreak);
      result.put("updates", updates);
      return result;
    }

    public static RiskMeta fromMap(Map<String, Object> input) {
      return new RiskMeta(
          doubleValue(input.get("baselineLossPct"), LOSS_BASELINE_MIN),
          doubleValue(input.get("baselineSessionMinutes"), SESSION_BASELINE_MIN),
          doubleValue(input.get("rollingLossPct"), 0),
          doubleValue(input.get("rollingSessionMinutes"), 45),
          nonNegativeInt(input.get("atRiskStreak"), 0),
          nonNegativeInt(input.get("healthyStreak"), 0),
          nonNegativeInt(input.get("updates"), 0));
    }
  }

  public static final class Triggers {
    public final boolean inactivity7d;
    public final boolean lossSpike24h;
    public final boolean longSessions;

    public Triggers(boolean inactivity7d, boolean lossSpike24h, boolean longSessions) {
      this.inactivity7d = inactivity7d;
      this.lossSpike24h = lossSpike24h;
      this.longSessions = longSessions;
    }

    public Triggers copy() {
      return new Triggers(inactivity7d, lossSpike24h, longSessions);
    }

    public Map<String, Object> toMap() {
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("inactivity7d", inactivity7d);
      result.put("lossSpike24h", lossSpike24h);
      result.put("longSessions", longSessions);
      return result;
    }

    public static Triggers fromMap(Map<String, Object> input) {
      return new Triggers(
          booleanValue(input.get("inactivity7d"), false),
          booleanValue(input.get("lossSpike24h"), false),
          booleanValue(input.get("longSessions"), false));
    }
  }

  public static final class Campaign {
    public final String id;
    public final String label;
    public final String owner;

    public Campaign(String id, String label, String owner) {
      this.id = id;
      this.label = label;
      this.owner = owner;
    }

    public Campaign copy() {
      return new Campaign(id, label, owner);
    }

    public Map<String, Object> toMap() {
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("id", id);
      result.put("label", label);
      result.put("owner", owner);
      return result;
    }

    public static Campaign fromMap(Map<String, Object> input) {
      return new Campaign(
          stringValue(input.get("id"), "loyalty"),
          stringValue(input.get("label"), "Loyalty mission"),
          stringValue(input.get("owner"), "CRM"));
    }
  }

  public static final class UpdatePatch {
    public String playerId;
    public Integer daysSinceJoined;
    public Integer daysSinceLastBet;
    public Integer totalBets30d;
    public Double lossChange24hPercent;
    public Integer avgSessionMinutes;
    public Double netDeposit30d;

    public Map<String, Object> toMap() {
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("playerId", playerId);
      if (daysSinceJoined != null) {
        result.put("daysSinceJoined", daysSinceJoined);
      }
      if (daysSinceLastBet != null) {
        result.put("daysSinceLastBet", daysSinceLastBet);
      }
      if (totalBets30d != null) {
        result.put("totalBets30d", totalBets30d);
      }
      if (lossChange24hPercent != null) {
        result.put("lossChange24hPercent", lossChange24hPercent);
      }
      if (avgSessionMinutes != null) {
        result.put("avgSessionMinutes", avgSessionMinutes);
      }
      if (netDeposit30d != null) {
        result.put("netDeposit30d", netDeposit30d);
      }
      return result;
    }

    @SuppressWarnings("unchecked")
    public static UpdatePatch fromObject(Object input) {
      UpdatePatch patch = new UpdatePatch();
      if (!(input instanceof Map<?, ?> map)) {
        return patch;
      }

      Map<String, Object> values = (Map<String, Object>) map;
      patch.playerId = nullableString(values.get("playerId"));
      patch.daysSinceJoined = nullableInt(values.get("daysSinceJoined"));
      patch.daysSinceLastBet = nullableInt(values.get("daysSinceLastBet"));
      patch.totalBets30d = nullableInt(values.get("totalBets30d"));
      patch.lossChange24hPercent = nullableDouble(values.get("lossChange24hPercent"));
      patch.avgSessionMinutes = nullableInt(values.get("avgSessionMinutes"));
      patch.netDeposit30d = nullableDouble(values.get("netDeposit30d"));
      return patch;
    }
  }

  public static final class ActivityEvent {
    public final long id;
    public final String playerId;
    public final String source;
    public final Map<String, Object> update;
    public final String createdAt;

    public ActivityEvent(long id, String playerId, String source, Map<String, Object> update, String createdAt) {
      this.id = id;
      this.playerId = playerId;
      this.source = source;
      this.update = update;
      this.createdAt = createdAt;
    }

    public Map<String, Object> toMap() {
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("id", id);
      result.put("playerId", playerId);
      result.put("source", source);
      result.put("update", update);
      result.put("createdAt", createdAt);
      return result;
    }

    @SuppressWarnings("unchecked")
    public static ActivityEvent fromMap(Map<String, Object> input) {
      Object updateValue = input.get("update");
      Map<String, Object> update = updateValue instanceof Map<?, ?> ? new LinkedHashMap<>((Map<String, Object>) updateValue) : new LinkedHashMap<>();
      return new ActivityEvent(
          longValue(input.get("id"), 0L),
          stringValue(input.get("playerId"), ""),
          stringValue(input.get("source"), "api"),
          update,
          stringValue(input.get("createdAt"), Instant.now().toString()));
    }
  }

  public static final class Intervention {
    public final String id;
    public final String playerId;
    public final String playerName;
    public final String segment;
    public final String campaign;
    public final String owner;
    public final int riskScore;
    public final int priority;
    public final String status;
    public final List<String> triggers;

    public Intervention(
        String id,
        String playerId,
        String playerName,
        String segment,
        String campaign,
        String owner,
        int riskScore,
        int priority,
        String status,
        List<String> triggers) {
      this.id = id;
      this.playerId = playerId;
      this.playerName = playerName;
      this.segment = segment;
      this.campaign = campaign;
      this.owner = owner;
      this.riskScore = riskScore;
      this.priority = priority;
      this.status = status;
      this.triggers = triggers;
    }

    public Map<String, Object> toMap() {
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("id", id);
      result.put("playerId", playerId);
      result.put("playerName", playerName);
      result.put("segment", segment);
      result.put("campaign", campaign);
      result.put("owner", owner);
      result.put("riskScore", riskScore);
      result.put("priority", priority);
      result.put("status", status);
      result.put("triggers", triggers);
      return result;
    }
  }

  public static final class Kpis {
    public final int totalPlayers;
    public final int atRiskPlayers;
    public final int retention30d;
    public final int reactivationRate;
    public final int promoRoi;
    public final int riskyTrend;

    public Kpis(
        int totalPlayers,
        int atRiskPlayers,
        int retention30d,
        int reactivationRate,
        int promoRoi,
        int riskyTrend) {
      this.totalPlayers = totalPlayers;
      this.atRiskPlayers = atRiskPlayers;
      this.retention30d = retention30d;
      this.reactivationRate = reactivationRate;
      this.promoRoi = promoRoi;
      this.riskyTrend = riskyTrend;
    }

    public Map<String, Object> toMap() {
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("totalPlayers", totalPlayers);
      result.put("atRiskPlayers", atRiskPlayers);
      result.put("retention30d", retention30d);
      result.put("reactivationRate", reactivationRate);
      result.put("promoRoi", promoRoi);
      result.put("riskyTrend", riskyTrend);
      return result;
    }
  }

  public static final class ExperimentSummaryRow {
    public final String campaign;
    public final String groupA;
    public final String groupB;
    public final String winner;

    public ExperimentSummaryRow(String campaign, String groupA, String groupB, String winner) {
      this.campaign = campaign;
      this.groupA = groupA;
      this.groupB = groupB;
      this.winner = winner;
    }

    public Map<String, Object> toMap() {
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("campaign", campaign);
      result.put("groupA", groupA);
      result.put("groupB", groupB);
      result.put("winner", winner);
      return result;
    }
  }

  private static final class SegmentState {
    public final String segment;
    public final RiskMeta riskMeta;

    private SegmentState(String segment, RiskMeta riskMeta) {
      this.segment = segment;
      this.riskMeta = riskMeta;
    }
  }

  private static final class SevereSignals {
    public final boolean severeInactivity;
    public final boolean severeLossSpike;
    public final boolean severeLongSessions;

    private SevereSignals(boolean severeInactivity, boolean severeLossSpike, boolean severeLongSessions) {
      this.severeInactivity = severeInactivity;
      this.severeLossSpike = severeLossSpike;
      this.severeLongSessions = severeLongSessions;
    }
  }

  public static List<Player> seedPlayers() {
    List<Player> players = new ArrayList<>();
    players.add(rawPlayer("p-1001", "Milan Jovanovic", "Serbia", 12, 0, 16, 8, 44, 380, "A", true));
    players.add(rawPlayer("p-1002", "Ana Markovic", "Serbia", 218, 9, 84, 12, 52, 940, "B", false));
    players.add(rawPlayer("p-1003", "Nemanja Petrovic", "Montenegro", 420, 1, 248, 6, 61, 6120, "A", true));
    players.add(rawPlayer("p-1004", "Ivana Vukovic", "Serbia", 75, 2, 31, 58, 84, 780, "B", false));
    players.add(rawPlayer("p-1005", "Stefan Nikolic", "Bosnia", 160, 4, 114, 17, 132, 1760, "A", false));
    players.add(rawPlayer("p-1006", "Jelena Stojanovic", "Serbia", 25, 1, 7, 5, 30, 190, "B", true));
    players.add(rawPlayer("p-1007", "Lazar Kovacevic", "Croatia", 340, 11, 58, 48, 146, 2050, "A", false));
    players.add(rawPlayer("p-1008", "Teodora Simic", "North Macedonia", 43, 0, 26, -3, 47, 620, "B", true));
    players.add(rawPlayer("p-1009", "Marko Pavlovic", "Serbia", 605, 3, 275, 9, 89, 7380, "A", true));
    players.add(rawPlayer("p-1010", "Sanja Mitrovic", "Serbia", 95, 8, 19, 2, 40, 540, "B", false));
    players.add(rawPlayer("p-1011", "Vladimir Ristic", "Serbia", 51, 1, 67, 37, 119, 1440, "A", true));
    players.add(rawPlayer("p-1012", "Mina Krstic", "Montenegro", 18, 0, 11, 4, 38, 260, "B", true));
    return players;
  }

  public static List<Player> enrichPlayers(List<Player> players) {
    List<Player> enriched = new ArrayList<>();
    for (Player player : players) {
      enriched.add(enrichPlayer(player));
    }
    return enriched;
  }

  public static List<Player> copyPlayers(List<Player> players) {
    List<Player> copies = new ArrayList<>();
    for (Player player : players) {
      copies.add(player.copy());
    }
    return copies;
  }

  public static Player enrichPlayer(Player player) {
    Player normalized = normalizePlayer(player);
    RiskMeta initialRiskMeta = normalizeRiskMeta(normalized);
    RiskMeta refreshed = refreshRiskMeta(normalized, initialRiskMeta);
    Triggers triggers = deriveTriggers(normalized, refreshed);
    int riskScore = riskScoreFromTriggers(normalized, triggers, refreshed);
    SegmentState segmentState = resolveSegmentState(normalized, triggers, riskScore, refreshed);
    Campaign campaign = resolveCampaign(segmentState.segment, triggers);

    Player enriched = normalized.copy();
    enriched.triggers = triggers;
    enriched.campaign = campaign;
    enriched.riskScore = riskScore;
    enriched.riskMeta = segmentState.riskMeta;
    enriched.segment = segmentState.segment;
    enriched.triggerLabels = activeTriggerLabels(triggers);
    return enriched;
  }

  public static Player applyActivityUpdate(Player current, UpdatePatch update) {
    if (current == null || update == null) {
      return current;
    }

    Player merged = current.copy();
    if (update.daysSinceJoined != null) {
      merged.daysSinceJoined = Math.max(0, update.daysSinceJoined);
    }
    if (update.daysSinceLastBet != null) {
      merged.daysSinceLastBet = Math.max(0, update.daysSinceLastBet);
    }
    if (update.totalBets30d != null) {
      merged.totalBets30d = Math.max(0, update.totalBets30d);
    }
    if (update.lossChange24hPercent != null) {
      merged.lossChange24hPercent = update.lossChange24hPercent;
    }
    if (update.avgSessionMinutes != null) {
      merged.avgSessionMinutes = Math.max(0, update.avgSessionMinutes);
    }
    if (update.netDeposit30d != null) {
      merged.netDeposit30d = Math.max(0, update.netDeposit30d);
    }
    return enrichPlayer(merged);
  }

  public static List<Intervention> buildInterventions(List<Player> players) {
    List<Intervention> interventions = new ArrayList<>();
    for (Player player : players) {
      interventions.add(buildIntervention(player));
    }

    interventions.sort(
        Comparator.comparingInt((Intervention item) -> item.priority)
            .thenComparing((Intervention left, Intervention right) -> Integer.compare(right.riskScore, left.riskScore)));
    return interventions;
  }

  public static Kpis buildKpis(List<Player> players) {
    int totalPlayers = players.size();
    int atRiskPlayers = 0;
    int retainedPlayers = 0;
    int dormantPlayers = 0;
    int reactivatedPlayers = 0;
    int acceptedOffers = 0;
    int riskyPlayers = 0;

    for (Player player : players) {
      if ("At-risk".equals(player.segment)) {
        atRiskPlayers += 1;
      }
      if (player.totalBets30d >= 8) {
        retainedPlayers += 1;
      }
      if (player.triggers != null && player.triggers.inactivity7d) {
        dormantPlayers += 1;
        if (player.experiment != null && player.experiment.accepted) {
          reactivatedPlayers += 1;
        }
      }
      if (player.experiment != null && player.experiment.accepted) {
        acceptedOffers += 1;
      }
      if (player.riskScore >= 72) {
        riskyPlayers += 1;
      }
    }

    return new Kpis(
        totalPlayers,
        atRiskPlayers,
        totalPlayers == 0 ? 0 : (int) Math.round((retainedPlayers / (double) totalPlayers) * 100),
        dormantPlayers == 0 ? 0 : (int) Math.round((reactivatedPlayers / (double) dormantPlayers) * 100),
        totalPlayers == 0 ? 0 : (int) Math.round((acceptedOffers / (double) totalPlayers) * 170 - 32),
        totalPlayers == 0 ? 0 : (int) Math.round((riskyPlayers / (double) totalPlayers) * 100));
  }

  public static List<ExperimentSummaryRow> buildExperimentSummary(List<Player> players) {
    Map<String, SummaryBucket> buckets = new LinkedHashMap<>();

    for (Player player : players) {
      if (player.campaign == null || player.experiment == null) {
        continue;
      }

      SummaryBucket bucket =
          buckets.computeIfAbsent(player.campaign.id, ignored -> new SummaryBucket(player.campaign.label));
      if ("B".equals(player.experiment.group)) {
        bucket.groupBTotal += 1;
        if (player.experiment.accepted) {
          bucket.groupBAccepted += 1;
        }
      } else {
        bucket.groupATotal += 1;
        if (player.experiment.accepted) {
          bucket.groupAAccepted += 1;
        }
      }
    }

    List<ExperimentSummaryRow> rows = new ArrayList<>();
    for (SummaryBucket bucket : buckets.values()) {
      double groupARate = rateValue(bucket.groupAAccepted, bucket.groupATotal);
      double groupBRate = rateValue(bucket.groupBAccepted, bucket.groupBTotal);
      String winner = "Tie";
      if (groupARate > groupBRate) {
        winner = "Group A";
      } else if (groupBRate > groupARate) {
        winner = "Group B";
      }

      rows.add(
          new ExperimentSummaryRow(
              bucket.campaign,
              bucket.groupAAccepted + "/" + bucket.groupATotal + " (" + formatRate(bucket.groupAAccepted, bucket.groupATotal) + ")",
              bucket.groupBAccepted + "/" + bucket.groupBTotal + " (" + formatRate(bucket.groupBAccepted, bucket.groupBTotal) + ")",
              winner));
    }

    rows.sort(Comparator.comparing(row -> row.campaign));
    return rows;
  }

  public static UpdatePatch randomUpdate(List<Player> players, Random random) {
    if (players.isEmpty()) {
      return null;
    }

    Player player = players.get(random.nextInt(players.size()));
    SegmentTargets targets = segmentTargets(player.segment);
    String eventType = pickWeighted(eventWeightsForPlayer(player), random);
    UpdatePatch patch = new UpdatePatch();
    patch.playerId = player.id;

    if ("normalBet".equals(eventType)) {
      patch.daysSinceLastBet = 0;
      patch.totalBets30d = clampInt(player.totalBets30d + randomInt(random, 1, 3), 0, 320);
      patch.lossChange24hPercent = clampDouble(driftTowards(player.lossChange24hPercent, targets.loss, 12, 7, random), -60, 140);
      patch.avgSessionMinutes = clampInt((int) Math.round(driftTowards(player.avgSessionMinutes, targets.session, 18, 10, random)), 15, 240);
      return patch;
    }

    if ("idleDay".equals(eventType)) {
      patch.daysSinceLastBet = clampInt(player.daysSinceLastBet + randomInt(random, 1, 2), 0, 35);
      patch.lossChange24hPercent = clampDouble(driftTowards(player.lossChange24hPercent, targets.loss + 8, 10, 6, random), -60, 140);
      patch.avgSessionMinutes = clampInt((int) Math.round(driftTowards(player.avgSessionMinutes, targets.session + 6, 12, 9, random)), 15, 240);
      return patch;
    }

    if ("stressBurst".equals(eventType)) {
      patch.daysSinceLastBet = randomInt(random, 0, 2);
      patch.lossChange24hPercent = clampDouble(player.lossChange24hPercent + randomInt(random, 14, 32), -60, 140);
      patch.avgSessionMinutes = clampInt(player.avgSessionMinutes + randomInt(random, 18, 40), 15, 240);
      patch.totalBets30d = clampInt(player.totalBets30d + randomInt(random, 0, 2), 0, 320);
      return patch;
    }

    if ("coolDown".equals(eventType)) {
      patch.lossChange24hPercent = clampDouble(driftTowards(player.lossChange24hPercent, targets.loss - 12, 18, 6, random), -60, 140);
      patch.avgSessionMinutes = clampInt((int) Math.round(driftTowards(player.avgSessionMinutes, targets.session - 20, 28, 9, random)), 15, 240);
      return patch;
    }

    patch.netDeposit30d = clampDouble(player.netDeposit30d + randomInt(random, -280, 360), 0, 12000);
    patch.lossChange24hPercent = clampDouble(driftTowards(player.lossChange24hPercent, targets.loss + 3, 12, 8, random), -60, 140);
    patch.avgSessionMinutes = clampInt((int) Math.round(driftTowards(player.avgSessionMinutes, targets.session + 2, 16, 8, random)), 15, 240);
    return patch;
  }

  private static Player rawPlayer(
      String id,
      String name,
      String market,
      int daysSinceJoined,
      int daysSinceLastBet,
      int totalBets30d,
      double lossChange24hPercent,
      int avgSessionMinutes,
      double netDeposit30d,
      String experimentGroup,
      boolean experimentAccepted) {
    return new Player(
        id,
        name,
        market,
        daysSinceJoined,
        daysSinceLastBet,
        totalBets30d,
        lossChange24hPercent,
        avgSessionMinutes,
        netDeposit30d,
        new Experiment(experimentGroup, experimentAccepted));
  }

  private static Player normalizePlayer(Player player) {
    Player normalized =
        new Player(
            stringValue(player.id, ""),
            stringValue(player.name, ""),
            stringValue(player.market, ""),
            nonNegativeInt(player.daysSinceJoined, 0),
            nonNegativeInt(player.daysSinceLastBet, 0),
            nonNegativeInt(player.totalBets30d, 0),
            doubleValue(player.lossChange24hPercent, 0),
            nonNegativeInt(player.avgSessionMinutes, 0),
            nonNegativeDouble(player.netDeposit30d, 0),
            sanitizedExperiment(player.experiment));
    normalized.segment = player.segment;
    normalized.triggers = player.triggers == null ? null : player.triggers.copy();
    normalized.campaign = player.campaign == null ? null : player.campaign.copy();
    normalized.riskScore = player.riskScore;
    normalized.riskMeta = player.riskMeta == null ? null : player.riskMeta.copy();
    normalized.triggerLabels = player.triggerLabels == null ? new ArrayList<>() : new ArrayList<>(player.triggerLabels);
    return normalized;
  }

  private static Experiment sanitizedExperiment(Object experimentValue) {
    if (experimentValue instanceof Experiment experiment) {
      return experiment.copy();
    }
    if (experimentValue instanceof Map<?, ?> map) {
      return new Experiment(stringValue(((Map<?, ?>) map).get("group"), "A"), booleanValue(((Map<?, ?>) map).get("accepted"), false));
    }
    return new Experiment("A", false);
  }

  private static RiskMeta normalizeRiskMeta(Player player) {
    RiskMeta previous = player.riskMeta;
    double fallbackLoss = Math.max(LOSS_BASELINE_MIN, doubleValue(player.lossChange24hPercent, LOSS_BASELINE_MIN));
    double fallbackSession = Math.max(SESSION_BASELINE_MIN, doubleValue(player.avgSessionMinutes, SESSION_BASELINE_MIN));

    return new RiskMeta(
        clampDouble(previous == null ? fallbackLoss : previous.baselineLossPct, LOSS_BASELINE_MIN, 160),
        clampDouble(previous == null ? fallbackSession : previous.baselineSessionMinutes, SESSION_BASELINE_MIN, 240),
        clampDouble(previous == null ? doubleValue(player.lossChange24hPercent, 0) : previous.rollingLossPct, -60, 160),
        clampDouble(previous == null ? doubleValue(player.avgSessionMinutes, 45) : previous.rollingSessionMinutes, 15, 240),
        previous == null ? 0 : Math.max(0, previous.atRiskStreak),
        previous == null ? 0 : Math.max(0, previous.healthyStreak),
        previous == null ? 0 : Math.max(0, previous.updates));
  }

  private static RiskMeta refreshRiskMeta(Player player, RiskMeta riskMeta) {
    boolean healthySignal =
        player.daysSinceLastBet <= 2 && player.lossChange24hPercent < 45 && player.avgSessionMinutes < 120;
    double baselineWeight = healthySignal ? 0.14 : 0.04;

    return new RiskMeta(
        clampDouble(ema(riskMeta.baselineLossPct, Math.max(LOSS_BASELINE_MIN, player.lossChange24hPercent), baselineWeight), LOSS_BASELINE_MIN, 160),
        clampDouble(ema(riskMeta.baselineSessionMinutes, Math.max(SESSION_BASELINE_MIN, player.avgSessionMinutes), baselineWeight), SESSION_BASELINE_MIN, 240),
        clampDouble(ema(riskMeta.rollingLossPct, player.lossChange24hPercent, 0.22), -60, 160),
        clampDouble(ema(riskMeta.rollingSessionMinutes, player.avgSessionMinutes, 0.22), 15, 240),
        riskMeta.atRiskStreak,
        riskMeta.healthyStreak,
        riskMeta.updates + 1);
  }

  private static Triggers deriveTriggers(Player player, RiskMeta riskMeta) {
    SevereSignals severeSignals = deriveSevereSignals(player);
    return new Triggers(
        player.daysSinceLastBet >= 7,
        player.lossChange24hPercent >= lossSpikeThreshold(riskMeta) || severeSignals.severeLossSpike,
        player.avgSessionMinutes >= longSessionThreshold(riskMeta) || severeSignals.severeLongSessions);
  }

  private static int riskScoreFromTriggers(Player player, Triggers triggers, RiskMeta riskMeta) {
    double inactivityScore = clampDouble(((player.daysSinceLastBet - 1) / 12.0) * 100, 0, 100);
    double lossDelta = player.lossChange24hPercent - riskMeta.baselineLossPct;
    double lossScore = clampDouble(((lossDelta + 8) / 58.0) * 100, 0, 100);
    double sessionDelta = player.avgSessionMinutes - riskMeta.baselineSessionMinutes;
    double sessionScore = clampDouble(((sessionDelta + 10) / 110.0) * 100, 0, 100);
    double volatilityScore =
        clampDouble(
            ((Math.abs(player.lossChange24hPercent - riskMeta.rollingLossPct)
                        + Math.abs(player.avgSessionMinutes - riskMeta.rollingSessionMinutes) * 0.45)
                    / 70.0)
                * 100,
            0,
            100);

    double score =
        9 + inactivityScore * 0.34 + lossScore * 0.37 + sessionScore * 0.23 + volatilityScore * 0.06;

    if (triggers.inactivity7d) {
      score += 11;
    }
    if (triggers.lossSpike24h) {
      score += 11;
    }
    if (triggers.longSessions) {
      score += 9;
    }

    SevereSignals severe = deriveSevereSignals(player);
    if (severe.severeInactivity || severe.severeLossSpike || severe.severeLongSessions) {
      score += 10;
    }
    if (severe.severeInactivity) {
      score = Math.max(score, 74);
    }
    if (severe.severeLossSpike) {
      score = Math.max(score, 78);
    }
    if (severe.severeLongSessions) {
      score = Math.max(score, 70);
    }

    return (int) Math.round(clampDouble(score, 0, 100));
  }

  private static SegmentState resolveSegmentState(Player player, Triggers triggers, int riskScore, RiskMeta riskMeta) {
    SevereSignals severe = deriveSevereSignals(player);
    boolean severeRisk = severe.severeInactivity || severe.severeLossSpike || severe.severeLongSessions;
    String previousSegment = player.segment;
    boolean warmupWindow = riskMeta.updates <= 1 && previousSegment == null;

    boolean highRiskNow = riskScore >= ENTRY_RISK_THRESHOLD || (triggers.inactivity7d && (triggers.lossSpike24h || triggers.longSessions));
    boolean lowRiskNow = riskScore <= EXIT_RISK_THRESHOLD && !triggers.inactivity7d && !triggers.lossSpike24h && !triggers.longSessions;

    int atRiskStreak = riskMeta.atRiskStreak;
    int healthyStreak = riskMeta.healthyStreak;
    boolean isAtRisk;

    if (severeRisk) {
      isAtRisk = true;
      atRiskStreak =
          "At-risk".equals(previousSegment)
              ? riskMeta.atRiskStreak + 1
              : Math.max(riskMeta.atRiskStreak + 1, 2);
      healthyStreak = 0;
    } else if ("At-risk".equals(previousSegment)) {
      healthyStreak = lowRiskNow ? riskMeta.healthyStreak + 1 : 0;
      atRiskStreak = highRiskNow ? riskMeta.atRiskStreak + 1 : Math.max(riskMeta.atRiskStreak - 1, 0);
      isAtRisk = healthyStreak < 3;
      if (!isAtRisk) {
        atRiskStreak = 0;
      }
    } else {
      atRiskStreak = highRiskNow ? riskMeta.atRiskStreak + 1 : 0;
      healthyStreak = lowRiskNow ? riskMeta.healthyStreak + 1 : 0;
      isAtRisk = atRiskStreak >= 2;

      if (warmupWindow && highRiskNow && riskScore >= ENTRY_RISK_THRESHOLD + 6) {
        isAtRisk = true;
        atRiskStreak = Math.max(atRiskStreak, 2);
      }
    }

    if (isAtRisk) {
      return new SegmentState("At-risk", new RiskMeta(riskMeta.baselineLossPct, riskMeta.baselineSessionMinutes, riskMeta.rollingLossPct, riskMeta.rollingSessionMinutes, atRiskStreak, healthyStreak, riskMeta.updates));
    }
    if (player.netDeposit30d >= 5000 || player.totalBets30d >= 220) {
      return new SegmentState("VIP", new RiskMeta(riskMeta.baselineLossPct, riskMeta.baselineSessionMinutes, riskMeta.rollingLossPct, riskMeta.rollingSessionMinutes, 0, healthyStreak, riskMeta.updates));
    }
    if (player.daysSinceJoined <= 30) {
      return new SegmentState("New", new RiskMeta(riskMeta.baselineLossPct, riskMeta.baselineSessionMinutes, riskMeta.rollingLossPct, riskMeta.rollingSessionMinutes, 0, healthyStreak, riskMeta.updates));
    }
    return new SegmentState("Active", new RiskMeta(riskMeta.baselineLossPct, riskMeta.baselineSessionMinutes, riskMeta.rollingLossPct, riskMeta.rollingSessionMinutes, 0, healthyStreak, riskMeta.updates));
  }

  private static Campaign resolveCampaign(String segment, Triggers triggers) {
    if (triggers.lossSpike24h || triggers.longSessions) {
      return CAMPAIGN_COOLDOWN;
    }
    if (triggers.inactivity7d && "VIP".equals(segment)) {
      return CAMPAIGN_ODDS_BOOST;
    }
    if (triggers.inactivity7d) {
      return CAMPAIGN_FREEBET;
    }
    if ("New".equals(segment)) {
      return CAMPAIGN_BONUS;
    }
    return CAMPAIGN_LOYALTY;
  }

  private static List<String> activeTriggerLabels(Triggers triggers) {
    List<String> labels = new ArrayList<>();
    if (triggers.inactivity7d) {
      labels.add("Inactivity 7d");
    }
    if (triggers.lossSpike24h) {
      labels.add("Loss spike 24h");
    }
    if (triggers.longSessions) {
      labels.add("Session >120m");
    }
    return labels;
  }

  private static Intervention buildIntervention(Player player) {
    boolean urgent = player.riskScore >= 72;
    int priority = urgent ? 1 : "VIP".equals(player.segment) ? 2 : "New".equals(player.segment) ? 3 : 4;
    List<String> triggers = player.triggerLabels == null || player.triggerLabels.isEmpty() ? List.of("Healthy pattern") : new ArrayList<>(player.triggerLabels);
    return new Intervention(
        player.id + ":" + player.campaign.id,
        player.id,
        player.name,
        player.segment,
        player.campaign.label,
        player.campaign.owner,
        player.riskScore,
        priority,
        urgent ? "Immediate review" : "Auto-send",
        triggers);
  }

  private static String formatRate(int accepted, int total) {
    if (total == 0) {
      return "0%";
    }
    return Math.round((accepted / (double) total) * 100) + "%";
  }

  private static double rateValue(int accepted, int total) {
    return total == 0 ? 0 : accepted / (double) total;
  }

  private static SevereSignals deriveSevereSignals(Player player) {
    return new SevereSignals(
        player.daysSinceLastBet >= EXTREME_INACTIVITY_DAYS,
        player.lossChange24hPercent >= EXTREME_LOSS_PERCENT,
        player.avgSessionMinutes >= EXTREME_SESSION_MINUTES);
  }

  private static double lossSpikeThreshold(RiskMeta riskMeta) {
    return Math.max(38, Math.max(riskMeta.baselineLossPct + 12, riskMeta.rollingLossPct + 9));
  }

  private static double longSessionThreshold(RiskMeta riskMeta) {
    return Math.max(105, Math.max(riskMeta.baselineSessionMinutes * 1.35, riskMeta.rollingSessionMinutes * 1.25));
  }

  private static double ema(double currentValue, double nextValue, double weight) {
    return currentValue + (nextValue - currentValue) * weight;
  }

  private static SegmentTargets segmentTargets(String segment) {
    if ("At-risk".equals(segment)) {
      return new SegmentTargets(42, 122);
    }
    if ("VIP".equals(segment)) {
      return new SegmentTargets(18, 90);
    }
    if ("New".equals(segment)) {
      return new SegmentTargets(14, 58);
    }
    return new SegmentTargets(17, 74);
  }

  private static Map<String, Double> eventWeightsForPlayer(Player player) {
    String segment = player.segment == null ? "Active" : player.segment;
    String campaignId = player.campaign == null ? "loyalty" : player.campaign.id;

    Map<String, Double> weights = new LinkedHashMap<>();
    weights.put("normalBet", 2.4);
    weights.put("idleDay", 2.2);
    weights.put("stressBurst", "At-risk".equals(segment) ? 2.8 : 1.8);
    weights.put("coolDown", "At-risk".equals(segment) ? 1.4 : 1.1);
    weights.put("bankrollSwing", 1.3);

    if ("cooldown".equals(campaignId)) {
      weights.put("coolDown", weights.get("coolDown") + 0.8);
      weights.put("stressBurst", Math.max(0.6, weights.get("stressBurst") - 0.4));
    }
    if ("freebet".equals(campaignId) || "oddsBoost".equals(campaignId)) {
      weights.put("normalBet", weights.get("normalBet") + 0.6);
      weights.put("idleDay", Math.max(0.6, weights.get("idleDay") - 0.2));
    }
    if ("bonus".equals(campaignId)) {
      weights.put("normalBet", weights.get("normalBet") + 0.4);
    }
    return weights;
  }

  private static String pickWeighted(Map<String, Double> weights, Random random) {
    double total = 0;
    for (double weight : weights.values()) {
      if (weight > 0) {
        total += weight;
      }
    }
    if (total <= 0) {
      return "normalBet";
    }

    double cursor = random.nextDouble() * total;
    for (Map.Entry<String, Double> entry : weights.entrySet()) {
      if (entry.getValue() <= 0) {
        continue;
      }
      cursor -= entry.getValue();
      if (cursor <= 0) {
        return entry.getKey();
      }
    }
    return weights.keySet().iterator().next();
  }

  private static int randomInt(Random random, int min, int max) {
    return random.nextInt((max - min) + 1) + min;
  }

  private static double driftTowards(double current, double target, double maxStep, int noise, Random random) {
    double delta = target - current;
    double boundedStep = clampDouble(delta, -maxStep, maxStep);
    int jitter = noise == 0 ? 0 : randomInt(random, -noise, noise);
    return current + boundedStep + jitter;
  }

  private static List<String> stringList(Object value) {
    List<String> result = new ArrayList<>();
    if (value instanceof List<?> list) {
      for (Object item : list) {
        if (item != null) {
          result.add(String.valueOf(item));
        }
      }
    }
    return result;
  }

  private static Integer nullableInt(Object value) {
    if (value == null) {
      return null;
    }
    return intValue(value, 0);
  }

  private static Double nullableDouble(Object value) {
    if (value == null) {
      return null;
    }
    return doubleValue(value, 0);
  }

  private static String nullableString(Object value) {
    if (value == null) {
      return null;
    }
    String text = String.valueOf(value);
    return text.isBlank() ? null : text;
  }

  private static String stringValue(Object value, String fallback) {
    if (value == null) {
      return fallback;
    }
    String text = String.valueOf(value);
    return text.isBlank() ? fallback : text;
  }

  private static double doubleValue(Object value, double fallback) {
    if (value == null) {
      return fallback;
    }
    if (value instanceof Number number) {
      return number.doubleValue();
    }
    try {
      return Double.parseDouble(String.valueOf(value));
    } catch (NumberFormatException ignored) {
      return fallback;
    }
  }

  private static int intValue(Object value, int fallback) {
    if (value == null) {
      return fallback;
    }
    if (value instanceof Number number) {
      return number.intValue();
    }
    try {
      return (int) Math.round(Double.parseDouble(String.valueOf(value)));
    } catch (NumberFormatException ignored) {
      return fallback;
    }
  }

  private static long longValue(Object value, long fallback) {
    if (value == null) {
      return fallback;
    }
    if (value instanceof Number number) {
      return number.longValue();
    }
    try {
      return Long.parseLong(String.valueOf(value));
    } catch (NumberFormatException ignored) {
      return fallback;
    }
  }

  private static boolean booleanValue(Object value, boolean fallback) {
    if (value == null) {
      return fallback;
    }
    if (value instanceof Boolean bool) {
      return bool;
    }
    return Boolean.parseBoolean(String.valueOf(value));
  }

  private static int positiveInt(Object value, int fallback) {
    return Math.max(0, intValue(value, fallback));
  }

  private static int nonNegativeInt(int value, int fallback) {
    return Math.max(0, value);
  }

  private static int nonNegativeInt(Object value, int fallback) {
    return Math.max(0, intValue(value, fallback));
  }

  private static double nonNegativeDouble(Object value, double fallback) {
    return Math.max(0, doubleValue(value, fallback));
  }

  private static double clampDouble(double value, double min, double max) {
    return Math.max(min, Math.min(max, value));
  }

  private static int clampInt(int value, int min, int max) {
    return Math.max(min, Math.min(max, value));
  }

  private static final class SummaryBucket {
    public final String campaign;
    public int groupATotal;
    public int groupAAccepted;
    public int groupBTotal;
    public int groupBAccepted;

    private SummaryBucket(String campaign) {
      this.campaign = campaign;
    }
  }

  private record SegmentTargets(double loss, double session) {}
}
