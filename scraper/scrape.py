"""The last 24 hours of UPSC current-affairs stories from coaching sites, grouped across sources, as one JSON file.

Stdlib only. Usage: python3 scrape.py out.json
Each source fails on its own; a broken or silent source just contributes nothing.
"""
import html
import json
import re
import sys
import time
from datetime import datetime, timedelta
from itertools import zip_longest
from urllib.parse import urljoin
from urllib.request import Request, urlopen
from zoneinfo import ZoneInfo

IST = ZoneInfo("Asia/Kolkata")
NOW = datetime.now(IST)
SINCE = NOW - timedelta(hours=24)


def get(url):
    """Retries: from GitHub's runners these sites sometimes stall a request that works fine a minute later."""
    for wait in (10, 20):
        try:
            return fetch(url)
        except OSError:
            time.sleep(wait)
    return fetch(url)


def fetch(url):
    with urlopen(Request(url, headers={"User-Agent": "Mozilla/5.0"}), timeout=60) as r:
        return r.read().decode("utf-8", "replace")


JUNK = re.compile(r"<(script|style|iframe|form|noscript|svg)\b.*?</\1>|<!--.*?-->", re.S | re.I)


def excerpt(text):
    """Skip the "Source: TH Subject: Mapping Context:" header Insights-style notes open with."""
    m = re.search(r"\bContext\s*:\s*", text[:300])
    return text[m.end() if m else 0:][:220]


def item(source, kind, title, link, body, image=None):
    body = JUNK.sub("", body).replace('src="http://', 'src="https://').strip()
    text = re.sub(r"\s+", " ", html.unescape(re.sub(r"<[^>]+>", " ", body))).strip()
    img = re.search(r'<img[^>]+src="([^"]+)"', body)
    return {
        "source": source,
        "kind": kind,
        "title": html.unescape(re.sub(r"<[^>]+>", "", title)).strip(),
        "link": link,
        "image": image or (img and urljoin(link, img.group(1))) or "",
        "excerpt": excerpt(text),
        "min": max(1, round(len(text.split()) / 200)),
        "html": body,
    }


def published(post):
    return datetime.fromisoformat(post["date_gmt"] + "+00:00")


def wp(source, base, kinds, default="prelims"):
    """WordPress REST API: structured, so it survives site redesigns. [kinds] maps category id -> prelims/mains."""
    url = (f"{base}/wp-json/wp/v2/posts?per_page=100&_embed=wp:featuredmedia"
           f"&after={SINCE.date() - timedelta(days=1)}T00:00:00" + (f"&categories={','.join(map(str, kinds))}" if kinds else ""))
    for p in json.loads(get(url)):
        if published(p) < SINCE:
            continue
        kind = next((kinds[c] for c in p["categories"] if c in kinds), default)
        media = (p.get("_embedded", {}).get("wp:featuredmedia") or [{}])[0]
        yield item(source, kind, p["title"]["rendered"], p["link"], p["content"]["rendered"], media.get("source_url"))


def drishti(source):
    """No API or feed: one page per date, one <div class="article-detail"> per story.
    Posts carry no time, so take the newest edition: today's, or yesterday's until today's is up."""
    for day in (NOW.date(), NOW.date() - timedelta(days=1)):
        page = get(f"https://www.drishtiias.com/current-affairs-news-analysis-editorials/news-analysis/{day:%d-%m-%Y}")
        blocks = page.split('<div class="article-detail"')[1:]
        if blocks:
            break
    for block in blocks:
        block = block.split("<!-- #end article detail -->")[0]
        head = re.search(r'<h1 id="dynamic-title"><a href="([^"]+)"[^>]*>(.*?)</a>', block, re.S)
        if head:
            body = block.split("<!-- #end tags area -->")[-1].split('<div class="banner-static">')[0]
            body = re.sub(r'<div class="social-shares">.*?</div>\s*</div>', "", body, count=1, flags=re.S)
            yield item(source, "mains", head.group(2), head.group(1), body)  # analysis pieces, written for mains


# name, fetcher, args. WordPress category ids pick the per-topic news posts (not quizzes or daily mega-posts).
SOURCES = [
    ("Vajiram & Ravi", wp, "https://vajiramandravi.com/current-affairs", {1: "prelims", 18: "mains"}),
    ("Drishti IAS", drishti),
    ("Insights IAS", wp, "https://www.insightsonindia.com", {238466806: "prelims", 238466805: "mains"}),
    ("ForumIAS", wp, "https://forumias.com/blog", {1566: "prelims", 1230: "mains"}),
    ("Rau's IAS", wp, "https://compass.rauias.com", {}),  # silent since 10 Jun 2026; costs one request
]


def proquiz(source, base, category):
    """WP Pro Quiz posts: questions and options in the HTML, correct answers in the plugin's init JSON."""
    for p in json.loads(get(f"{base}/wp-json/wp/v2/posts?per_page=3&categories={category}&_fields=date_gmt,link,content")):
        if published(p) >= SINCE:
            yield from questions(source, p["link"], p["content"]["rendered"])


def questions(source, link, page):
    correct = {qid: flags.split(",").index("1") for qid, flags in
               re.findall(r'"(\d+)":\{[^{}]*?"correct":\[([01,]+)\]', page.replace(" ", ""))}
    for block in page.split('class="wpProQuiz_listItem"')[1:]:
        qid = re.search(r'data-question_id="(\d+)"', block)
        text = re.search(r'class="wpProQuiz_question_text">(.*?)</div>', block, re.S)
        why = re.search(r'class="wpProQuiz_correct">(.*?)</div>\s*<div[^>]*class="wpProQuiz_incorrect"', block, re.S)
        options = [re.sub(r"^[a-dA-D][.)]\s*", "", plain(o))
                   for o in re.findall(r'<li class="wpProQuiz_questionListItem"[^>]*>(.*?)</li>', block, re.S)]
        if not (qid and text and qid.group(1) in correct and len(options) > 1):
            continue
        why = why.group(1) if why else ""
        why = re.sub(r'<span class="wpProQuiz_respone_span">.*?</span>\s*(<br\s*/?>)?|<p>\s*<strong>\s*Answer:.*?</p>|<img[^>]*>'
                     r'|<p>\s*<strong>\s*Explanation\s*:?\s*</strong>\s*</p>', "", why, flags=re.S)
        why, more = split(JUNK.sub("", why).strip())
        yield {"source": source, "link": link, "q": numbered(text.group(1).strip()), "options": options,
               "answer": correct[qid.group(1)], "why": why, "more": more}


def split(why):
    """The reason first, the background later: cut at the first sub-heading ("About Phuket Island:", "History")."""
    cut = re.search(r"<p>\s*<strong>[^<]{3,80}</strong>\s*</p>", why)
    if cut and plain(why[:cut.start()]):
        return why[:cut.start()].strip(), why[cut.start():].strip()
    return why, ""


def plain(fragment):
    return re.sub(r"\s+", " ", html.unescape(re.sub(r"<[^>]+>", " ", fragment))).strip()


def numbered(fragment):
    """The app renders with Html.fromHtml, which bullets <ol> items; UPSC statements need their numbers."""
    def ol(m):
        return "".join(f"<p>{i}. {li}</p>" for i, li in enumerate(re.findall(r"<li[^>]*>(.*?)</li>", m.group(1), re.S), 1))
    return re.sub(r"<ol[^>]*>(.*?)</ol>", ol, fragment, flags=re.S)


QUIZZES = [
    ("Vajiram & Ravi", "https://vajiramandravi.com/current-affairs", 139),
    ("IASbaba", "https://iasbaba.com", 1066),
]

QUIZ_STOP = set("""consider following statement statements regarding recently seen news which given above correct
incorrect select answer using code below with reference pair pairs matched how many only none all both neither
one two three four""".split())


def dedupe(qs):
    """The same question from several coachings: keep the copy with the fullest explanation."""
    kept = []
    for q in sorted(qs, key=lambda q: len(plain(q["why"])), reverse=True):
        w = words(plain(q["q"])) - QUIZ_STOP
        if not any(len(w & k) >= 3 and len(w & k) / min(len(w), len(k)) >= 0.6 for k in (words(plain(x["q"])) - QUIZ_STOP for x in kept)):
            kept.append(q)
    return sorted(kept, key=qs.index)  # back in the coachings' own order

STOP = set("""a an the of in on for to and or is are was be by with from at as its it into how why what who
will can new india indian upsc daily news key fact about explained editorial analysis""".split())


def words(title):
    ws = re.findall(r"[a-z0-9]{2,}", title.lower())
    return {w[:-1] if len(w) > 4 and w.endswith("s") else w for w in ws if not w.isdigit()} - STOP


def same(a, b):
    common = len(a & b)
    return common >= 2 and common / min(len(a), len(b)) >= 0.6


def group(items):
    """Same news from different coachings -> one story. Most-covered stories first."""
    stories = []
    for it in items:
        w = words(it["title"])
        match = next((s for s in stories if it["source"] not in {x["source"] for x in s}
                      and any(same(w, words(x["title"])) for x in s)), None)
        match.append(it) if match else stories.append([it])
    return sorted(stories, key=len, reverse=True)


def main(out):
    per_source = []
    for name, fetcher, *args in SOURCES:
        try:
            per_source.append(list(fetcher(name, *args)))
            print(f"{name}: {len(per_source[-1])}")
        except Exception as e:
            print(f"::warning::{name} failed: {e!r}")
    quiz = []
    for name, base, category in QUIZZES:
        try:
            quiz += list(proquiz(name, base, category))
            print(f"{name} quiz: {sum(q['source'] == name for q in quiz)}")
        except Exception as e:
            print(f"::warning::{name} quiz failed: {e!r}")
    items = [x for row in zip_longest(*per_source) for x in row if x]  # interleave sources
    feed = {"updated": NOW.isoformat(timespec="minutes"), "stories": group(items), "quiz": dedupe(quiz)}
    with open(out, "w", encoding="utf-8") as f:
        json.dump(feed, f, ensure_ascii=False, separators=(",", ":"))


if __name__ == "__main__":
    main(sys.argv[1])
