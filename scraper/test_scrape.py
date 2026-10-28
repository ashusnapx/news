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
print("ok")

quiz_page = """<li class="wpProQuiz_listItem"><div class="wpProQuiz_question_text"><p>Consider the following statements about Phuket Island:</p>
<ol><li>It is part of Malaysia.</li><li>It sits on the Andaman Sea.</li></ol></div>
<ul class="wpProQuiz_questionList" data-question_id="2840" data-type="single">
<li class="wpProQuiz_questionListItem" data-pos="0"><label><input type="radio"> a. 1 only </label></li>
<li class="wpProQuiz_questionListItem" data-pos="1"><label><input type="radio"> b. 2 only </label></li></ul>
<div class="wpProQuiz_response"><div style="display: none;" class="wpProQuiz_correct"><span class="wpProQuiz_respone_span"> Correct </span><br>
<p><strong>Answer: b</strong></p><p>Phuket is in Thailand.</p><img src="x.png"></div><div style="display: none;" class="wpProQuiz_incorrect">x</div></div>
<script>json: {"2840":{"type":"single","id":2840,"points":1,"correct":[0,1]}}</script>"""
[q] = scrape.questions("Vajiram & Ravi", "https://v.com/q", quiz_page)
assert q["options"] == ["1 only", "2 only"] and q["answer"] == 1, q
assert "<p>1. It is part of Malaysia.</p><p>2. It sits on the Andaman Sea.</p>" in q["q"], q["q"]
assert q["why"] == "<p>Phuket is in Thailand.</p>", q["why"]

short = dict(q, source="IASbaba", why="<p>Thailand.</p>",
             q="With reference to Phuket Island, consider: <p>1. It is in Malaysia.</p><p>2. It lies on the Andaman Sea.</p>")
other = dict(q, source="IASbaba", q="Consider the following statements about the Saidongar pumped storage project", why="")
kept = scrape.dedupe([q, short, other])
assert kept == [q, other], [k["source"] for k in kept]  # duplicate dropped for the thinner explanation, order kept
print("ok")
