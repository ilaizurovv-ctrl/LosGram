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

Before a usable APK can be produced, configure your own Telegram API `APP_ID` and `APP_HASH` in the upstream `TMessagesProj/src/main/java/org/telegram/messenger/BuildVars.java`, and provide the project's required Firebase configuration and signing properties. Do not commit API credentials, keystores, passwords, or `google-services.json` to this public repository; use local ignored files or CI secrets.

The upstream app currently uses its own application ID, package namespace, launcher resources, and ShuzaGram branding. Those still need a deliberate LosGram rebrand before release. Do not change package identifiers with blind global replacement: update Gradle namespace/application ID, manifests, provider authorities, deep links, Firebase registration, and signing configuration consistently.

## Important

This branch adds the upstream source as a pinned submodule; it does not yet claim that the app has been rebranded, compiled, or tested as LosGram. The original LosGram prototype and backend remain in this repository, and `main` is unchanged.
