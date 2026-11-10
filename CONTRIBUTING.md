# Contributing

Keep the app focused: user-selected channels, official APIs/player, minimal UI. No extraction, ad blocking or downloads.

Run `node scripts/test-player-speed.cjs`, `:app:testDebugUnitTest` and `:app:lintDebug` before submitting changes. Test on a device for playback, fullscreen, Google consent, key storage, group drag, Undo and background scheduling. Use test fixtures, never real credentials in tests or bug reports.

For release builds, provide a keystore through environment variables: `GREENTUBE_KEYSTORE`, `GREENTUBE_STORE_PASSWORD`, `GREENTUBE_KEY_ALIAS`, `GREENTUBE_KEY_PASSWORD`, and configure `GREENTUBE_GOOGLE_WEB_CLIENT_ID` as described in [Developer Google setup](docs/DEVELOPER_GOOGLE.md), then run `:app:assembleRelease`. Earlier `VERDANT_*` names remain compatible. Keep signing material private; release updates require the same key. A self-built release needs its own matching Google Android OAuth client.

Do not commit `local.properties`, `.env`, credentials, signing files, build output or personal deployment settings.
