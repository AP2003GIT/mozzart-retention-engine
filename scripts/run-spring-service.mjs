import { access, mkdir, readdir } from 'node:fs/promises';
import { constants } from 'node:fs';
import { spawn } from 'node:child_process';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const projectRoot = path.resolve(__dirname, '..');
const localMavenRepo = path.join(projectRoot, '.m2', 'repository');

async function isExecutable(filePath) {
  if (!filePath) {
    return false;
  }

  try {
    await access(filePath, constants.X_OK);
    return true;
  } catch {
    return false;
  }
}

async function findMavenExecutable() {
  const platformIsWindows = process.platform === 'win32';
  const mvnExecutable = platformIsWindows ? 'mvn.cmd' : 'mvn';
  const homeDirectory = os.homedir();

  const candidates = [];

  if (process.env.MAVEN_HOME) {
    candidates.push(path.join(process.env.MAVEN_HOME, 'bin', mvnExecutable));
  }

  if (process.env.M2_HOME) {
    candidates.push(path.join(process.env.M2_HOME, 'bin', mvnExecutable));
  }

  if (process.env.PATH) {
    for (const entry of process.env.PATH.split(path.delimiter)) {
      candidates.push(path.join(entry, mvnExecutable));
    }
  }

  if (homeDirectory) {
    try {
      const homeEntries = await readdir(homeDirectory, { withFileTypes: true });
      for (const entry of homeEntries) {
        if (!entry.isDirectory() || !entry.name.startsWith('apache-maven-')) {
          continue;
        }
        candidates.push(path.join(homeDirectory, entry.name, 'bin', mvnExecutable));
      }
    } catch {
      // Ignore home scan failures and fall back to explicit candidates only.
    }
  }

  const checked = new Set();
  for (const candidate of candidates) {
    if (!candidate || checked.has(candidate)) {
      continue;
    }
    checked.add(candidate);
    if (await isExecutable(candidate)) {
      return candidate;
    }
  }

  return null;
}

async function main() {
  const mvnPath = await findMavenExecutable();
  if (!mvnPath) {
    console.error(
      'Maven executable not found. Install Maven or set MAVEN_HOME so Spring Boot scripts can run.'
    );
    process.exit(1);
  }

  const args = process.argv.slice(2);
  if (args.length < 2) {
    console.error('Usage: node scripts/run-spring-service.mjs <service-dir> <maven-goals...>');
    process.exit(1);
  }

  const serviceDir = args.shift();
  const serviceRoot = path.resolve(projectRoot, serviceDir);

  await mkdir(localMavenRepo, { recursive: true });

  const serviceEnv = { ...process.env };
  delete serviceEnv.DEBUG;

  const child = spawn(mvnPath, [`-Dmaven.repo.local=${localMavenRepo}`, ...args], {
    cwd: serviceRoot,
    stdio: 'inherit',
    env: serviceEnv
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

main().catch((error) => {
  console.error(error instanceof Error ? error.message : String(error));
  process.exit(1);
});
