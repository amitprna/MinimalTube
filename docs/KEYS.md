# Keys and Google setup



Use **Settings → API keys**. Each API-key field has an info icon; values and optional descriptions are stored only on your device. Never put keys in source code, issues, screenshots or APK build flags.



## YouTube — required



1. Create or select your project in [Google Cloud Console](https://console.cloud.google.com/).

2. Enable **YouTube Data API v3** in APIs & Services → Library.

3. Open Credentials → Create credentials → API key.

4. Restrict the key to **YouTube Data API v3**. Configure application restrictions supported by your API/client and check quota alerts in Google Cloud. MinimalTube supplies its package and certificate headers for supported Android restrictions; those values appear in the info panel. An IP/server-restricted key cannot be assumed to work on a phone.

5. Paste the value into MinimalTube’s **YouTube API key** field and save.



The key identifies your project, not your YouTube account. Search and public channel/video metadata use this key directly; no hosted backend is needed. Mobile storage is not an unextractable vault: use API restrictions and revoke a compromised key.



## Google sign-in — optional



Open Account → **Import from Google**, choose your account and approve the requested access. No OAuth client ID, Cloud project or password needs to be entered in MinimalTube for sign-in. The developer configures the APK’s Google identity.



Read-only permission imports subscriptions. Subscribe/unsubscribe requests the broader `youtube.force-ssl` permission, which can also allow other account changes; MinimalTube uses it only for subscriptions. Revoke access at [Google connections](https://myaccount.google.com/connections).



Account operations use the app’s OAuth project quota; public search/refresh uses your YouTube key’s project. Google may require reauthorization. Testing builds can be limited to approved test accounts; public launch requires the maintainer to complete applicable Google verification.



Building your own APK? See [Developer Google setup](DEVELOPER_GOOGLE.md).



## Gemini summaries — optional



1. Open [Google AI Studio → API keys](https://aistudio.google.com/apikey).

2. Create a key in your project; enable billing/quota if required.

3. Restrict the key to **Generative Language API** and add it to MinimalTube’s **Gemini API key** field.

4. Keep `gemini-flash-latest` or enter an exact compatible model, such as `gemini-3.8-flash` (available when this guide was checked). The alias follows Google’s latest Flash and can change; pin a model for consistency.

5. Open a public video → **Get summary**. MinimalTube sends the URL and a request for top insights with high thinking. It does not download the video or scrape captions.



Google documents public YouTube URL input; private/unlisted videos are unsupported. Availability, duration limits, pricing and quotas depend on Google and your account. Responses omit internal thought parts. Insights may be inaccurate; check the video. High thinking can increase latency and cost.



Sources: [API key management](https://docs.cloud.google.com/docs/authentication/api-keys), [Gemini models](https://ai.google.dev/gemini-api/docs/models), [YouTube URL input](https://ai.google.dev/gemini-api/docs/video-understanding), [Thinking](https://ai.google.dev/gemini-api/docs/thinking).



Only the YouTube key and optional Gemini key are needed. Edit the existing field to replace a key.

