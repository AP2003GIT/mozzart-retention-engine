import { access } from 'node:fs/promises';
import { constants } from 'node:fs';
import { spawn } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const projectRoot = path.resolve(__dirname, '..');
const backendRoot = path.join(projectRoot, 'backend');
const jarName = 'retention-backend-0.1.0-SNAPSHOT.jar';
const jarPath = path.join(backendRoot, 'target', jarName);
const javaBin = process.platform === 'win32' ? 'java.exe' : 'java';

async function fileExists(filePath) {
  try {
    await access(filePath, constants.F_OK);
    return true;
  } catch {
    return false;
  }
}

async function main() {
  if (!(await fileExists(jarPath))) {
    console.error(
      `Spring production jar not found at ${jarPath}. Run "npm run build:backend" before starting.`
    );
    process.exit(1);
  }

  const backendEnv = { ...process.env };
  delete backendEnv.DEBUG;

  const backend = spawn(javaBin, ['-jar', jarPath], {
    cwd: backendRoot,
    stdio: 'inherit',
    env: backendEnv
  });

  const stopBackend = () => {
    if (!backend.killed) {
      backend.kill('SIGTERM');
    }
  };

  process.on('SIGINT', stopBackend);
  process.on('SIGTERM', stopBackend);

  backend.on('error', (error) => {
    console.error(error instanceof Error ? error.message : String(error));
    process.exit(1);
  });

  backend.on('exit', (code, signal) => {
    if (signal) {
      process.exit(1);
      return;
    }

    process.exit(code ?? 0);
  });
}

main().catch((error) => {
  console.error(error instanceof Error ? error.message : String(error));
  process.exit(1);
});
