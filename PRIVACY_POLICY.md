# LexiRead Privacy Policy (P1-6 disclosure)

LexiRead works fully offline for reading. Some features send data to third
parties — only after your explicit opt-in, and only the minimum needed.

## Cloud word lookups (opt-in, OFF by default)

Tapping a word while reading can fetch definitions, translations and AI
explanations from the network. When enabled, the selected fragment
(a single word plus its sentence) is sent to:

| Purpose       | Recipient                          | Data sent              |
|---------------|------------------------------------|------------------------|
| Definitions   | `api.dictionaryapi.dev`            | selected word          |
| Translation   | `api.mymemory.translated.net`      | selected word          |
| AI explanation| Google Gemini / OpenAI / Anthropic Claude / DeepSeek (only the provider you selected in Settings, only when you tap “AI explain”) | word + sentence |

No account data, no full book text, no reading history is ever transmitted.
Catalog search/downloads contact public book catalogues (Gutenberg, Open
Library, Internet Archive, Standard Ebooks, Google Books) — the search query
only.

## Your control

- First word tap shows a consent dialog (Allow / Stay offline).
- `Settings → Privacy & Cloud Lookups` toggles cloud lookups anytime.
- API keys you enter in Settings are stored only on your device
  (encrypted with AES-256-GCM, key in Android Keystore) and are sent only
  to the official API of the selected provider.

## Play Data Safety (for the release checklist)

- Collected: user-entered API keys (stored on device), tapped words/sentences
  (transmitted to the services above only with consent), search queries.
- Shared: with the API providers listed above, only while the feature is on.
- No data is sold. No advertising SDKs.
