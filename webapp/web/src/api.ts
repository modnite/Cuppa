import type { AuthStatus, Device, Job, Printer, Settings, Status, ThermalConfig } from "./types";

export class ApiError extends Error {
  status: number;
  /** True for a network failure or a 5xx from a proxy — worth retrying, not worth alarming the user. */
  transient: boolean;
  constructor(message: string, status: number) {
    super(message);
    this.status = status;
    this.transient = status === 0 || status >= 500;
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  let response: Response;
  // Only declare a JSON content type when there is actually a JSON body. A
  // bodyless POST with Content-Type: application/json is rejected by the server
  // with 400 (empty JSON body), which is what broke Test Print and friends.
  const isForm = init?.body instanceof FormData;
  const hasBody = init?.body !== undefined && init?.body !== null;
  try {
    response = await fetch(path, {
      credentials: "same-origin",
      ...init,
      headers: isForm || !hasBody ? init?.headers : { "Content-Type": "application/json", ...init?.headers },
    });
  } catch (error) {
    // fetch only rejects on a genuine network/connection failure.
    throw new ApiError(error instanceof Error ? error.message : "Network error", 0);
  }
  const text = await response.text();
  let data: unknown = {};
  try {
    data = text ? JSON.parse(text) : {};
  } catch {
    data = {};
  }
  if (!response.ok) {
    const message = (data as { error?: string }).error ?? `Request failed (${response.status})`;
    throw new ApiError(message, response.status);
  }
  return data as T;
}

export const api = {
  authStatus: () => request<AuthStatus>("/api/auth/status"),
  login: (password: string) =>
    request<{ ok: boolean }>("/api/auth/login", { method: "POST", body: JSON.stringify({ password }) }),
  logout: () => request<{ ok: boolean }>("/api/auth/logout", { method: "POST" }),

  status: () => request<Status>("/api/status"),

  printers: () => request<{ printers: Printer[] }>("/api/printers").then((r) => r.printers),
  addPrinter: (input: {
    deviceUri: string;
    displayName: string;
    location?: string;
    driver?: "auto" | "everywhere" | "raw";
    shared?: boolean;
    thermal?: Partial<ThermalConfig> | null;
  }) => request<{ ok: boolean; queue: string }>("/api/printers", { method: "POST", body: JSON.stringify(input) }),
  updateThermal: (queue: string, patch: Partial<ThermalConfig>) =>
    request<{ ok: boolean }>(`/api/printers/${encodeURIComponent(queue)}/thermal`, {
      method: "PATCH",
      body: JSON.stringify(patch),
    }),
  updatePrinter: (queue: string, patch: { displayName?: string; location?: string; shared?: boolean }) =>
    request<{ ok: boolean }>(`/api/printers/${encodeURIComponent(queue)}`, {
      method: "PATCH",
      body: JSON.stringify(patch),
    }),
  removePrinter: (queue: string) =>
    request<{ ok: boolean }>(`/api/printers/${encodeURIComponent(queue)}`, { method: "DELETE" }),
  setDefault: (queue: string) =>
    request<{ ok: boolean }>(`/api/printers/${encodeURIComponent(queue)}/default`, { method: "POST" }),
  testPrint: (queue: string) =>
    request<{ ok: boolean }>(`/api/printers/${encodeURIComponent(queue)}/test`, { method: "POST" }),
  setEnabled: (queue: string, enabled: boolean) =>
    request<{ ok: boolean }>(`/api/printers/${encodeURIComponent(queue)}/enable`, {
      method: "POST",
      body: JSON.stringify({ enabled }),
    }),
  setAccepting: (queue: string, accepting: boolean) =>
    request<{ ok: boolean }>(`/api/printers/${encodeURIComponent(queue)}/accept`, {
      method: "POST",
      body: JSON.stringify({ accepting }),
    }),
  uploadPrint: (queue: string, file: File) => {
    const form = new FormData();
    form.append("file", file);
    return request<{ ok: boolean }>(`/api/printers/${encodeURIComponent(queue)}/print`, {
      method: "POST",
      body: form,
    });
  },

  discover: () => request<{ devices: Device[] }>("/api/discover").then((r) => r.devices),

  jobs: (scope: "active" | "history" | "all") =>
    request<{ jobs: Job[] }>(`/api/jobs?scope=${scope}`).then((r) => r.jobs),
  cancelJob: (id: number) => request<{ ok: boolean }>(`/api/jobs/${id}/cancel`, { method: "POST" }),

  settings: () => request<Settings>("/api/settings"),
  updateSettings: (patch: Partial<Pick<Settings, "advertiseEnabled" | "airprintCompat" | "tlsEnabled">>) =>
    request<{ ok: boolean }>("/api/settings", { method: "PATCH", body: JSON.stringify(patch) }),
  setPassword: (password: string) =>
    request<{ ok: boolean; authRequired: boolean }>("/api/settings/password", {
      method: "POST",
      body: JSON.stringify({ password }),
    }),
};
