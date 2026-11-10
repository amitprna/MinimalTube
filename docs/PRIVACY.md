# Privacy

MinimalTube has no analytics or developer-operated backend in its default setup.

- Your YouTube key, searches and channel/video requests go directly to Google’s official APIs. Thumbnails connect to Google/YouTube image servers.
- The official embedded player connects to YouTube. It can show ads and store cookies/site data. MinimalTube does not intercept media or remove advertising.
- Optional Google sign-in supplies the account identity to the app. The selected email is stored locally; access tokens are kept in memory. Google handles account selection and consent. No password is collected.
- Get summary sends the selected public video URL and an insights prompt to Gemini with your key. The 300 most recent summaries are kept on your device. Google’s API data-use terms apply.
- Groups, filters, subscriptions, feed, Watch Later and Bin are local. API keys use Android Keystore encryption; app backup/device-transfer rules exclude app data. Settings disables screenshots to avoid key capture.
- API metadata is refreshed or cleared within 30 days. User organisation and saved/suppressed video IDs remain. The Bin empties 24 hours after a video is binned; the IDs of binned and dismissed videos remain so they do not return to Home. Watch positions are kept on your device for videos you watched for at least two minutes.
- Clearing image/search cache keeps groups and saved lists. Signing out keeps local organisation and removes the account connection. Uninstalling removes local app data. Android’s Clear storage also removes app data.

Remove individual keys in Settings. Revoke Google access at [Account connections](https://myaccount.google.com/connections). Review [Google Privacy Policy](https://policies.google.com/privacy), [YouTube terms](https://www.youtube.com/t/terms) and [Gemini API terms](https://ai.google.dev/gemini-api/terms).
