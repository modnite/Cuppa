import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// The backend address the dev server proxies /api to. Overridable so you can
// point the UI at a remote/NAS instance or a different local port.
const target = process.env.VITE_API_TARGET ?? "http://localhost:8631";

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      "/api": {
        target,
        changeOrigin: true,
        configure: (proxy) => {
          proxy.on("error", (error, _request, response) => {
            console.error(`[vite proxy] ${error.message} -> ${target}`);
            if (response && !response.headersSent && "writeHead" in response) {
              response.writeHead(502, { "Content-Type": "application/json" });
              response.end(JSON.stringify({ error: "Cuppa backend is not reachable" }));
            }
          });
        },
      },
    },
  },
  build: {
    outDir: "dist",
    emptyOutDir: true,
  },
});
