# Google sign-in: developer setup

End users tap Sign in with Google and select an account. They must never be asked to create an OAuth client or paste a client ID.

1. Configure Google Auth Platform branding and audience in your Cloud project. Enable YouTube Data API v3.
2. Register an Android OAuth client for the APK package and signing SHA-1. Obtain them using `apksigner verify --print-certs` and your Gradle application ID.
3. Create a Web OAuth client in that same project. Credential Manager uses its public client ID; do not embed a client secret.
4. Provide `GREENTUBE_GOOGLE_WEB_CLIENT_ID` as an environment variable or Gradle property when building. The ID is compiled into `BuildConfig.GOOGLE_WEB_CLIENT_ID`. The repository contains no project-specific identifier.
5. Configure `youtube.readonly` and `youtube.force-ssl`. The app requests read permission for import and write permission only for subscription changes.
6. For testing, add permitted test accounts. Before public launch, complete Google’s branding/scope verification and production requirements. Bundling a client ID does not remove Google’s audience restrictions.

Release builds fail if the developer forgets the client ID. Debug builds without it remain usable with local subscriptions and API keys; Google sign-in reports that a configured build is needed. There is no user-side OAuth override.

Google allows each Android package/signing-SHA pairing in only one project. Forks generally need their own signing certificate and OAuth project. Do not replace the signing key of an existing release: updates need that certificate to preserve installations.

The client ID is public configuration. YouTube/Gemini API keys, OAuth client secrets and signing keys must not be committed or compiled into the APK.

Sources: [Sign in with Google](https://developer.android.com/identity/sign-in/credential-manager-siwg), [OAuth production requirements](https://developers.google.com/identity/protocols/oauth2/production-readiness/policy-compliance), [Android client uniqueness](https://support.google.com/firebase/answer/6401008?hl=en).
