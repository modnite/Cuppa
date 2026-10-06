import { useState } from "react";
import { api, ApiError } from "../api";
import { Spinner } from "./ui";
import { CuppaIcon } from "./CuppaIcon";
import { LockIcon } from "./Icons";

export function Login({ onSuccess }: { onSuccess: () => void }) {
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setError("");
    try {
      await api.login(password);
      onSuccess();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Could not sign in");
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="login-wrap">
      <form className="card login-card" onSubmit={submit}>
        <CuppaIcon size={64} radius={15} />
        <h1>Cuppa</h1>
        <p>Enter the admin password to manage this print server.</p>
        <div className="field">
          <input
            className="input"
            type="password"
            autoFocus
            placeholder="Admin password"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
          />
        </div>
        {error ? <div className="pill bad mb">{error}</div> : null}
        <button className="btn btn-primary" style={{ width: "100%" }} disabled={busy || !password}>
          {busy ? <Spinner /> : <LockIcon size={15} />}
          Sign in
        </button>
      </form>
    </div>
  );
}
