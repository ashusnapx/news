"""Today's (IST) UPSC current-affairs stories from coaching sites, grouped across sources, as one JSON file.

Stdlib only. Usage: python3 scrape.py out.json
Each source fails on its own; a broken or silent source just contributes nothing.
"""
import html
import json
import re
import sys
from datetime import datetime, timedelta
from itertools import zip_longest
from urllib.parse import urljoin
from urllib.request import Request, urlopen
from zoneinfo import ZoneInfo

IST = ZoneInfo("Asia/Kolkata")
TODAY = datetime.now(IST).date()


def get(url):
    with urlopen(Request(url, headers={"User-Agent": "Mozilla/5.0"}), timeout=30) as r:
        return r.read().decode("utf-8", "replace")


JUNK = re.compile(r"<(script|style|iframe|form|noscript|svg)\b.*?</\1>|<!--.*?-->", re.S | re.I)


def item(source, title, link, body, image=None):
    body = JUNK.sub("", body).replace('src="http://', 'src="https://').strip()
    text = re.sub(r"\s+", " ", html.unescape(re.sub(r"<[^>]+>", " ", body))).strip()
    img = re.search(r'<img[^>]+src="([^"]+)"', body)
    return {
        "source": source,
        "title": html.unescape(re.sub(r"<[^>]+>", "", title)).strip(),
        "link": link,
        "image": image or (img and urljoin(link, img.group(1))) or "",
        "excerpt": text[:220],
        "min": max(1, round(len(text.split()) / 200)),
        "html": body,
    }


def wp(source, base, categories):
    """WordPress REST API: structured, so it survives site redesigns."""
    url = (f"{base}/wp-json/wp/v2/posts?per_page=100&_embed=wp:featuredmedia"
           f"&after={TODAY - timedelta(days=1)}T00:00:00" + (f"&categories={categories}" if categories else ""))
    for p in json.loads(get(url)):
        if datetime.fromisoformat(p["date_gmt"] + "+00:00").astimezone(IST).date() != TODAY:
            continue
        media = (p.get("_embedded", {}).get("wp:featuredmedia") or [{}])[0]
        yield item(source, p["title"]["rendered"], p["link"], p["content"]["rendered"], media.get("source_url"))


def drishti(source):
    """No API or feed: one page per date, one <div class="article-detail"> per story."""
    page = get(f"https://www.drishtiias.com/current-affairs-news-analysis-editorials/news-analysis/{TODAY:%d-%m-%Y}")
    for block in page.split('<div class="article-detail"')[1:]:
        block = block.split("<!-- #end article detail -->")[0]
        head = re.search(r'<h1 id="dynamic-title"><a href="([^"]+)"[^>]*>(.*?)</a>', block, re.S)
        if head:
            body = block.split("<!-- #end tags area -->")[-1].split('<div class="banner-static">')[0]
            body = re.sub(r'<div class="social-shares">.*?</div>\s*</div>', "", body, count=1, flags=re.S)
            yield item(source, head.group(2), head.group(1), body)


# name, fetcher, args. WordPress category ids pick the per-topic news posts (not quizzes or daily mega-posts).
SOURCES = [
    ("Vajiram & Ravi", wp, "https://vajiramandravi.com/current-affairs", "1,18"),
    ("Drishti IAS", drishti),
    ("Insights IAS", wp, "https://www.insightsonindia.com", "238466806,238466805"),
    ("ForumIAS", wp, "https://forumias.com/blog", "1566,1230"),
    ("Rau's IAS", wp, "https://compass.rauias.com", ""),  # silent since 10 Jun 2026; costs one request
]

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
    for name, fetch, *args in SOURCES:
        try:
            per_source.append(list(fetch(name, *args)))
            print(f"{name}: {len(per_source[-1])}")
        except Exception as e:
            print(f"::warning::{name} failed: {e!r}")
    items = [x for row in zip_longest(*per_source) for x in row if x]  # interleave sources
    feed = {"date": TODAY.isoformat(), "updated": datetime.now(IST).isoformat(timespec="minutes"), "stories": group(items)}
    with open(out, "w", encoding="utf-8") as f:
        json.dump(feed, f, ensure_ascii=False, separators=(",", ":"))


if __name__ == "__main__":
    main(sys.argv[1])
