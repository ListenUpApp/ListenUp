// This lane is slow by nature and the slowness is real, not a race.
//
// Every test in it builds its own world: a fresh Worker, a wasm SQLite instance, and its
// own OPFS database. That is deliberate — it is what makes these tests runtime-proof
// Room-on-web rather than mock it — but it means the browser can go quiet for a long
// stretch while a worker boots.
//
// Karma's browserNoActivityTimeout defaults to 30s. When it is exceeded the browser is
// disconnected mid-run and the lane reports "Disconnected, because no message in 30000 ms"
// with no test named and no report written — which reads like a hang and is not one.
//
// NOT a way to make a flaky test pass. If this ever needs raising again, ask first whether
// a spec is leaking workers rather than reaching for a bigger number.
config.set({
    browserNoActivityTimeout: 120000,
    browserDisconnectTimeout: 30000,
});
