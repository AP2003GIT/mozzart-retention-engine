function clamp(value, min, max) {
  return Math.max(min, Math.min(max, value));
}

function randomInt(min, max) {
  return Math.floor(Math.random() * (max - min + 1)) + min;
}

export function startMockPlayerActivityStream({
  players,
  onUpdate,
  intervalMs = 2200,
  onDisconnect
}) {
  const timerId = globalThis.setInterval(() => {
    if (!Array.isArray(players) || players.length === 0) {
      return;
    }

    const player = players[Math.floor(Math.random() * players.length)];
    const eventType = randomInt(1, 4);

    if (eventType === 1) {
      onUpdate({
        playerId: player.id,
        daysSinceLastBet: 0,
        totalBets30d: player.totalBets30d + randomInt(1, 3),
        lossChange24hPercent: clamp(player.lossChange24hPercent - randomInt(2, 16), -60, 140),
        avgSessionMinutes: clamp(player.avgSessionMinutes + randomInt(-8, 12), 15, 240)
      });
      return;
    }

    if (eventType === 2) {
      onUpdate({
        playerId: player.id,
        daysSinceLastBet: clamp(player.daysSinceLastBet + 1, 0, 30),
        lossChange24hPercent: clamp(player.lossChange24hPercent + randomInt(-3, 9), -60, 140)
      });
      return;
    }

    if (eventType === 3) {
      onUpdate({
        playerId: player.id,
        lossChange24hPercent: clamp(player.lossChange24hPercent + randomInt(8, 24), -60, 140),
        avgSessionMinutes: clamp(player.avgSessionMinutes + randomInt(10, 28), 15, 240)
      });
      return;
    }

    onUpdate({
      playerId: player.id,
      avgSessionMinutes: clamp(player.avgSessionMinutes + randomInt(-25, 20), 15, 240),
      netDeposit30d: clamp(player.netDeposit30d + randomInt(-200, 340), 0, 12000)
    });
  }, intervalMs);

  return () => {
    globalThis.clearInterval(timerId);
    if (typeof onDisconnect === 'function') {
      onDisconnect();
    }
  };
}
