import { spawn, spawnSync } from 'node:child_process';

const npmBin = process.platform === 'win32' ? 'npm.cmd' : 'npm';
const backendPort = Number(process.env.RETENTION_API_PORT ?? 8787);
const frontendPort = Number(process.env.RETENTION_FRONTEND_PORT ?? 5174);
const requestedPorts = [frontendPort, backendPort];
const stopOnly = process.argv.includes('--stop-only');

function sleep(ms) {
  return new Promise((resolve) => {
    setTimeout(resolve, ms);
  });
}

function uniqueNumbers(values) {
  return [...new Set(values.filter((value) => Number.isInteger(value) && value > 0))];
}

function runCommand(command, args, options = {}) {
  return spawnSync(command, args, {
    encoding: 'utf8',
    stdio: ['ignore', 'pipe', 'pipe'],
    ...options
  });
}

function pidsForPortWindows(port) {
  const result = runCommand('netstat', ['-ano', '-p', 'tcp']);
  if (result.status !== 0) {
    return [];
  }

  const pids = [];
  const portSuffix = `:${port}`;

  for (const line of result.stdout.split(/\r?\n/)) {
    const trimmed = line.trim();
    if (!trimmed.startsWith('TCP')) {
      continue;
    }

    const parts = trimmed.split(/\s+/);
    if (parts.length < 5) {
      continue;
    }

    const localAddress = parts[1];
    const state = parts[3];
    const pid = Number(parts[4]);

    if (localAddress.endsWith(portSuffix) && state === 'LISTENING' && Number.isInteger(pid)) {
      pids.push(pid);
    }
  }

  return uniqueNumbers(pids);
}

function pidsForPortUnix(port) {
  const pidSources = [];

  const lsofResult = runCommand('lsof', ['-tiTCP:' + port, '-sTCP:LISTEN']);
  if (lsofResult.status === 0) {
    pidSources.push(lsofResult.stdout);
  }

  const fuserResult = runCommand('fuser', ['-n', 'tcp', String(port)]);
  if (fuserResult.status === 0 || fuserResult.status === 1) {
    pidSources.push(fuserResult.stdout, fuserResult.stderr);
  }

  const pids = [];
  for (const source of pidSources) {
    for (const match of source.matchAll(/\d+/g)) {
      const value = Number(match[0]);
      if (value !== port) {
        pids.push(value);
      }
    }
  }

  return uniqueNumbers(pids);
}

function pidsForPort(port) {
  if (process.platform === 'win32') {
    return pidsForPortWindows(port);
  }

  return pidsForPortUnix(port);
}

function currentPortOwners() {
  const owners = new Map();

  for (const port of requestedPorts) {
    owners.set(port, pidsForPort(port));
  }

  return owners;
}

function allPids(owners) {
  return uniqueNumbers(
    [...owners.values()].flat().filter((pid) => pid !== process.pid && pid !== process.ppid)
  );
}

function printOwners(owners, label) {
  const lines = [...owners.entries()].map(([port, pids]) => {
    const suffix = pids.length === 0 ? 'free' : pids.join(', ');
    return `  - ${label} ${port}: ${suffix}`;
  });

  for (const line of lines) {
    console.log(line);
  }
}

function killPids(pids, force = false) {
  if (pids.length === 0) {
    return;
  }

  if (process.platform === 'win32') {
    runCommand('taskkill', [
      ...(force ? ['/F'] : []),
      '/T',
      ...pids.flatMap((pid) => ['/PID', String(pid)])
    ]);
    return;
  }

  for (const pid of pids) {
    try {
      process.kill(pid, force ? 'SIGKILL' : 'SIGTERM');
    } catch {
      // Process already exited or cannot be signaled.
    }
  }
}

async function freeDevPorts() {
  const initialOwners = currentPortOwners();
  const initialPids = allPids(initialOwners);

  if (initialPids.length === 0) {
    console.log('No existing dev processes were using the frontend/backend ports.');
    return;
  }

  console.log('Stopping existing dev processes...');
  printOwners(initialOwners, 'port');
  killPids(initialPids, false);
  await sleep(1200);

  const remainingOwners = currentPortOwners();
  const remainingPids = allPids(remainingOwners);

  if (remainingPids.length > 0) {
    console.log('Force-stopping remaining processes...');
    printOwners(remainingOwners, 'port');
    killPids(remainingPids, true);
    await sleep(600);
  }

  const finalOwners = currentPortOwners();
  const finalPids = allPids(finalOwners);

  if (finalPids.length > 0) {
    console.error('Some dev ports are still occupied after restart cleanup.');
    printOwners(finalOwners, 'port');
    process.exit(1);
  }

  console.log('Frontend and backend ports are clear.');
}

function startDev() {
  console.log('Starting the Spring Boot backend and Vite frontend...');

  const child = spawn(npmBin, ['run', 'dev'], {
    stdio: 'inherit',
    env: process.env
  });

  child.on('exit', (code, signal) => {
    if (signal) {
      process.exit(1);
      return;
    }

    process.exit(code ?? 0);
  });

  child.on('error', (error) => {
    console.error(error instanceof Error ? error.message : String(error));
    process.exit(1);
  });
}

async function main() {
  await freeDevPorts();

  if (stopOnly) {
    return;
  }

  startDev();
}

main().catch((error) => {
  console.error(error instanceof Error ? error.message : String(error));
  process.exit(1);
});
