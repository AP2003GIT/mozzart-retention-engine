import { spawn } from 'node:child_process';
import { readdir, mkdir } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const projectRoot = path.resolve(__dirname, '..');
const sourceRoot = path.join(projectRoot, 'java-backend', 'src');
const buildRoot = path.join(projectRoot, 'java-backend', 'build', 'classes');
const javacBin = process.platform === 'win32' ? 'javac.exe' : 'javac';
const javaBin = process.platform === 'win32' ? 'java.exe' : 'java';

async function collectJavaFiles(directory) {
  const entries = await readdir(directory, { withFileTypes: true });
  const files = [];

  for (const entry of entries) {
    const entryPath = path.join(directory, entry.name);
    if (entry.isDirectory()) {
      files.push(...(await collectJavaFiles(entryPath)));
      continue;
    }

    if (entry.isFile() && entry.name.endsWith('.java')) {
      files.push(entryPath);
    }
  }

  return files;
}

function run(command, args, options = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, {
      stdio: 'inherit',
      ...options
    });

    child.on('error', reject);
    child.on('exit', (code, signal) => {
      if (signal) {
        reject(new Error(`${command} exited from signal ${signal}`));
        return;
      }

      if (code !== 0) {
        reject(new Error(`${command} exited with code ${code}`));
        return;
      }

      resolve();
    });
  });
}

async function main() {
  await mkdir(buildRoot, { recursive: true });
  const javaFiles = await collectJavaFiles(sourceRoot);

  if (javaFiles.length === 0) {
    throw new Error('No Java backend source files found.');
  }

  await run(javacBin, ['-d', buildRoot, ...javaFiles], {
    cwd: projectRoot
  });

  if (process.argv.includes('--compile-only')) {
    return;
  }

  const backend = spawn(
    javaBin,
    ['-cp', buildRoot, 'com.mozzart.retention.RetentionApplication'],
    {
      cwd: projectRoot,
      stdio: 'inherit',
      env: process.env
    }
  );

  const stopBackend = () => {
    if (!backend.killed) {
      backend.kill('SIGTERM');
    }
  };

  process.on('SIGINT', stopBackend);
  process.on('SIGTERM', stopBackend);

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
