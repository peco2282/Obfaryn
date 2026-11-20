# Documentation translations

English KDoc/Javadoc is the sole source of truth. Translation catalogs contain only
localized natural-language fragments from the generated GitHub Pages site.

Dokka navigation and programming-language terminology remain in English. Labels
such as `Types`, `Functions`, `Class`, `Properties`, and `Package-level declarations`
are not translation targets. Only prose originating in API documentation comments
is localized.

Each entry records its English source and hash, translation, status, and engine.
Supported statuses are:

- `missing`: no translation exists;
- `machine`: generated translation awaiting review;
- `reviewed`: manually checked and protected from automatic replacement;
- `stale`: the English source changed after translation.

Edit `translation` and set `status` to `reviewed` to correct a translation. Running
`sync` preserves that edit. If the English text changes, `sync` retains the old
translation but marks it `stale`; the localized page then falls back to English.
