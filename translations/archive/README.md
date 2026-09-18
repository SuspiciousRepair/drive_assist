# Archived Android locales

Drive Assist currently ships English (default), Brazilian Portuguese, Thai,
and Spanish. Their source files remain under `drivemem/src/main/res/` and are
checked against the English key set by `tools/check-active-locales.sh` during
normal verification.

The locale directories below `android-res/` are preserved source material from
previous broad translation work. They sit outside Android's resource tree, so
they are neither compiled nor shipped. This avoids presenting incomplete,
unreviewed translations as supported languages.

Restore a locale only in response to a concrete user/community request. Move
its directory back to `drivemem/src/main/res/`, update every key to match the
English bundle, add it to `ACTIVE` in `tools/check-active-locales.sh`, and
include a focused translation review in the PR.
