# UPSC Today

Today's UPSC current affairs from coaching sites, in one clean Android app. No ads, no accounts, no database, no third-party libraries.

## How it works

```
GitHub Actions (hourly, 06:00–23:00 IST)
  scraper/scrape.py  ──►  news.json  ──►  GitHub Pages
                                             │  one HTTPS GET (ETag revalidated)
                                             ▼
                                        Android app
```

- **Scraper** (`scraper/`, Python stdlib only): WordPress REST API for Vajiram & Ravi, Insights IAS, ForumIAS, Rau's IAS; the dated news-analysis page for Drishti IAS. Keeps only posts dated **today (IST)** and groups the same story across coachings. A broken source logs a warning and is skipped.
- **App** (`app/`, Kotlin + platform views, zero dependencies): fetches `news.json`, shows cards, opens the full article in-app. Switch coaching per story when several covered it. Works offline for today's stories via the platform HTTP cache. Shows nothing from previous days.

## Setup

1. Push this repo to GitHub as a **public** repo named `news` (free Pages needs public).
2. Settings → Pages → Source: **GitHub Actions**.
3. Actions → `scrape` → **Run workflow** once. The feed appears at `https://<user>.github.io/news/news.json`.
4. If your user or repo name differs, change `URL` in `app/src/main/kotlin/com/ashusnapx/news/Feed.kt`.

## Build

```sh
./gradlew assembleRelease            # app/build/outputs/apk/release/app-release.apk
adb install app/build/outputs/apk/release/app-release.apk
```

Needs JDK 17+ and Android SDK 37.

## Tests

```sh
cd scraper && python3 test_scrape.py   # grouping + Drishti parsing, offline
```

## Notes

- GitHub disables scheduled workflows in public repos after 60 days without commits. Push anything (or re-enable in Actions) to wake it.
- Personal use only: the scraped content belongs to the coaching institutes.
- Icons: [Phosphor Icons](https://phosphoricons.com) (MIT), converted to vector drawables. Fonts: Instrument Serif, Plus Jakarta Sans, Literata (SIL OFL 1.1), subset to Latin.
