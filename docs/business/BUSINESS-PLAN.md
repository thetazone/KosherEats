# KosherEats — Business Plan & Strategy Notes

> **Owner:** Salto (Sam Mamiye) · **Started:** 2026-06-26 · **Status:** living document
> **Scope:** business / strategy / go-to-market — **non-technical.** Keep code and infra notes in `docs/KE-TODO.md`, not here.

---

## How to use this document (read me first)

This is a **strategy brief**, not a finished plan. It exists so I (Salto) can think through where KosherEats goes next on the *business* side, and so I can hand it to a fresh advisory session and get straight to direction without re-explaining everything.

Structure:
1. **My two founding ideas**, captured in my own framing — these are the seed; everything after is support for thinking them through.
2. **Where the business actually is today** — the honest, data-grounded reality so no one strategizes in a fantasy.
3. Three developed lenses — **Monetization**, **Marketing & Community Growth**, and **Risks / Sequencing** — written as decision-support, with illustrative numbers clearly marked (not market facts) and open questions called out.

**To the next advisory session:** I want help on *direction and the best way to approach this* — business model, sequencing, go-to-market — **not code**. The funnel/scale numbers below are real (pulled from the production database 2026-06-26). Push back on me; I'd rather hear "this is premature" than be flattered.

---

## My two founding ideas (in my words)

### Idea 1 — Switch the business model from commission to subscription

Today KosherEats takes a **commission** on each order (the UberEats/DoorDash model). I want to consider flipping to a **flat monthly subscription** instead:

- Charge each restaurant roughly **$350–$500 / month** rather than a per-order cut.
- Offer **lower pricing for smaller restaurants** that qualify.
- Possibly **tier restaurants by how much they make** — but I'm wary, because tiering on revenue means restaurants have to **divulge proprietary financials** they may not want to share. I need a way to tier (if at all) that doesn't force that disclosure.

The appeal to me: "we don't take a cut of your food" is a cleaner, more values-aligned pitch, and flat revenue is more predictable.

### Idea 2 — Marketing through TikTok + the Jewish community

For **every restaurant we onboard**, I personally film a **TikTok short of myself eating my favorite dish there** — real, on camera, as someone who actually keeps kosher.

- Launch these on social media **within the Jewish community** to build traction, awareness, and hype around KosherEats and what it stands for.
- Tie it to **broader Jewish missions** — the angle that this is a **kosher-designated app built by a Jewish person who genuinely cares about the laws of kashrut**, and that it can connect to / support other Jewish causes.

The content doubles as the restaurant's promo and as our brand. The mission isn't a gimmick to me — it's why I'm building this.

---

## Where the business actually is today (reality grounding)

Pulled live from production on 2026-06-26 — the honest picture, so strategy is grounded:

- **Stage: pre-traction.** Live on Stripe, tech works end-to-end, payments/refunds healthy. The constraint is **demand and restaurant supply, not engineering.**
- **Supply:** 4 active restaurants, 5 couriers, 1 market (Jewish community).
- **Consumers:** 64 consumer signups — but **only ~2 have ever placed an order**, and **~62 signed up and never even added an item to a cart.** The handful of orders so far (8 total, from 2 people, over 2 days, mostly *rejected*) are essentially founder/tester activity, not real demand.
- **Translation:** the drop-off is at **signup → first engagement**, not checkout. Checkout, payments, and refunds all work. **Almost nobody is using it yet.**

This is the single most important fact in this document, and the lenses below are written against it.

---

## Business Model: Commission → Subscription

### Strategic logic: why flat subscription can fit a niche kosher marketplace

Commission (a % of each order) is the default for general-market delivery because it scales with volume and aligns the platform's revenue with the restaurant's: no orders, no fee. Its weaknesses show up exactly where KosherEats lives — a thin, community niche with few restaurants and low order density. Commission only pays the platform if order volume is high; at near-zero volume it generates near-zero revenue while you still carry full fixed costs (Stripe, hosting, the founder's time). It also caps your story: a 15–30% take is what restaurants resent most about UberEats, and "we're the kosher app that does the same thing" is a weak wedge.

A flat subscription (the founder's ~$350–$500/mo) inverts this. It gives the platform **predictable revenue independent of volume**, which is the single most valuable thing pre-traction. It lets KosherEats credibly say "we don't take a cut of your food" — a real differentiator and an easy sentence to sell inside a values-driven community. And it caps the restaurant's cost, which is attractive to a busy kosher restaurant doing meaningful volume, because a flat fee gets *cheaper per order* the more they sell.

**Where it breaks:** subscription transfers risk from the platform to the restaurant. The restaurant now pays whether or not orders come. At 4 restaurants and ~2 lifetime ordering customers, you are asking a restaurant to pay $350–$500/mo for a channel that currently delivers ~$0 of revenue to them. That is the core problem below.

### The value-proposition problem: what must a restaurant BELIEVE for $350–$500/mo?

At a per-order commission of $0, a rational restaurant owner does the math: a $400/mo subscription only beats commission once the app drives enough orders that 15–20% of that volume would have exceeded $400 — i.e., roughly **$2,000–$2,700/mo of GMV through the app** *(illustrative, assumes a 15–20% equivalent commission)*. KosherEats cannot yet promise that. So selling a subscription today is not selling order volume — you don't have it — it's selling one of these instead:

- **Belief in the channel's future** — "get in now, lock founding pricing before demand arrives." Only works with owners who buy the vision.
- **Marketing, not delivery** — the TikkTok/community plan (Idea #2) reframes the fee as *paid marketing + a sales channel*, not a delivery toll. A restaurant will pay $400/mo for "the founder personally films you, pushes you to the Jewish community, and you keep 100% of order revenue" far more readily than for "a delivery app with no customers."
- **Mission alignment** — a kosher-designated, founder-led, community-tied platform. Real, but it does not pay rent; treat it as a tiebreaker, not the pitch.

**Be honest:** selling a flat fee *before* demand exists is hard, and most of your 4 restaurants will reasonably refuse $350–$500/mo until they see customers. The realistic early play is **free or near-free supply first** (below), with subscription as the destination once volume is provable.

### Tiering without forcing financial disclosure

The founder's instinct to tier is right; the concern — that revenue-based tiering forces restaurants to divulge proprietary financials — is also right, and easily avoided. Use **proxies you can observe or that the restaurant self-selects**, never numbers they must hand over:

- **In-app order volume (best):** tier on GMV/order count *measured by KosherEats itself*. This is your data, not theirs — no disclosure, and it's the metric that actually correlates with the value delivered. "Under X orders/mo = $X; over = $Y."
- **Observable size proxies:** number of locations, menu size (# of items), seating capacity, # of catering SKUs. Public-ish, non-sensitive, and roughly track restaurant scale.
- **Self-selected plan:** offer Good/Better/Best tiers and let the owner pick. They reveal nothing; they choose based on features (e.g., # of promoted slots, priority listing, catering enablement) rather than disclosing income.
- **Founding-member / intro pricing:** a flat discounted rate for early restaurants regardless of size, converting to standard tiers later. Sidesteps tiering entirely at this stage.

Recommendation: **tier on in-app order volume + self-selected feature plans.** Both are zero-disclosure, and the first ties price to value automatically.

### Hybrid / transition options

You do not have to choose commission *or* subscription. Sequenced options, roughly in order of fit for pre-traction:

1. **Founding-member free tier (recommended now):** first ~5–10 restaurants pay $0 (or a nominal $49–$99) to solve the supply/chicken-and-egg problem and earn the right to film Idea #2 content. Lock in "founding price" language so a future raise feels earned.
2. **Commission now → subscription later:** keep a low commission while volume is tiny (you collect little, but the restaurant risks nothing), and offer to *switch* a restaurant to a flat fee once its in-app volume crosses the break-even line — at which point the flat fee saves *them* money. This makes the subscription an upsell the restaurant *wants*.
3. **Low subscription + small commission (hybrid):** e.g., a modest base fee (covers your fixed cost and signals commitment) plus a small per-order % (keeps you aligned and de-risks the restaurant). Caps restaurant downside while giving you a revenue floor.
4. **Full subscription:** the end-state once order volume reliably clears break-even for most restaurants.

### Unit economics: how to sanity-check subscription vs. commission

*All numbers below are illustrative assumptions, not market data — plug in your real figures.*

The break-even question is simply: **at what monthly order volume does a flat fee earn the platform more than commission would?**

- Platform revenue under commission = `GMV × take_rate`
- Platform revenue under subscription = `flat_fee`
- They're equal when `GMV = flat_fee ÷ take_rate`

Worked example *(illustrative: $400/mo fee, 18% take, $35 avg order)*:
- Break-even GMV = $400 ÷ 0.18 ≈ **$2,222/mo**
- At $35/order that's ≈ **63 orders/mo per restaurant** (~2/day).

Reading it:
- **Below ~63 orders/mo:** subscription earns the *platform* more than commission would — good for you, but the restaurant is overpaying vs. commission, so it's a hard sell unless the marketing/mission value covers the gap.
- **Above ~63 orders/mo:** commission would earn the platform more — but the *flat fee is now a discount to the restaurant*, so it becomes an easy, restaurant-favorable upsell. This is the sweet spot for converting busy restaurants.

Implication: **subscription is easiest to sell to high-volume restaurants and hardest to sell to the low-volume ones you have today.** That's the central tension. Re-run this table with your real take rate, average order value, and a realistic per-restaurant order forecast before committing to any price point.

### Sharpest open questions to resolve first

1. **What does a restaurant actually get for the fee — marketing or delivery?** If the honest answer today is "marketing" (Idea #2), price and pitch it as marketing, not as a delivery subscription. Decide this first; it determines everything else.
2. **Can you prove break-even volume to even one restaurant?** Until ~1–2 restaurants demonstrably clear ~50–60 orders/mo, you cannot sell a $400 flat fee on its merits. Should the goal right now be *revenue* or *provable supply + demand*? (Almost certainly the latter — which argues for the founding-free tier.)
3. **What's the founding-member ramp?** Free → when, and to what? Define the trigger (e.g., "$X in-app GMV" or "N months") and the post-intro price *now*, in writing, so the raise is expected rather than a betrayal.
4. **Full subscription vs. hybrid at steady state?** A base-fee-plus-small-commission hybrid keeps you aligned and de-risks restaurants; pure subscription maximizes the "we take 0% of your food" message. Pick which matters more.
5. **Does $350–$500 even cover your costs and time?** Sanity-check the floor: at 4–10 restaurants, does the total subscription revenue justify the founder's hours plus Stripe/hosting — or is the real near-term goal traction, with monetization deliberately deferred?

---

## Marketing & Community Growth

### The Strategic Fit: Why Founder-Led TikTok Is the Right Wedge

KosherEats has a pre-traction demand problem, not an engineering one. 64 signups and ~2 real orders means the product works but nobody has a *reason to care* yet. Paid acquisition would be lighting money on fire at this stage — you'd be buying clicks from people who don't yet trust the app, in a community that runs on word-of-mouth and rabbinic/communal endorsement, not on ad impressions. Founder-led content is the right wedge for four concrete reasons:

- **Authenticity is the moat.** A Jewish founder who genuinely keeps kosher, on camera, eating at the restaurant, *is* the credibility. In the frum world, "who's behind it and do they actually keep kosher?" is the first question — this answers it before it's asked. It cannot be faked by a competitor with a bigger budget.
- **It doubles as restaurant-acquisition collateral.** Every short is simultaneously consumer marketing *and* a free promo reel for the restaurant. That reframes the onboarding pitch from "give me a commission" to "I will make you content and bring you the kosher-keeping audience."
- **It solves cold-start "why should I care."** Abstract app marketing ("download our delivery app") converts nothing pre-traction. "Watch me eat the best shawarma in [neighborhood]" is a story people share.
- **Supply and demand are addressed by the same asset.** With only 4 restaurants, you cannot afford content that only does one job. This does two.

Stay grounded: this works *because* it's small. It's a hand-to-hand, neighborhood-by-neighborhood motion. It is not yet a scalable growth channel — it's a traction-igniter (see Open Questions).

### From One-Offs to a Content Engine: The Per-Restaurant Playbook

The goal is a **repeatable template**, so the founder isn't reinventing each video. Every restaurant onboarded triggers the same unit of content.

**The standard short (45–60s, vertical), every time includes:**
1. **The hook (first 2 sec):** founder on camera — "Found the best [dish] in [neighborhood] and it's 100% kosher." No slow intros; the first frame decides whether it survives the feed.
2. **The dish:** founder eats his actual favorite item. Real reaction, close-up, named explicitly.
3. **The restaurant:** name, neighborhood, one line of why it's special (family-run, the rav who certifies it, the signature item).
4. **The kashrut / mission beat (one line, not a sermon):** "certified by [hechsher]," or "this is why I built a kosher-only app — so you never have to second-guess." The mission is a *spice*, not the *meal*.
5. **The call to action:** "Order it on KosherEats — link in bio." Single, clear, app-directed.

**Cadence:** one flagship short per restaurant at onboarding, then re-cut into 2–3 derivatives (the dish close-up, the founder "why," a 15-sec teaser). Aim for a sustainable rhythm — e.g., 2–3 posts/week — rather than a burst that burns out. Consistency beats volume in feed algorithms and in community memory.

**Cross-posting (one shoot, many surfaces):** film once, publish to TikTok, Instagram Reels, and YouTube Shorts. **Critically, push the same clip to WhatsApp Status** — in frum communities WhatsApp status and broadcast groups are often a *higher-reach channel than TikTok itself*, since many households limit or avoid TikTok. Don't treat WhatsApp as an afterthought; for this audience it may be the primary surface.

**Each video as a sales asset:** maintain a running reel of past shorts. The onboarding pitch to restaurant #5 becomes: "Here's what I made for restaurants #1–4, here's the views/shares they got, here's the audience I'll put in front of you — for free." The content *is* the sales deck. This is the mechanism that lets the engine compound.

### Distribution Inside the Jewish Community

Generic social distribution underperforms here; the real reach is in owned community channels:

- **Community WhatsApp groups & broadcast lists** — neighborhood, shul, and "kosher food / deals" groups are the dominant information rail in frum communities. Getting a video shared by a trusted group admin is worth more than thousands of cold impressions.
- **Shuls, schools, and mosdos** — flyers, bulletin mentions, and a quiet word from a respected community member. Schools and shul newsletters reach the exact household decision-makers (often the mothers who order family meals).
- **Simcha networks** — bar mitzvahs, sheva brachos, kiddushim are catering-adjacent; even if delivery isn't catering, these are where food talk and recommendations circulate.
- **Frum influencers & askanim** — a growing set of kosher-food influencers (reviewers, "where to eat" accounts) and respected community organizers. One genuine share from a trusted askan carries communal weight no ad can buy.
- **Local Jewish media** — community papers, local Jewish radio/podcasts, neighborhood news sites and their social feeds.

**Trust dynamics:** the entire pitch rests on the app being *kosher-designated and Jewish-built*. That is the differentiator versus UberEats/DoorDash, where a kosher-keeping user must vet every listing themselves. Lead with it, but earn it: make the hechsher/certification of each restaurant visible and verifiable, and never list anything whose kosher status is ambiguous. One trust violation in this community travels faster than any marketing.

### Mission & Brand Narrative

Position KosherEats as **"the delivery app built for people who keep kosher, by someone who does."** The reason-to-believe is the founder himself and the design choice that every restaurant on the platform is kosher — no vetting burden on the user.

- **Durable positioning:** not "a delivery app that happens to have kosher options," but "the kosher community's own delivery infrastructure." That framing survives growth and invites communal ownership.
- **Connection to broader Jewish missions:** this should be expressed as *contribution*, not co-option. A concrete, honest mechanism — e.g., a defined portion of proceeds, or pledged visibility, to a Jewish cause (a local food-relief/tomchei-shabbos-style effort, a school, etc.) — done transparently and modestly. State exactly what is given and to whom; let it be verifiable.
- **Be respectful, not exploitative:** the religious dimension is the founder's sincere commitment, not a marketing veneer. The tone should be "I built this because I needed it and care about it," never "buy from us because you're Jewish." The mission earns trust only if it's real and lightly worn. Over-leveraging religion will read as cynical and backfire in exactly the community you're courting.

### The Flywheel: How Content Reinforces Supply

The marketing engine directly attacks the supply constraint (4 restaurants), which is the binding limit on demand:

1. Founder films a restaurant → flagship short.
2. Content drives consumer awareness, signups, and the first real orders.
3. The video + early order data become the **acquisition pitch** for the next restaurant ("free promo + a hungry kosher audience").
4. More restaurants → more selection → more reason for consumers to open the app and order.
5. More orders → stronger proof → easier next restaurant onboard → more content.

Each turn of the loop produces *both* a consumer asset and a supply asset from a single shoot. That dual-purpose property is what makes the flywheel viable at this tiny scale — there isn't budget for separate demand-gen and sales motions, so they must be the same motion.

### Light, Realistic Measurement

No vanity dashboards and no invented analytics. At this stage, watch a handful of signals you can actually observe:

- **Per-video:** views, shares/sends (shares matter far more than views in a WhatsApp-forward culture), saves, profile/link taps.
- **The only metric that counts:** real orders placed (currently ~2). Use a simple per-restaurant or per-video discount code / "link in bio" so you can attribute *which video drove which first order*.
- **Supply funnel:** restaurants pitched → restaurants onboarded, and whether showing the reel improves that ratio.
- **Activation, not just signups:** of new signups, how many add to cart and complete a first order. (62 of 64 never added an item — fixing *activation* matters as much as top-of-funnel reach.)
- **Channel sense-check:** ask new orderers "where did you hear about us?" — cheap, manual, and tells you whether TikTok, WhatsApp, or shul word-of-mouth is actually working.

### Open Questions & Risks (Sharpest 5)

1. **Founder bandwidth is the single point of failure.** Filming, editing, posting, *and* running the company doesn't scale past a handful of restaurants. What's the realistic films-per-week number, and what breaks first?
2. **What happens when it can't scale past the founder?** The authenticity that makes this work is *also* what makes it un-delegable — a hired creator isn't a kosher-keeping founder. Is there a planned hand-off (a trusted community face, a repeatable format others can run) before the founder becomes the bottleneck?
3. **The content-to-conversion gap.** Views and shares are not orders. Pre-traction, it's entirely possible to make beloved videos that produce zero incremental orders (because of activation friction, thin restaurant selection, or delivery-radius limits). What's the threshold at which "good content, no orders" means the problem is product/supply, not marketing?
4. **Mission authenticity risk.** Tying proceeds to Jewish causes is powerful *if real and transparent* and corrosive if it reads as a marketing prop. Is there a concrete, honestly-funded commitment, or is it aspirational? Don't promote it until it's true.
5. **Channel dependency & platform risk.** If WhatsApp groups are the real reach channel, you're dependent on group admins' goodwill and one bad-share/spam-complaint away from being blocked — and TikTok adoption is uneven in frum households. Is the distribution diversified enough that no single gatekeeper can shut it down?

---

## Risks, Sequencing & Hard Questions

### The one reality check that matters

The business is **pre-demand**, not pre-monetization. 62 of 64 signups never added a single item to a cart; ~2 people have ever actually ordered, and even those are essentially founder/tester activity. That is the only number that matters right now, and it tells you the problem is **no one wants to buy yet** — not that the pricing model is wrong.

So, bluntly:

- **Idea #1 (commission → subscription) does NOT address the core problem.** It changes who pays and how, not whether anyone orders. Worse, it's premature: you can't price a subscription against order volume that doesn't exist, and a restaurant doing ~0 orders/month through you has zero reason to pay $350–500/mo. Changing the monetization model on a marketplace with no GMV is rearranging deck chairs.
- **Idea #2 (TikToks + community) DOES address the core problem** — it's a demand-and-awareness play, which is exactly the constraint. It's the right *category* of move. The risk is execution and whether it converts viewers into repeat orderers, not whether it's aimed at the right target.

Plainly: **monetization changes do not create demand.** A flat fee doesn't make a single consumer place an order. Until you have repeatable order volume, pricing is a distraction. Build demand first; let the proven order volume tell you what monetization the market can bear.

### Risks per idea

**Idea #1 — Subscription model**

- **Charging for a service that delivers ~0 orders.** A flat $350–500/mo only makes sense if a restaurant is netting clearly more than that in incremental orders. At current volume you're asking restaurants to pay for nothing. Expect immediate, justified churn.
- **You lose the only honest signal you have.** Commission scales with value delivered (you eat $0 when they sell $0). A subscription flips that: you get paid whether or not the restaurant succeeds, which is exactly backwards for a platform that hasn't proven it drives orders.
- **Revenue-tiering is a non-starter right now.** You already named the problem: restaurants won't divulge financials. It also creates a verification/trust burden you can't enforce, and it punishes your best restaurants. Don't tier on revenue. If you ever tier, tier on something observable to you (order volume *through your platform*, number of locations, listing features) — not their books.
- **Subscription churn with no order volume is brutal.** SaaS churn is forgivable when the product is sticky. A delivery subscription with no orders has nothing holding the restaurant — they'll cancel the first slow month, and every month is slow today.
- **Cash-flow mirage.** $350–500 × 4 restaurants = $1,400–2,000/mo looks like "revenue," but it's really pre-paid goodwill from 4 relationships you personally hold. It's not a business model proven at scale; it's friends paying you.

**Idea #2 — Founder TikToks + Jewish-community marketing**

- **Founder-dependency / bus-factor (the biggest one).** The entire growth engine is *you*, on camera, eating at every restaurant. That does not scale past your personal time and does not survive you being sick, busy, or burned out. One person is a single point of failure for both supply (relationships) and demand (content).
- **Views ≠ orders.** TikTok virality in a community is vanity until it converts to carted, paid, *repeat* orders. You must instrument the funnel: view → app install → first order → second order. A million views and zero second orders is a failure dressed as a win.
- **Brand/mission risk cuts both ways.** Tying KosherEats to broader Jewish causes/missions is powerful *and* dangerous: it invites scrutiny, politicization, and "why is a delivery app weighing in on X" backlash. Mission can become a liability if it gets ahead of the product or wades into contested territory.
- **Authenticity is fragile.** "Founder eats his favorite dish" works exactly until it looks like an ad or a restaurant you're paid to promote. The moment it feels transactional, the community sniffs it out.
- **Content can outrun supply.** If a TikTok pops and people order, but you have 4 restaurants and 5 couriers, you'll deliver a bad first experience to your most valuable early demand — and burn the launch.

**Cross-cutting risks**

- **Supply ↔ demand chicken-and-egg.** Consumers won't come for 4 restaurants; restaurants won't pay/engage without consumers. You have to break this manually, market by market, not via a pricing change.
- **Single-market concentration.** One community = one rumor, one bad-kosher incident, or one influential detractor can sink the whole thing. No diversification cushion.
- **Seasonality is structural, not incidental.** A Jewish-community business goes dark every Shabbat (≈25 hrs/week, Fri eve–Sat night) and around major holidays — and *spikes* before them. Your weekly order curve, courier scheduling, and any subscription "value per month" math must account for ~6 fewer operating days/month than a generic delivery app, plus holiday peaks/troughs. Subscriptions priced on a normal month will feel overpriced in a Shabbat/holiday-heavy month.
- **Kashrut-certification liability and trust — your single existential risk.** If a listed restaurant's kosher status is wrong, lapsed, or misrepresented, you don't lose a customer — you lose the *entire community's trust* permanently, and possibly face real liability. This is worse than any food-safety issue for a generic app. You need a documented certification-verification process, a source of truth (which hechsher/agency, expiry, re-check cadence), and a fast takedown path *before* you scale listings or run a single TikTok that drives traffic.

### Recommended sequence (and why order matters)

The order matters because each stage de-risks the next; doing them out of order burns your scarcest assets (community trust, founder time, early-adopter goodwill).

1. **Kashrut trust foundation (do first, non-negotiable).** Verify and document the kosher certification of all 4 restaurants: which agency, valid through when, re-check cadence, takedown process. This must exist before you drive any traffic — one bad listing during a viral moment is fatal.
2. **Prove demand in a tiny, controlled slice.** Pick 1–2 of the strongest restaurants and manufacture real repeat orders — hand-recruit 20–50 real consumers (not signups: actual repeat orderers), even via concierge/manual ops. The goal is a believable *repeat-order rate*, not GMV.
3. **Run content as a demand test, not a brand campaign.** Film 3–5 TikToks, instrument the full funnel (view → install → first order → repeat), and measure conversion. Treat it as an experiment with a kill/scale decision, not a foregone strategy.
4. **Fix the first-order experience before scaling traffic.** Ensure your 4 restaurants + 5 couriers can actually deliver a great first order under a spike. Don't drive demand into a broken or thin supply.
5. **Only then revisit monetization — and let volume choose the model.** Once you have proven order volume, you'll know whether commission already works (it might) or whether a subscription is even sellable. Pricing is the *last* decision, set against real data, not the first.

Keep commission for now. It's $0-risk to the restaurant and to you while volume is near zero, and it preserves the honest signal. Don't touch pricing until step 5.

### Decisions to make + cheap validations BEFORE committing

**Decisions the founder must make:**

- Is the near-term goal **demand** or **revenue**? (It should be demand. Subscription revenue now is a vanity metric.)
- What is the **source of truth and process for kosher certification**, and who owns it if you're hit by a bus?
- How do you de-risk **founder-dependency** in the content engine — is there a format that survives you not being on camera every time?
- How far, if at all, do you want to tie the brand to **broader Jewish missions** — and where's the line you won't cross?

**Validate cheaply, this week, before committing a dollar of model change:**

- **Talk to the 4 current restaurants about subscription willingness — directly.** "Would you pay $350–500/mo flat instead of commission?" Their faces will answer the whole Idea #1. (Predict: at current volume, no.)
- **Ask each restaurant what order volume they'd need** to make a flat fee worth it. That number, vs. reality, tells you how far you are.
- **Run 3–5 TikToks now** with full-funnel tracking. Cost: your time. Output: a real view→order conversion rate. This is the single highest-value cheap test you can run.
- **Hand-recruit 20–50 real repeat orderers** via concierge ops and measure the repeat rate. This proves (or kills) the core thesis cheaply.
- **Verify all 4 restaurants' kashrut status today** — a phone call to each hechsher. Free, and existential.

### What I'd want to know that the brief can't answer

- Of the ~2 people who ordered, did **anyone order twice**? Repeat rate is the whole ballgame and the brief doesn't have it.
- **Why** did 62 of 64 never add to cart — no selection (only 4 restaurants)? price? friction in the app? bad/empty menus? delivery radius? You need the actual drop-off reason.
- What's the **realistic total addressable market** for this specific community in this single market — hundreds, thousands, tens of thousands of households?
- What do the **4 restaurants do today** for delivery (UberEats? DoorDash? in-house? none?), and what would make KosherEats worth switching to or adding?
- What's the **founder's runway and time budget** — months of cash, and hours/week available for the personally-filmed content engine?
- Are there **kosher restaurants in this market not yet on the platform**, and why aren't they — supply objection, awareness, or trust?
- What does a **great first-order experience** look like operationally with 5 couriers, and what's the max order spike that supply can absorb before quality breaks?
- Is there any **existing community distribution** (a shul, WhatsApp groups, a community figure, an org) that could seed demand faster and cheaper than cold TikTok?

---

## For the next (strategy) session — the brief

Bring this whole doc, but the crux to work through:

1. **Demand before monetization?** The reality grounding says the constraint is that almost no one orders. Does it make sense to change pricing (Idea 1) *before* there's order volume — or does that come later, once demand is proven? (The risk lens argues strongly for "later.") I want a real opinion, not validation.
2. **What does a restaurant actually pay $350–500/mo *for* — delivery or marketing?** If it's really marketing (Idea 2), should the subscription be priced and pitched as marketing? Does that change the number?
3. **Tiering without disclosure** — is "tier on in-app order volume + self-selected feature plans" the right call, or is tiering itself premature?
4. **The right sequence** — kashrut-trust foundation → prove repeat demand in a tiny slice → content as a measured demand test → fix first-order experience → *then* revisit pricing. Is that ordering right? What would you change?
5. **Founder-dependency** — the whole content engine is me, on camera. How do I keep that authentic but not have it die the moment I'm out of bandwidth?
6. **The mission angle** — how far do I tie KosherEats to broader Jewish causes without it reading as exploitative or inviting backlash? Where's the line?

**Cheap things to validate this week (from the risk lens):** ask the 4 current restaurants directly if they'd pay a flat $350–500/mo; ask what order volume would make it worth it; run 3–5 TikToks with full-funnel tracking; hand-recruit 20–50 real *repeat* orderers; verify all 4 restaurants' kashrut certification today.

---

*Analysis lenses below were developed as decision-support (illustrative numbers are marked as such, not market data). My two ideas above are the source of truth for intent.*
