import { execFile, spawn } from "node:child_process";
import { promisify } from "node:util";
import { ENV } from "./env.js";
import { log } from "./logger.js";

const execFileAsync = promisify(execFile);

export interface RunResult {
  stdout: string;
  stderr: string;
  code: number;
}

/** Environment every CUPS command runs with: stable locale, local scheduler. */
function commandEnv(extra?: NodeJS.ProcessEnv): NodeJS.ProcessEnv {
  return {
    ...process.env,
    LC_ALL: "C",
    LANG: "C",
    CUPS_SERVER: `${ENV.cupsHost}:${ENV.ippPort}`,
    ...extra,
  };
}

/**
 * Runs a CUPS command-line tool. Output is forced to the C locale so parsing is
 * stable regardless of the NAS's language, and every call has a timeout so a
 * wedged backend can never hang a request.
 */
export async function run(
  command: string,
  args: string[],
  opts: { timeoutMs?: number } = {}
): Promise<RunResult> {
  const timeout = opts.timeoutMs ?? 30_000;
  if (ENV.debug) log.debug(`exec ${command} ${args.join(" ")}`);
  try {
    const result = await execFileAsync(command, args, {
      timeout,
      maxBuffer: 8 * 1024 * 1024,
      env: commandEnv(),
    });
    return { stdout: result.stdout ?? "", stderr: result.stderr ?? "", code: 0 };
  } catch (error) {
    const err = error as { stdout?: string; stderr?: string; code?: number | string; message?: string };
    const code = typeof err.code === "number" ? err.code : 1;
    const stderr = (err.stderr ?? "").trim() || (err.message ?? "");
    log.warn(`command failed (${code}): ${command} ${args.join(" ")}: ${stderr}`);
    return { stdout: err.stdout ?? "", stderr, code };
  }
}

/**
 * Runs a command with a binary payload on stdin and captures its output.
 *
 * `execFile` cannot feed stdin, so this uses `spawn` directly. It is what drives
 * CUPS backends during the USB self-test: the backend is handed the job bytes on
 * fd 0 exactly as cupsd would hand them over.
 */
export async function runWithInput(
  command: string,
  args: string[],
  input: Buffer,
  opts: { timeoutMs?: number; env?: NodeJS.ProcessEnv } = {}
): Promise<RunResult> {
  const timeout = opts.timeoutMs ?? 30_000;
  if (ENV.debug) log.debug(`exec(input) ${command} ${args.join(" ")} (${input.length} bytes)`);
  return new Promise<RunResult>((resolve) => {
    let stdout = "";
    let stderr = "";
    let settled = false;

    const child = spawn(command, args, { env: commandEnv(opts.env), detached: true });
    const timer = setTimeout(() => {
      if (settled) return;
      // Kill the whole group: the paced backend spawns the real USB backend as a
      // child, and an orphan holding the device open would wedge the next job.
      try {
        if (child.pid) process.kill(-child.pid, "SIGKILL");
        else child.kill("SIGKILL");
      } catch {
        child.kill("SIGKILL");
      }
    }, timeout);

    const finish = (code: number): void => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      resolve({ stdout, stderr, code });
    };

    child.stdout?.on("data", (chunk: Buffer) => {
      stdout += chunk.toString();
    });
    child.stderr?.on("data", (chunk: Buffer) => {
      stderr += chunk.toString();
    });
    child.on("error", (error) => {
      stderr += error.message;
      finish(127);
    });
    child.on("close", (code) => finish(code ?? 1));

    if (child.stdin) {
      child.stdin.on("error", () => undefined);
      child.stdin.end(input);
    }
  });
}

/** Runs a CUPS command and throws with stderr when it fails. */
export async function runOrThrow(
  command: string,
  args: string[],
  opts: { timeoutMs?: number } = {}
): Promise<string> {
  const result = await run(command, args, opts);
  if (result.code !== 0) {
    throw new Error(result.stderr || `${command} exited with ${result.code}`);
  }
  return result.stdout;
}
