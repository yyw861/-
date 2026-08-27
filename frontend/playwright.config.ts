import { rmSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

import { defineConfig, devices } from '@playwright/test'

const frontendDir = fileURLToPath(new URL('.', import.meta.url))
const backendDir = fileURLToPath(new URL('../backend', import.meta.url))
const e2eDataDir = fileURLToPath(new URL('../backend/target/e2e-data', import.meta.url))
const browserExecutable = process.env.SPORTSHOP_BROWSER_EXECUTABLE

// Workers load this config too; only the coordinator may reset data before web servers start.
// The E2E suite owns only this exact target directory. Development data under backend/data is untouched.
// Bounded retries tolerate Windows briefly retaining SQLite WAL/SHM handles after the prior server exits.
if (process.env.TEST_WORKER_INDEX === undefined) {
  rmSync(e2eDataDir, { recursive: true, force: true, maxRetries: 3, retryDelay: 200 })
}

export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  workers: 1,
  reporter: 'list',
  use: {
    baseURL: 'http://127.0.0.1:5173',
    trace: 'retain-on-failure',
  },
  projects: [
    {
      name: 'chromium',
      use: {
        ...devices['Desktop Chrome'],
        ...(browserExecutable ? { launchOptions: { executablePath: browserExecutable } } : {}),
      },
    },
  ],
  webServer: [
    {
      command: 'mvn spring-boot:run',
      cwd: backendDir,
      env: {
        ...process.env,
        SPORTSHOP_DATA_DIR: './target/e2e-data',
        JAVA_HOME: process.env.SPORTSHOP_JAVA_HOME ?? process.env.JAVA_HOME ?? '',
      },
      url: 'http://127.0.0.1:8080/api/health',
      timeout: 120_000,
      reuseExistingServer: false,
    },
    {
      command: 'npm run dev -- --host 127.0.0.1',
      cwd: frontendDir,
      url: 'http://127.0.0.1:5173',
      timeout: 60_000,
      reuseExistingServer: false,
    },
  ],
})
