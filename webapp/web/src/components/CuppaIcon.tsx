import icon from "../assets/cuppa-icon.svg?raw";

/**
 * The Cuppa mark, inlined so its background (the `.s0` path) can follow the
 * chosen accent colour instead of a fixed teal. A plain <img src="/icon.svg">
 * cannot inherit the page's CSS variables.
 */
export function CuppaIcon({
  size = 30,
  radius = 8,
  className = "",
}: {
  size?: number;
  radius?: number;
  className?: string;
}) {
  return (
    <span
      className={`cuppa-icon ${className}`.trim()}
      style={{ width: size, height: size, borderRadius: radius }}
      dangerouslySetInnerHTML={{ __html: icon }}
    />
  );
}
