import { spawn } from 'node:child_process';

const npmBin = process.platform === 'win32' ? 'npm.cmd' : 'npm';
const backendHost = process.env.RETENTION_API_HOST ?? '127.0.0.1';
const backendPort = Number(process.env.RETENTION_API_PORT ?? 8787);
const backendHealthUrl = `http://${backendHost}:${backendPort}/api/health`;

const backend = spawn(process.execPath, ['scripts/run-java-backend.mjs'], {
  stdio: 'inherit',
  env: process.env
});

let shuttingDown = false;
let frontend = null;

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
  stopProcess(backend);
  stopProcess(frontend);
  process.exit(exitCode);
}

backend.on('exit', (code) => {
  if (!shuttingDown) {
    shutdown(code ?? 1);
  }
});

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

function delay(ms) {
  return new Promise((resolve) => {
    setTimeout(resolve, ms);
  });
}

async function waitForBackend(timeoutMs = 20000) {
  const startedAt = Date.now();

  while (Date.now() - startedAt < timeoutMs) {
    if (backend.exitCode !== null) {
      return false;
    }

    try {
      const response = await fetch(backendHealthUrl, {
        method: 'GET'
      });

      if (response.ok) {
        return true;
      }
    } catch {
      // Backend is still starting up.
    }

    await delay(500);
  }

  return false;
}

process.on('SIGINT', () => shutdown(0));
process.on('SIGTERM', () => shutdown(0));

waitForBackend()
  .then((backendReady) => {
    if (!backendReady) {
      console.warn(
        `Backend did not report healthy within 20s (${backendHealthUrl}); starting frontend anyway.`
      );
    }

    startFrontend();
  })
  .catch(() => {
    startFrontend();
  });
