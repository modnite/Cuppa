import { createWriteStream } from "node:fs";
import os from "node:os";
import path from "node:path";
import { pipeline } from "node:stream/promises";
import { unlink } from "node:fs/promises";
import type { FastifyInstance } from "fastify";
import { refreshAdvertisements } from "./advertise.js";
import { auth, clearSessionCookie, requireAuth, setSessionCookie, sessionToken } from "./auth.js";
import {
  addPrinter,
  cancelJob,
  collectDiagnostics,
  discoverDevices,
  listDrivers,
  listJobs,
  listPrinters,
  printFile,
  probeCups,
  removePrinter,
  renamePrinter,
  setAccepting,
  setDefaultPrinter,
  setEnabled,
  setLocation,
  setShared,
  testPrint,
  updateThermalSettings,
} from "./cups.js";
import { ENV } from "./env.js";
import { log } from "./logger.js";
import { primaryIPv4, hostName } from "./network.js";
import { store } from "./store.js";
import type { ThermalConfig } from "./thermal/config.js";
import type { StatusView } from "./types.js";

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

function param(request: { params: unknown }, key: string): string {
  return String((request.params as Record<string, unknown>)[key] ?? "");
}

export function registerRoutes(app: FastifyInstance): void {
  // ---- Auth ----
  app.get("/api/auth/status", async (request) => ({
    required: auth.required,
    authenticated: !auth.required || auth.isValid(sessionToken(request)),
  }));

  app.post("/api/auth/login", async (request, reply) => {
    const body = (request.body ?? {}) as { password?: string };
    if (!auth.required) return { ok: true };
    if (!auth.verify(body.password ?? "")) {
      reply.code(401);
      return { error: "Incorrect password" };
    }
    setSessionCookie(reply, auth.issue());
    return { ok: true };
  });

  app.post("/api/auth/logout", async (request, reply) => {
    auth.revoke(sessionToken(request));
    clearSessionCookie(reply);
    return { ok: true };
  });

  // Everything below requires the admin password when one is set.
  app.addHook("preHandler", async (request, reply) => {
    if (!request.url.startsWith("/api/")) return;
    if (request.url.startsWith("/api/auth/")) return;
    if (!requireAuth(request, reply)) return reply;
  });

  // ---- Status ----
  app.get("/api/status", async () => {
    const probe = await probeCups();
    let printers = 0;
    let shared = 0;
    let activeJobs = 0;
    try {
      const list = await listPrinters();
      printers = list.length;
      shared = list.filter((printer) => printer.shared).length;
      activeJobs = (await listJobs("active")).length;
    } catch (error) {
      log.debug(`Status could not read printers: ${errorMessage(error)}`);
    }

    const status: StatusView = {
      ok: probe.running,
      cupsVersion: probe.version,
      cupsRunning: probe.running,
      host: hostName(),
      ip: primaryIPv4(),
      ippPort: ENV.ippPort,
      webPort: ENV.webPort,
      advertiseEnabled: store.settings.advertiseEnabled,
      airprintCompat: store.settings.airprintCompat,
      tlsEnabled: store.settings.tlsEnabled,
      authRequired: auth.required,
      printerCount: printers,
      sharedCount: shared,
      activeJobs,
      uptimeSeconds: Math.round(process.uptime()),
    };
    return status;
  });

  // ---- Printers ----
  app.get("/api/printers", async () => ({ printers: await listPrinters() }));

  app.post("/api/printers", async (request, reply) => {
    const body = (request.body ?? {}) as {
      deviceUri?: string;
      displayName?: string;
      location?: string;
      driver?: string;
      shared?: boolean;
      makeAndModel?: string;
      thermal?: Partial<ThermalConfig> | null;
    };
    try {
      const queue = await addPrinter({
        deviceUri: body.deviceUri ?? "",
        displayName: body.displayName ?? "",
        location: body.location,
        driver: body.driver ?? "auto",
        shared: body.shared ?? true,
        makeAndModel: body.makeAndModel,
        thermal: body.thermal ?? null,
      });
      await refreshAdvertisements();
      return { ok: true, queue };
    } catch (error) {
      reply.code(400);
      return { error: errorMessage(error) };
    }
  });

  app.patch("/api/printers/:queue", async (request, reply) => {
    const queue = param(request, "queue");
    const body = (request.body ?? {}) as { displayName?: string; location?: string; shared?: boolean };
    try {
      if (body.displayName !== undefined) await renamePrinter(queue, body.displayName);
      if (body.location !== undefined) await setLocation(queue, body.location);
      if (body.shared !== undefined) await setShared(queue, body.shared);
      await refreshAdvertisements();
      return { ok: true };
    } catch (error) {
      reply.code(400);
      return { error: errorMessage(error) };
    }
  });

  app.delete("/api/printers/:queue", async (request, reply) => {
    try {
      await removePrinter(param(request, "queue"));
      await refreshAdvertisements();
      return { ok: true };
    } catch (error) {
      reply.code(400);
      return { error: errorMessage(error) };
    }
  });

  app.patch("/api/printers/:queue/thermal", async (request, reply) => {
    const body = (request.body ?? {}) as Partial<ThermalConfig>;
    try {
      await updateThermalSettings(param(request, "queue"), body);
      await refreshAdvertisements();
      return { ok: true };
    } catch (error) {
      reply.code(400);
      return { error: errorMessage(error) };
    }
  });

  app.post("/api/printers/:queue/default", async (request, reply) => {
    try {
      await setDefaultPrinter(param(request, "queue"));
      return { ok: true };
    } catch (error) {
      reply.code(400);
      return { error: errorMessage(error) };
    }
  });

  app.post("/api/printers/:queue/test", async (request, reply) => {
    try {
      await testPrint(param(request, "queue"));
      return { ok: true };
    } catch (error) {
      reply.code(400);
      return { error: errorMessage(error) };
    }
  });

  app.post("/api/printers/:queue/enable", async (request, reply) => {
    const body = (request.body ?? {}) as { enabled?: boolean };
    try {
      await setEnabled(param(request, "queue"), body.enabled ?? true);
      return { ok: true };
    } catch (error) {
      reply.code(400);
      return { error: errorMessage(error) };
    }
  });

  app.post("/api/printers/:queue/accept", async (request, reply) => {
    const body = (request.body ?? {}) as { accepting?: boolean };
    try {
      await setAccepting(param(request, "queue"), body.accepting ?? true);
      return { ok: true };
    } catch (error) {
      reply.code(400);
      return { error: errorMessage(error) };
    }
  });

  app.post("/api/printers/:queue/print", async (request, reply) => {
    const queue = param(request, "queue");
    const upload = await request.file({ limits: { fileSize: 100 * 1024 * 1024 } });
    if (!upload) {
      reply.code(400);
      return { error: "No file was uploaded" };
    }
    const temporary = path.join(os.tmpdir(), `cuppa-upload-${Date.now()}-${Math.random().toString(16).slice(2)}`);
    try {
      await pipeline(upload.file, createWriteStream(temporary));
      await printFile(queue, temporary, upload.filename || "Cuppa Upload");
      return { ok: true };
    } catch (error) {
      reply.code(400);
      return { error: errorMessage(error) };
    } finally {
      await unlink(temporary).catch(() => undefined);
    }
  });

  // ---- Discovery & drivers ----
  app.get("/api/discover", async () => ({ devices: await discoverDevices() }));
  app.get("/api/drivers", async () => ({ drivers: await listDrivers() }));
  app.get("/api/diagnostics", async () => collectDiagnostics());

  // ---- Jobs ----
  app.get("/api/jobs", async (request) => {
    const scope = String((request.query as { scope?: string }).scope ?? "all");
    const normalized = scope === "active" || scope === "history" ? scope : "all";
    return { jobs: await listJobs(normalized) };
  });

  app.post("/api/jobs/:id/cancel", async (request, reply) => {
    const id = Number.parseInt(param(request, "id"), 10);
    if (!Number.isFinite(id)) {
      reply.code(400);
      return { error: "Invalid job id" };
    }
    try {
      await cancelJob(id);
      return { ok: true };
    } catch (error) {
      reply.code(400);
      return { error: errorMessage(error) };
    }
  });

  // ---- Settings ----
  app.get("/api/settings", async () => ({
    advertiseEnabled: store.settings.advertiseEnabled,
    airprintCompat: store.settings.airprintCompat,
    tlsEnabled: store.settings.tlsEnabled,
    authRequired: auth.required,
  }));

  app.patch("/api/settings", async (request, reply) => {
    const body = (request.body ?? {}) as {
      advertiseEnabled?: boolean;
      airprintCompat?: boolean;
      tlsEnabled?: boolean;
    };
    try {
      store.updateSettings({
        ...(body.advertiseEnabled !== undefined ? { advertiseEnabled: body.advertiseEnabled } : {}),
        ...(body.airprintCompat !== undefined ? { airprintCompat: body.airprintCompat } : {}),
        ...(body.tlsEnabled !== undefined ? { tlsEnabled: body.tlsEnabled } : {}),
      });
      await refreshAdvertisements();
      return { ok: true };
    } catch (error) {
      reply.code(400);
      return { error: errorMessage(error) };
    }
  });

  app.post("/api/settings/password", async (request, reply) => {
    const body = (request.body ?? {}) as { password?: string };
    try {
      auth.setPassword((body.password ?? "").trim());
      clearSessionCookie(reply);
      return { ok: true, authRequired: auth.required };
    } catch (error) {
      reply.code(400);
      return { error: errorMessage(error) };
    }
  });
}
