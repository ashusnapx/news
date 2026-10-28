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
