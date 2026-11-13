# ShuzaGram source integration

LosGram's rebuild branch pins the upstream ShuzaGram Mod Android client as a Git submodule:

- Upstream: https://github.com/shuzq-sh/shuzagram-mod
- Pinned commit: `d5ff82a90877ac89f613ddab7698fc5cee7941b5`
- License: GPL-2.0 (upstream `LICENSE` must be retained)

## Get the complete source

Clone LosGram with submodules:

```bash
git clone --recurse-submodules --branch shuzagram-based-rebuild https://github.com/ilaizurovv-ctrl/LosGram.git
cd LosGram
```

If already cloned:

```bash
git submodule update --init --recursive
```

The complete Telegram-derived Android source is in `upstream/ShuzaGram/`. Its own nested native dependencies are Git submodules, so initialize those too:

```bash
git -C upstream/ShuzaGram submodule update --init --recursive
```

## Build prerequisites

Read the upstream README and build scripts before building. The upstream project currently requires Android SDK/NDK and CMake versions specified by its Gradle files, plus its native submodules.

An Actions workflow is now available at `.github/workflows/shuzagram-build.yml`. It initializes nested submodules, sets the app ID to `org.losgram.messenger`, changes the visible app name to LosGram, creates a temporary debug signing key, and attempts a universal debug APK build. Add these repository Actions secrets before running it:

- `TELEGRAM_APP_ID` — your own numeric API ID from https://my.telegram.org
- `TELEGRAM_APP_HASH` — the matching API hash

Do not paste these credentials into chat or commit them to the repository. The workflow uses a temporary Firebase placeholder only to get through Gradle configuration; Firebase push notifications are not configured by it. A production release still needs a real Firebase project, a private release keystore, and matching signing secrets. The workflow's debug APK is not a production-signed release.

The build changes the application ID and visible app label in the CI checkout, without modifying the upstream submodule commit. A complete production rebrand still requires auditing provider authorities, deep links, update URLs, launcher assets, Firebase registration, and any service-specific identifiers; do not use blind global replacement.

## Important

This branch contains the pinned upstream source and a CI build path. No successful APK build has been verified yet. The original LosGram prototype and backend remain in this repository, and `main` is unchanged.
