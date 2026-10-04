import { execFile } from "node:child_process";
import { promisify } from "node:util";
import { ENV } from "./env.js";
import { log } from "./logger.js";

const execFileAsync = promisify(execFile);

export interface RunResult {
  stdout: string;
  stderr: string;
  code: number;
}

/**
 * Runs a CUPS command-line tool. Output is forced to the C locale so parsing is
 * stable regardless of the NAS's language, and every call has a timeout so a
 * wedged backend can never hang a request.
 */
export async function run(
  command: string,
  args: string[],
  opts: { timeoutMs?: number; input?: string } = {}
): Promise<RunResult> {
  const timeout = opts.timeoutMs ?? 30_000;
  if (ENV.debug) log.debug(`exec ${command} ${args.join(" ")}`);
  try {
    const result = await execFileAsync(command, args, {
      timeout,
      maxBuffer: 8 * 1024 * 1024,
      env: { ...process.env, LC_ALL: "C", LANG: "C", CUPS_SERVER: `${ENV.cupsHost}:${ENV.ippPort}` },
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
