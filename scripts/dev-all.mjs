import { spawn } from 'node:child_process';

const npmBin = process.platform === 'win32' ? 'npm.cmd' : 'npm';

const backend = spawn(process.execPath, ['server/index.js'], {
  stdio: 'inherit',
  env: process.env
});

const frontend = spawn(npmBin, ['run', 'dev:frontend'], {
  stdio: 'inherit',
  env: process.env
});

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
  stopProcess(backend);
  stopProcess(frontend);
  process.exit(exitCode);
}

backend.on('exit', (code) => {
  if (!shuttingDown) {
    shutdown(code ?? 1);
  }
});

frontend.on('exit', (code) => {
  if (!shuttingDown) {
    shutdown(code ?? 1);
  }
});

process.on('SIGINT', () => shutdown(0));
process.on('SIGTERM', () => shutdown(0));
