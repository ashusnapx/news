"""Offline self-check for the parts that can silently go wrong: grouping and Drishti parsing."""
import scrape


def it(source, title):
    return {"source": source, "title": title}


stories = scrape.group([
    it("ForumIAS", "What are the powers and functions of a Special Executive?"),
    it("Vajiram & Ravi", "Cyclone Arnab 2026, Will the Storm Hit Andhra Coast Tomorrow?"),
    it("Vajiram & Ravi", "Special Executive Magistrates: Powers, Limits and Legal Safeguards"),
    it("Insights IAS", "Cyclone Arnab"),
    it("Insights IAS", "Special Executive Magistrates (SEMs)"),  # matches Vajiram's title, not ForumIAS's
    it("ForumIAS", "India's first LNG-powered train"),
    it("Drishti IAS", "India's first hydrogen fuel cell vessel"),
    it("Drishti IAS", "Captagon"),
])
sizes = sorted(len(s) for s in stories)
assert sizes == [1, 1, 1, 2, 3], stories  # SEM x3, cyclone x2, unrelated "India's first ..." stay apart
assert {x["source"] for x in stories[0]} == {"ForumIAS", "Vajiram & Ravi", "Insights IAS"}

page = """<div class="article-detail" ><h1 id="dynamic-title"><a href="https://d.com/a" target="_blank">NVS-03 &amp; NavIC</a></h1>
<div class="tags-new">x</div><!-- #end tags area --><div class="social-shares"><div class="a2a"><a></a></div> </div>
<p>Body <img src="http://d.com/i.png"></p><script>x()</script><div class="banner-static">ad</div></div><!-- #end article detail -->"""
scrape.get = lambda _url: page
[d] = scrape.drishti("Drishti IAS")
assert d["title"] == "NVS-03 & NavIC" and d["link"] == "https://d.com/a", d
assert d["html"] == '<p>Body <img src="https://d.com/i.png"></p>', d["html"]
assert d["image"] == "https://d.com/i.png" and d["excerpt"] == "Body"
assert scrape.excerpt("Source: TH Subject: Mapping Context: The Maharashtra board approved it.") == "The Maharashtra board approved it."
assert scrape.excerpt("Plain opening line.") == "Plain opening line."
assert scrape.excerpt("Kittur Fort Latest News The Karnataka government will build it.") == "The Karnataka government will build it."
assert scrape.blocks("<p>Statement 1 is correct — yes. Statement 2 is incorrect — no.</p>") == [
    ["p", 0, "Statement 1 is correct — yes."], ["p", 0, "Statement 2 is incorrect — no."]]

legacy = """<div class="lqz-q" data-answer="b"> <div class="lqz-q-head"> <span class="lqz-q-num">Q1</span> <div class="lqz-q-text"> About <b>E100</b>:
<ul class="lqz-stems"> <li>One.</li> <li>Two.</li> </ul> <div class="lqz-q-ask">Which are correct?</div> </div> </div> <ul class="lqz-options">
<li class="lqz-option" data-key="a"><span class="lqz-key">A</span><span class="lqz-otext">1 only</span></li>
<li class="lqz-option" data-key="b"><span class="lqz-key">B</span><span class="lqz-otext">2 only</span></li> </ul>
<div class="lqz-explain"><div class="lqz-explain-inner"> <div class="lqz-explain-head">Answer: (b)</div> <p>Statement 2 holds.</p> </div></div> </div>"""
scrape.get = lambda url: '[{"date_gmt":"%s","link":"https://l.com/q","id":1}]' % scrape.NOW.strftime("%Y-%m-%dT%H:%M:%S") if "wp-json" in url else legacy
scrape.NOW = scrape.NOW.astimezone(scrape.timezone.utc)
[lq] = scrape.lqz("Legacy IAS", "https://l.com", 701)
assert lq["answer"] == 1 and lq["options"] == ["1 only", "2 only"], lq
assert "<p>1. One.</p><p>2. Two.</p>" in lq["q"] and "<p>Which are correct?</p>" in lq["q"], lq["q"]
assert lq["why"] == [["p", 0, "Statement 2 holds."]], lq["why"]
assert scrape.gs("UPSC Syllabus: Gs Paper 2- Health") == ["GS2"]
assert scrape.gs("Body text", tags="Tags: GS Paper - 3 Space Technology") == ["GS3"]
assert scrape.gs("Kittur Fort. The Karnataka government will restore the fort and its heritage.") == ["GS1"]
assert scrape.gs("Nothing to see here.") == []
print("ok")

quiz_page = """<li class="wpProQuiz_listItem"><div class="wpProQuiz_question_text"><p>Consider the following statements about Phuket Island:</p>
<ol><li>It is part of Malaysia.</li><li>It sits on the Andaman Sea.</li></ol></div>
<ul class="wpProQuiz_questionList" data-question_id="2840" data-type="single">
<li class="wpProQuiz_questionListItem" data-pos="0"><label><input type="radio"> a. 1 only </label></li>
<li class="wpProQuiz_questionListItem" data-pos="1"><label><input type="radio"> b. 2 only </label></li></ul>
<div class="wpProQuiz_response"><div style="display: none;" class="wpProQuiz_correct"><span class="wpProQuiz_respone_span"> Correct </span><br>
<p><strong>Answer: b</strong></p><p><strong>Explanation:</strong></p><p>Phuket is in Thailand.</p><img src="x.png">
<p><strong>About Phuket Island:</strong></p><ul><li>It is <strong>Thailand&#8217;s largest island</strong>.</li>
<li><strong>History</strong>:<ul><li style="font-weight: 400;"><span>Settled in the 1st century BCE.</span></li></ul></li></ul><p>&nbsp;</p>
<p><strong>Hence only statement 2 is correct.</strong></p><p><strong>News</strong>:https://www.thehindu.com/x</p></div><div style="display: none;" class="wpProQuiz_incorrect">x</div></div>
<script>json: {"2840":{"type":"single","id":2840,"points":1,"correct":[0,1]}}</script>"""
[q] = scrape.questions("Vajiram & Ravi", "https://v.com/q", quiz_page)
assert q["options"] == ["1 only", "2 only"] and q["answer"] == 1, q
assert "<p>1. It is part of Malaysia.</p><p>2. It sits on the Andaman Sea.</p>" in q["q"], q["q"]
assert q["why"] == [["p", 0, "Phuket is in Thailand."], ["p", 0, "<b>Hence only statement 2 is correct.</b>"]], q["why"]
assert q["more"] == [["h", 0, "About Phuket Island"], ["li", 1, "It is <b>Thailand’s largest island</b>."],
                     ["li", 1, "<b>History</b>:"], ["li", 2, "Settled in the 1st century BCE."]], q["more"]

short = dict(q, source="IASbaba", why=[["p", 0, "Thailand."]], more=[],
             q="With reference to Phuket Island, consider: <p>1. It is in Malaysia.</p><p>2. It lies on the Andaman Sea.</p>")
other = dict(q, source="IASbaba", q="Consider the following statements about the Saidongar pumped storage project", why=[], more=[])
kept = scrape.dedupe([q, short, other])
assert kept == [q, other], [k["source"] for k in kept]  # duplicate dropped for the thinner explanation, order kept
print("ok")
