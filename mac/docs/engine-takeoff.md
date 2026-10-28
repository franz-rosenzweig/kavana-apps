# The change I need in the web engine: `E.takeOff`

**What for.** The Mac app asks "count them?" when I come back after more than two minutes away in the middle of a block. "Take them off" has to move the block's end later by those minutes, the way a pause would have. The engine has no way to do that: `E.extend` only adds time after a block has ended, and `E.pause` can only pause from now.

**Why it has to be in the engine.** The end ping, the check-ins still to come and the minutes counted are all the engine's. If the Mac app only changed its own clock, the end ping would still fire at the old time and the doc would count the wrong minutes.

**The change** is [engine-takeoff.patch](engine-takeoff.patch), 15 lines in `web/engine.js`, right after `E.resume`:

```js
E.takeOff = (doc, from, to, now) => {
  const b = E.running(doc);
  const d = Math.round(Number(to) - Number(from));
  if (!b || b.paused || !(d > 0) || Number(from) < b.start || Number(to) > now) bad('takeOff');
  b.end += d;
  for (const p of b.plan) p.at += d;
  b.pausedTotal = (b.pausedTotal || 0) + d;
  touch(doc, b, now);
  return b;
};
```

It is what `resumeAt` does for a pause: the end and every ping still to come move later, and the time is left out of the time on the block (`pausedTotal`). It is refused when no block is running, when the block is paused, and when the gap isn't inside the block. The kitten stays: being away isn't drifting.

**Tests.** `mac/tools/test-takeoff.js` applies the patch to a copy of the synced engine in a temporary folder and checks all of the above (10 checks), and `mac/selftest.sh core` also runs the Mac's bridge against the patched engine (`takeOff` op, `state.caps.takeOff`). For the web repo's own `tools/test-engine.js`, the same checks would do.

**On the Mac side** nothing else needs to change: `bridge.js` already has the `takeOff` op and `state.caps.takeOff`, and the popover shows "Take them off" only when the engine says it can. Once the web engine has it, `mac/tools/sync-web.sh` brings it in.

## A second, smaller one: break lengths

The engine only starts breaks of 5, 10 or 15 minutes (`E.BREAKS = [5, 10, 15]`, checked in `E.startBreak`). The Mac's cycles
want a 17-minute break (52 / 17), a 20-minute one (Deep work, 90 / 20) and, in Flowtime, a break a fifth of the stretch you
just worked, up to 25. Adding `17, 20, 25` to `E.BREAKS` would do it (the web's own break buttons would show them too, so that
is your call).

Until then the Mac starts the longest engine break that fits (15 for a 17 or 20) and keeps the rest of the break on its own
clock: the full-screen break, the menu bar and the popover run to the full length, and the engine's early "Break's over" ping
is held back until the Mac's own end. Nothing else about the doc changes. When the engine allows the length, the Mac's own
clock simply isn't needed and `Store.startBreak` passes it straight through.
