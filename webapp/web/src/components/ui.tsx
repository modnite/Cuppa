import { useEffect, useState, type ReactNode } from "react";
import { CheckIcon, CopyIcon, XIcon } from "./Icons";

export function Spinner() {
  return <span className="spinner" />;
}

export function Modal({
  title,
  onClose,
  children,
  footer,
  width,
}: {
  title: string;
  onClose: () => void;
  children: ReactNode;
  footer?: ReactNode;
  width?: number;
}) {
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") onClose();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  return (
    <div
      className="overlay"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget) onClose();
      }}
    >
      <div className="modal" style={width ? { maxWidth: width } : undefined}>
        <div className="modal-head">
          <div className="modal-title">{title}</div>
          <button
            className="btn btn-ghost btn-icon"
            onClick={onClose}
            aria-label="Close"
            data-tooltip="Close"
            data-tooltip-pos="down"
          >
            <XIcon />
          </button>
        </div>
        <div className="modal-body">{children}</div>
        {footer ? <div className="modal-foot">{footer}</div> : null}
      </div>
    </div>
  );
}

export function Toggle({
  checked,
  onChange,
  disabled,
  label,
}: {
  checked: boolean;
  onChange: (value: boolean) => void;
  disabled?: boolean;
  label?: string;
}) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      aria-label={label}
      data-tooltip={label}
      className={`switch${checked ? " on" : ""}`}
      onClick={() => onChange(!checked)}
      disabled={disabled}
    />
  );
}

export function CopyButton({ value, label = "Copy" }: { value: string; label?: string }) {
  const [state, setState] = useState<"idle" | "copied" | "failed">("idle");

  return (
    <button
      type="button"
      className="printer-uri"
      title={value}
      onClick={async () => {
        const ok = await copyText(value);
        setState(ok ? "copied" : "failed");
        setTimeout(() => setState("idle"), 1600);
      }}
    >
      {state === "copied" ? <CheckIcon /> : <CopyIcon />}
      <span>
        {state === "copied"
          ? "Copied"
          : state === "failed"
            ? "Press and hold to copy"
            : label === "Copy"
              ? value
              : label}
      </span>
    </button>
  );
}

/**
 * Copies text in a way that also works on mobile browsers.
 *
 * `navigator.clipboard` only exists in a secure context, so a Cuppa server opened
 * at plain `http://nas-ip:8631` from a phone cannot use it. The legacy
 * `execCommand("copy")` path still works there with a user gesture, so fall back
 * to a hidden textarea.
 */
export async function copyText(text: string): Promise<boolean> {
  if (navigator.clipboard && window.isSecureContext) {
    try {
      await navigator.clipboard.writeText(text);
      return true;
    } catch {
      // Fall through to the legacy path.
    }
  }
  try {
    const area = document.createElement("textarea");
    area.value = text;
    area.setAttribute("readonly", "");
    area.style.position = "fixed";
    area.style.top = "-1000px";
    area.style.opacity = "0";
    document.body.appendChild(area);
    area.focus();
    area.select();
    area.setSelectionRange(0, text.length);
    const ok = document.execCommand("copy");
    document.body.removeChild(area);
    return ok;
  } catch {
    return false;
  }
}

export interface ToastMessage {
  id: number;
  text: string;
  kind: "info" | "success" | "error";
}

export function Toasts({ items }: { items: ToastMessage[] }) {
  return (
    <div className="toasts">
      {items.map((toast) => (
        <div key={toast.id} className={`toast ${toast.kind}`}>
          {toast.text}
        </div>
      ))}
    </div>
  );
}

export function Segmented<T extends string>({
  value,
  onChange,
  options,
}: {
  value: T;
  onChange: (value: T) => void;
  options: Array<{ value: T; label: string }>;
}) {
  return (
    <div className="segmented">
      {options.map((option) => (
        <button
          key={option.value}
          className={option.value === value ? "active" : ""}
          onClick={() => onChange(option.value)}
        >
          {option.label}
        </button>
      ))}
    </div>
  );
}

export function StatusPill({ state, stateLabel, offline }: { state: number; stateLabel: string; offline?: boolean }) {
  const cls = offline ? "warn" : state === 3 ? "ok" : state === 4 ? "info" : state === 5 ? "bad" : "";
  return (
    <span className={`pill ${cls}`}>
      <span className="dot" />
      {offline ? "Offline" : stateLabel}
    </span>
  );
}

export function EmptyState({ icon, title, children }: { icon: ReactNode; title: string; children?: ReactNode }) {
  return (
    <div className="empty">
      {icon}
      <h3>{title}</h3>
      <div className="muted">{children}</div>
    </div>
  );
}
