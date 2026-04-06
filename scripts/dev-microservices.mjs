import { spawn } from 'node:child_process';

const npmBin = process.platform === 'win32' ? 'npm.cmd' : 'npm';
const backendHost = process.env.RETENTION_API_HOST ?? '127.0.0.1';
const backendPort = Number(process.env.RETENTION_API_PORT ?? 8787);
const riskHost = process.env.RETENTION_RISK_HOST ?? '127.0.0.1';
const riskPort = Number(process.env.RETENTION_RISK_PORT ?? 8792);
const crmHost = process.env.RETENTION_CRM_HOST ?? '127.0.0.1';
const crmPort = Number(process.env.RETENTION_CRM_PORT ?? 8793);

const backendHealthUrl = `http://${backendHost}:${backendPort}/api/health`;
const riskHealthUrl = `http://${riskHost}:${riskPort}/api/risk/health`;
const crmHealthUrl = `http://${crmHost}:${crmPort}/api/crm/health`;

const children = [];
let frontend = null;
let shuttingDown = false;

function stopProcess(childProcess) {
  if (!childProcess || childProcess.killed) {
    return;
  }

  childProcess.kill('SIGTERM');
}

function shutdown(exitCode = 0) {
  if (shuttingDown) {
    return;
  }

  shuttingDown = true;
  for (const child of children) {
    stopProcess(child);
  }
  stopProcess(frontend);
  process.exit(exitCode);
}

function spawnService(command, args, env) {
  const child = spawn(command, args, {
    stdio: 'inherit',
    env: env ?? process.env
  });
  children.push(child);
  child.on('exit', (code) => {
    if (!shuttingDown) {
      shutdown(code ?? 1);
    }
  });
  return child;
}

function delay(ms) {
  return new Promise((resolve) => {
    setTimeout(resolve, ms);
  });
}

async function waitForHealth(url, timeoutMs = 20000) {
  const startedAt = Date.now();
  while (Date.now() - startedAt < timeoutMs) {
    try {
      const response = await fetch(url, { method: 'GET' });
      if (response.ok) {
        return true;
      }
    } catch {
      // Still starting up.
    }
    await delay(500);
  }
  return false;
}

function startFrontend() {
  if (frontend || shuttingDown) {
    return;
  }

  frontend = spawn(npmBin, ['run', 'dev:frontend'], {
    stdio: 'inherit',
    env: process.env
  });

  frontend.on('exit', (code) => {
    if (!shuttingDown) {
      shutdown(code ?? 1);
    }
  });
}

process.on('SIGINT', () => shutdown(0));
process.on('SIGTERM', () => shutdown(0));

async function main() {
  spawnService(npmBin, ['run', 'dev:risk']);
  spawnService(npmBin, ['run', 'dev:crm']);

  const [riskReady, crmReady] = await Promise.all([
    waitForHealth(riskHealthUrl),
    waitForHealth(crmHealthUrl)
  ]);

  if (!riskReady) {
    console.warn(`Risk service did not report healthy within 20s (${riskHealthUrl}).`);
  }
  if (!crmReady) {
    console.warn(`CRM service did not report healthy within 20s (${crmHealthUrl}).`);
  }

  const backendEnv = {
    ...process.env,
    RETENTION_MICROSERVICES: 'true',
    RETENTION_RISK_URL: `http://${riskHost}:${riskPort}`,
    RETENTION_CRM_URL: `http://${crmHost}:${crmPort}`
  };

  spawnService(npmBin, ['run', 'dev:backend'], backendEnv);

  const backendReady = await waitForHealth(backendHealthUrl);
  if (!backendReady) {
    console.warn(`Players service did not report healthy within 20s (${backendHealthUrl}); starting frontend anyway.`);
  }

  startFrontend();
}

main().catch(() => {
  startFrontend();
});
