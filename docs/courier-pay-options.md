# Courier Pay Structure — Per-Delivery vs Hourly

## The short answer

You **can** pay per delivery, but where and how matters a lot.

## Lakewood NJ (your best launch market for this)

- **No delivery-worker-specific minimum pay law** — unlike NYC
- NJ minimum wage is $15.49/hr but only applies to W-2 employees
- If couriers are 1099 contractors, no hourly floor — you can pay $6, $8, $10 per delivery

**The risk:** NJ uses a strict **ABC test** for worker classification. Prong B says a worker is an employee unless the work is "outside the usual course of the hiring entity's business." Delivery is KosherEats' core business, so couriers performing deliveries likely fail Prong B.

**Misclassification penalties in NJ:** Back wages, back taxes, $250-$1,000 per worker fines, potential criminal liability (A.B. 5890, 2020).

## NYC

- **DCWP minimum: ~$22.13/hr** (effective April 2026) for any third-party food delivery service
- You can structure pay per delivery, but each delivery must meet the per-minute equivalent
- A 20-minute delivery must pay at least ~$7.38; a 30-minute delivery at least ~$11.07
- Non-negotiable — DCWP is actively enforcing (fined HungryPanda $875K+)

## Lowest-cost paths to launch

### Option 1: Launch in Lakewood with per-delivery 1099 couriers (cheapest, riskiest)
- Pay $7-10 per delivery depending on distance
- No hourly minimums, no payroll taxes
- Risk: NJ ABC test misclassification exposure
- Mitigation: couriers must have true autonomy (choose hours, reject deliveries, work for others, own vehicle)

### Option 2: Use Uber Direct / DoorDash Drive as your only couriers (no own fleet)
- Pay per delivery ($6.99-$9.75) to the provider
- **Zero classification risk** — they handle their couriers
- You're just paying for a service
- Already integrated in your codebase as of today

### Option 3: Hybrid W-2 piece-rate (safest for own fleet)
- Pay per delivery as base compensation
- "True up" each pay period: if total deliveries × per-delivery rate < hours worked × $15.49, pay the difference
- Legal in all states
- More expensive but no classification risk

### Option 4: Contract with a local delivery company (best of both worlds)
- Find or create a separate LLC that provides courier services
- KosherEats pays the company per delivery
- The company handles worker classification, pay, insurance
- Shifts Prong B — the courier company's core business IS delivery

## Recommendation for minimum viable launch

**Start with Option 2 (Uber Direct / DoorDash Drive only).** Zero upfront cost for couriers, no HR/classification risk, already built into the code. Your delivery fee to consumers ($5.99-7.99) roughly covers the per-delivery cost.

As volume grows, recruit 2-3 local couriers in Lakewood under **Option 4** (separate delivery LLC) or **Option 3** (W-2 piece-rate). Your dispatcher already tries own couriers first, then falls back to Uber Direct → DoorDash Drive.

**Bottom line:** You do NOT need money set aside for couriers to start. The external providers bill per delivery — no monthly minimums, no upfront commitments. Your first dollar of revenue covers your first delivery.
