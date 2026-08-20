// Chrome refuses to start unmuted audio that no user gesture asked for, and a headless browser
// has no gestures at all. Without this, every `element.play()` in a spec rejects with
// NotAllowedError and the whole Playing / advance / release / error surface is untestable — not
// failing, just permanently absent, which is the worse outcome.
//
// The Vite/Playwright lane passes the same flag (web/test/run-kotest.mjs). Both lanes run the
// same specs, so a flag in only one of them would make them disagree for a reason that has
// nothing to do with the code under test.
//
// This does NOT excuse the player from handling a refusal: real browsers — iOS Safari most
// strictly — still refuse, so HtmlAudioPlayer maps a rejected play() to PlaybackState.Error.
// The flag exists so the *other* paths can be reached, not to pretend refusal never happens.
config.set({
    customLaunchers: {
        ChromeHeadlessAutoplay: {
            base: 'ChromeHeadless',
            flags: ['--autoplay-policy=no-user-gesture-required'],
        },
    },
    browsers: ['ChromeHeadlessAutoplay'],
});
