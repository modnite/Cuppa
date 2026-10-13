import assert from "node:assert/strict";
import { test } from "node:test";
import { MdnsAdvertiser, mdnsAdvertiser } from "../src/mdns-internal.js";

/**
 * `MdnsAdvertiser`'s DNS encoding (`buildPacket`, `encodeName`, ...) is private
 * and only reachable by binding a UDP socket, so these tests cover the
 * non-networked surface: construction, the pre-start no-ops and `status()`.
 */

test("MdnsAdvertiser reports it has not started before start()", () => {
  const advertiser = new MdnsAdvertiser();
  assert.equal(advertiser.status(), "(mDNS advertiser not started)");
});

test("update() before start() is a no-op and does not throw", () => {
  const advertiser = new MdnsAdvertiser();
  advertiser.update([
    { name: "Office (Cuppa)", type: "_ipp._tcp", port: 631, txt: ["rp=printers/Office"], airprint: true },
  ]);
  assert.equal(advertiser.status(), "(mDNS advertiser not started)");
});

test("stop() before start() is a safe no-op", () => {
  const advertiser = new MdnsAdvertiser();
  advertiser.stop();
  assert.equal(advertiser.status(), "(mDNS advertiser not started)");
});

test("the shared advertiser is an MdnsAdvertiser", () => {
  assert.ok(mdnsAdvertiser instanceof MdnsAdvertiser);
});
