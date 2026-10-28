"""The last 24 hours of UPSC current-affairs stories from coaching sites, grouped across sources, as one JSON file.

Stdlib only. Usage: python3 scrape.py out.json
Each source fails on its own; a broken or silent source just contributes nothing.
"""
import html
import json
import re
import sys
import time
from datetime import datetime, timedelta, timezone
from html.parser import HTMLParser
from itertools import zip_longest
from urllib.parse import urljoin
from urllib.request import Request, urlopen
from zoneinfo import ZoneInfo

IST = ZoneInfo("Asia/Kolkata")
NOW = datetime.now(IST)
SINCE = NOW - timedelta(hours=24)
QUIZ_SINCE = NOW - timedelta(hours=48)  # quizzes post later and in batches; each coaching still gives only its newest


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
    """Skip the "Source: TH Subject: Mapping Context:" header Insights notes open with, and the
    "Kittur Fort Latest News" heading Vajiram's do."""
    m = re.search(r"\bContext\s*:\s*|^.{0,100}?\bLatest News\s+", text[:300])
    return text[m.end() if m else 0:][:220]


GS_MARK = re.compile(r"\bGS\s*(?:Paper)?\s*[-–—:]?\s*([1-4])\b", re.I)
# Keyword fallback when a coaching does not say which paper: a coarse map of the UPSC syllabus.
GS_WORDS = {
    "GS1": "history ancient medieval temple dynasty fort art culture heritage festival archaeolog geography cyclone earthquake "
           "monsoon river lake mountain glacier ocean island society women population urban tribal caste mapping",
    "GS2": "constitution court supreme judiciary parliament bill act governance scheme policy election rights ministry "
           "bilateral treaty summit foreign diplomat health education welfare federal panchayat magistrate polity",
    "GS3": "economy gdp inflation rbi bank budget tax trade export investment agricultur farm environment climate wildlife "
           "sanctuary reserve species pollution biodiversity coral technology space isro satellite cyber defence security "
           "energy fuel ethanol infrastructure disaster missile semiconductor ai",
    "GS4": "ethics integrity moral values empathy probity",
}


def gs(text, tags=""):
    """Which GS papers a story serves: the coaching's own marker if it gives one, else syllabus keywords."""
    marked = sorted({f"GS{n}" for n in GS_MARK.findall(tags + " " + text[:800])})
    if marked:
        return marked
    words = re.findall(r"[a-z]+", text[:600].lower())
    score = {p: sum(any(w.startswith(k) for k in ks.split()) for w in words) for p, ks in GS_WORDS.items()}
    best = max(score.values())
    return [p for p, v in score.items() if v == best] if best else []


def item(source, kind, title, link, body, image=None, tags=""):
    body = JUNK.sub("", body).replace('src="http://', 'src="https://').strip()
    text = re.sub(r"\s+", " ", html.unescape(re.sub(r"<[^>]+>", " ", body))).strip()
    img = re.search(r'<img[^>]+src="([^"]+)"', body)
    return {
        "source": source,
        "kind": kind,
        "gs": gs(html.unescape(re.sub(r"<[^>]+>", "", title)) + ". " + text, tags),
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
            tags = plain((re.search(r'<div class="tags-new">(.*?)</div>', block, re.S) or re.match("", "")).group(1) or "")
            yield item(source, "mains", head.group(2), head.group(1), body, tags=tags)  # analysis pieces, written for mains


# name, fetcher, args. WordPress category ids pick the per-topic news posts (not quizzes or daily mega-posts).
SOURCES = [
    ("Vajiram & Ravi", wp, "https://vajiramandravi.com/current-affairs", {1: "prelims", 18: "mains"}),
    ("Drishti IAS", drishti),
    ("Insights IAS", wp, "https://www.insightsonindia.com", {238466806: "prelims", 238466805: "mains"}),
    ("ForumIAS", wp, "https://forumias.com/blog", {1566: "prelims", 1230: "mains"}),
]


def newest(base, category, fields):
    posts = json.loads(get(f"{base}/wp-json/wp/v2/posts?per_page=1&categories={category}&_fields=date_gmt,link,{fields}"))
    return posts[0] if posts and published(posts[0]) >= QUIZ_SINCE else None


def proquiz(source, base, category):
    """WP Pro Quiz posts: questions and options in the HTML, correct answers in the plugin's init JSON."""
    if p := newest(base, category, "content"):
        yield from questions(source, p["link"], p["content"]["rendered"])


def lqz(source, base, category):
    """Legacy IAS's own quiz widget: one <div class="lqz-q" data-answer="b"> per question, explanation inline."""
    if not (p := newest(base, category, "id")):
        return
    page = get(p["link"])
    for block in re.split(r'(?=<div class="lqz-q" data-answer=)', page)[1:]:
        answer = re.match(r'<div class="lqz-q" data-answer="([a-d])"', block)
        text = re.search(r'<div class="lqz-q-text">(.*?)</div>\s*</div>\s*<ul class="lqz-options">', block, re.S)
        options = [plain(o) for o in re.findall(r'<span class="lqz-otext">(.*?)</span>', block, re.S)]
        why = re.search(r'<div class="lqz-explain-inner">(.*?)</div>\s*</div>', block, re.S)
        if not (answer and text and len(options) > 1):
            continue
        q = re.sub(r'<ul class="lqz-stems">(.*?)</ul>', r"<ol>\1</ol>", text.group(1), flags=re.S)  # numbered by CSS on their site
        q = re.sub(r'<div class="lqz-q-ask">(.*?)</div>', r"<p>\1</p>", q, flags=re.S)
        why = re.sub(r'<div class="lqz-explain-head">.*?</div>', "", why.group(1) if why else "", flags=re.S)
        why, more = split(blocks(why))
        yield {"source": source, "q": numbered(q.strip()), "options": options,
               "answer": "abcd".index(answer.group(1)), "why": why, "more": more}


def questions(source, page):
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
        why, more = split(blocks(JUNK.sub("", why)))
        yield {"source": source, "q": numbered(text.group(1).strip()), "options": options,
               "answer": correct[qid.group(1)], "why": why, "more": more}


class _Blocks(HTMLParser):
    def __init__(self):
        super().__init__()
        self.out, self.buf, self.depth = [], [], 0

    def flush(self):
        text = re.sub(r"<b>\s*</b>", "", re.sub(r"\s+", " ", "".join(self.buf))).strip()
        text = re.sub(r"</b>(\s*)<b>", r"\1", text).replace("’’", "”").replace("‘‘", "“")
        self.buf = []
        if plain(text):
            self.out.append(["li" if self.depth else "p", self.depth, text])

    def handle_starttag(self, tag, attrs):
        if tag in ("strong", "b"):
            self.buf.append("<b>")
        elif tag in ("ul", "ol", "li", "p", "div", "br", "h1", "h2", "h3", "h4", "h5", "h6", "tr"):
            self.flush()
            self.depth += tag in ("ul", "ol")

    def handle_endtag(self, tag):
        if tag in ("strong", "b"):
            self.buf.append("</b>")
        elif tag in ("ul", "ol", "li", "p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "tr"):
            self.flush()
            self.depth -= tag in ("ul", "ol") and self.depth > 0

    def handle_data(self, data):
        self.buf.append(html.escape(data, quote=False))


def blocks(fragment):
    """Coaching HTML -> [[kind, depth, text]]: kind p (paragraph), li (bullet at depth), h (sub-heading).
    Inline markup is cut to <b> only; empty lines, inline styles and stray spans go."""
    parser = _Blocks()
    parser.feed(fragment)
    parser.close()
    parser.flush()
    out = []
    for kind, depth, text in parser.out:
        if JUNK_LINE.match(plain(text)):
            continue
        # A short all-bold line ending in ":" (or "About ...") is a sub-heading, not a sentence.
        whole = re.fullmatch(r"<b>([^<]{2,80}?)(:?)</b>(:?)", text)
        if kind == "p" and whole and (whole.group(2) or whole.group(3) or whole.group(1).startswith("About ")):
            out.append(["h", 0, whole.group(1).strip()])
            continue
        # "Statement 1 is correct — ... Statement 2 is incorrect — ..." reads better one verdict per line.
        for part in re.split(r"\s+(?=(?:Statement|Pair) \d+ is (?:in)?correct)", text):
            out.append([kind, depth, part])
    return out


JUNK_LINE = re.compile(r"^(?:(?:News|Source)\s*:?\s*)?(?:https?://\S+)?$", re.I)
CONCLUSION = re.compile(r"^(?:Hence|Therefore|Thus|So,)\b", re.I)


def split(bs):
    """The reason first, the background later: cut at the first sub-heading ("About Phuket Island", "History").
    A closing "Hence only statement 2 is correct." belongs with the reason, wherever it was written."""
    cut = next((i for i, b in enumerate(bs) if b[0] == "h"), None)
    if not cut:
        return bs, []
    why, more = bs[:cut], bs[cut:]
    return why + [b for b in more if CONCLUSION.match(plain(b[2]))], [b for b in more if not CONCLUSION.match(plain(b[2]))]


def plain(fragment):
    return re.sub(r"\s+", " ", html.unescape(re.sub(r"<[^>]+>", " ", fragment))).strip()


def numbered(fragment):
    """The app renders with Html.fromHtml, which bullets <ol> items; UPSC statements need their numbers."""
    def ol(m):
        return "".join(f"<p>{i}. {li}</p>" for i, li in enumerate(re.findall(r"<li[^>]*>(.*?)</li>", m.group(1), re.S), 1))
    return re.sub(r"<ol[^>]*>(.*?)</ol>", ol, fragment, flags=re.S)


QUIZZES = [
    ("Vajiram & Ravi", proquiz, "https://vajiramandravi.com/current-affairs", 139),
    ("IASbaba", proquiz, "https://iasbaba.com", 1066),
    ("Legacy IAS", lqz, "https://www.legacyias.com", 701),
]

QUIZ_STOP = set("""consider following statement statements regarding recently seen news which given above correct
incorrect select answer using code below with reference pair pairs matched how many only none all both neither
one two three four""".split())


def dedupe(qs):
    """The same question from several coachings: keep the copy with the fullest explanation."""
    kept = []
    for q in sorted(qs, key=lambda q: sum(len(plain(b[2])) for b in q["why"] + q["more"]), reverse=True):
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
    for name, fetcher, *args in QUIZZES:
        try:
            quiz += list(fetcher(name, *args))
            print(f"{name} quiz: {sum(q['source'] == name for q in quiz)}")
        except Exception as e:
            print(f"::warning::{name} quiz failed: {e!r}")
    items = [x for row in zip_longest(*per_source) for x in row if x]  # interleave sources
    feed = {"updated": NOW.isoformat(timespec="minutes"), "stories": group(items), "quiz": dedupe(quiz)}
    with open(out, "w", encoding="utf-8") as f:
        json.dump(feed, f, ensure_ascii=False, separators=(",", ":"))


if __name__ == "__main__":
    main(sys.argv[1])
