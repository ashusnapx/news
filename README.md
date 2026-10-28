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

- **Scraper** (`scraper/`, Python stdlib only): WordPress REST API for Vajiram & Ravi, Insights IAS and ForumIAS; the dated news-analysis page for Drishti IAS. Keeps the **last 24 hours**, groups the same story across coachings, and tags each with prelims/mains and its GS papers. Daily quizzes come from Vajiram & Ravi, IASbaba and Legacy IAS (last 48 hours, one quiz per coaching), with the same question from two coachings kept once. A broken source logs a warning and is skipped.
- **App** (`app/`, Kotlin + platform views, no dependencies except Firebase Analytics): fetches `news.json`, shows the feed, opens full articles in-app (paper or screen, listen, save, highlight, share), runs the quiz with UPSC marking, and keeps a streak, a daily goal, saved stories and a revision deck on the phone. Shows nothing older than 24 hours.

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

## Usage stats

Google Analytics for Firebase, the app's one dependency. Off until the reader says yes (a one-time prompt, and a
switch in You); never the advertising ID or Android ID; no ad personalisation. Sent: screens and features used,
quiz results, reminders tapped. Never sent: the reader's name or highlighted text.

Needs `app/google-services.json` from the Firebase console (Project settings → Your apps → Android app with
package `com.ashusnapx.news`). It is git-ignored because the repo is public. Events show in Firebase →
Analytics → Realtime; to watch them as they happen while testing:

```sh
adb shell setprop debug.firebase.analytics.app com.ashusnapx.news   # Firebase DebugView
```

## Tests

```sh
cd scraper && python3 test_scrape.py   # grouping + Drishti parsing, offline
```

## Notes

- GitHub disables scheduled workflows in public repos after 60 days without commits. Push anything (or re-enable in Actions) to wake it.
- Personal use only: the scraped content belongs to the coaching institutes.
- Icons: [Phosphor Icons](https://phosphoricons.com) (MIT), converted to vector drawables. Fonts: Instrument Serif, Plus Jakarta Sans, Literata (SIL OFL 1.1), subset to Latin.
