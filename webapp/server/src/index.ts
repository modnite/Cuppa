import { existsSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import cookie from "@fastify/cookie";
import multipart from "@fastify/multipart";
import fastifyStatic from "@fastify/static";
import Fastify from "fastify";
import { refreshAdvertisements } from "./advertise.js";
import { auth } from "./auth.js";
import { ENV } from "./env.js";
import { log } from "./logger.js";
import { registerRoutes } from "./routes.js";

const here = path.dirname(fileURLToPath(import.meta.url));

/** Finds the built front end, whether run from Docker or a local checkout. */
function resolveWebRoot(): string | null {
  const candidates = [
    process.env.CUPPA_WEB_ROOT,
    path.resolve(here, "../../web"),
    path.resolve(here, "../../web/dist"),
    path.resolve(process.cwd(), "web/dist"),
    path.resolve(process.cwd(), "../web/dist"),
  ].filter((candidate): candidate is string => Boolean(candidate));
  for (const candidate of candidates) {
    if (existsSync(path.join(candidate, "index.html"))) return candidate;
  }
  return null;
}

async function main(): Promise<void> {
  const app = Fastify({
    logger: false,
    trustProxy: true,
    bodyLimit: 4 * 1024 * 1024,
    // Keep sockets alive longer than any typical reverse proxy's idle timeout,
    // so a proxy never reuses a connection the backend has already closed.
    keepAliveTimeout: 72_000,
    connectionTimeout: 0,
    requestTimeout: 0,
  });

  await app.register(cookie);
  await app.register(multipart, { limits: { fileSize: 100 * 1024 * 1024 } });
  registerRoutes(app);

  // Apply a deploy-time admin password on first boot only.
  if (ENV.adminPassword && !auth.required) {
    auth.setPassword(ENV.adminPassword);
    log.info("Applied the admin password from CUPPA_ADMIN_PASSWORD");
  }

  const webRoot = resolveWebRoot();
  if (webRoot) {
    await app.register(fastifyStatic, { root: webRoot, prefix: "/" });
    app.setNotFoundHandler((request, reply) => {
      if (request.url.startsWith("/api/")) {
        reply.code(404).send({ error: "not found" });
        return;
      }
      reply.sendFile("index.html");
    });
    log.info(`Serving the web UI from ${webRoot}`);
  } else {
    log.warn("No built web UI found; serving the API only");
    app.get("/", async () => ({ name: "Cuppa", message: "Web UI assets are not installed in this image." }));
  }

  app.get("/healthz", async () => ({ ok: true }));

  // Return a predictable { error } body for the front end.
  app.setErrorHandler((error: Error & { statusCode?: number }, request, reply) => {
    log.warn(`API error on ${request.method} ${request.url}: ${error.message}`);
    const status = error.statusCode && error.statusCode >= 400 ? error.statusCode : 500;
    reply.code(status).send({ error: error.message });
  });

  // Keep Bonjour records in step with queues changed directly in CUPS.
  const timer = setInterval(() => {
    void refreshAdvertisements();
  }, 60_000);
  timer.unref();

  await app.listen({ host: "0.0.0.0", port: ENV.webPort });
  log.info(`Cuppa web UI listening on http://0.0.0.0:${ENV.webPort}`);

  // CUPS can take a moment to finish starting, so retry the first publish a
  // few times instead of waiting a full minute for the periodic refresh.
  let attempts = 0;
  const kick = async (): Promise<void> => {
    const ok = await refreshAdvertisements();
    attempts += 1;
    if (!ok && attempts < 20) {
      setTimeout(() => void kick(), 3000).unref();
    }
  };
  void kick();
}

main().catch((error) => {
  log.error("Fatal startup error", error);
  process.exit(1);
});
