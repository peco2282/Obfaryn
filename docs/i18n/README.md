# Documentation translations

English KDoc/Javadoc is the sole source of truth. `catalog.json` is generated from
Dokka HTML and records where each English fragment is used. `translations.json` is
the hand-reviewed translation memory, keyed by the English fragment's SHA-256 hash.

Dokka navigation and programming-language terminology remain in English. Labels
such as `Types`, `Functions`, `Class`, `Properties`, and `Package-level declarations`
are not translation targets. Only prose originating in API documentation comments
is localized.

Each translation records its English source, Japanese translation, and review status.
Supported statuses are:

- `missing`: no translation exists;
- `machine`: generated translation awaiting review (retained for schema compatibility);
- `reviewed`: manually checked and protected from automatic replacement;
- `stale`: the English source changed after translation.

Edit `translations.json` to correct a translation. Because translations are keyed by
the English source hash, repeated KDoc is translated once and reused everywhere. If
the English text changes, the new hash has no translation and the localized page
falls back to English; the old translation remains available for review or removal.

The repository workflow does not call a translation service. Codex updates the
catalog in an ordinary documentation branch after reading the relevant code and
English KDoc/Javadoc context.

Run the coverage report after synchronizing the catalog:

```bash
python3 scripts/docs-i18n/docs_i18n.py status \
  --catalog docs/i18n/ja/catalog.json \
  --translations docs/i18n/ja/translations.json
```

`Missing` fragments fall back to English. `Stale` counts retained translations that
are no longer used by the active Dokka output. `High-risk missing` identifies missing
text containing requirements, nullability, exception, thread-safety, or security
language and should be reviewed first. The publishing workflow reports these states
without blocking deployment.
