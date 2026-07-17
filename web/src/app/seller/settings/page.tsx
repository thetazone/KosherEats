"use client";

// Seller restaurant settings (S20). Mirrors the iOS RestaurantSettingsView:
// GET /seller/restaurant populates the form, Save PUTs the edited fields via
// PUT /seller/restaurant (pointer fields + COALESCE server-side, so we send a
// partial payload and never touch is_open — the open/closed toggle belongs to
// the dashboard, and omitting it here avoids the stale-snapshot flip the iOS
// app has to re-fetch around). Money fields take dollars and convert to
// integer cents. The Kosher Certification section edits agency + flags and
// shows the current certificate with a presign-upload replace flow that
// persists kosher_certificate_url on Save.

import Link from "next/link";
import { useCallback, useEffect, useRef, useState } from "react";
import {
  BadgeCheck,
  Car,
  CheckCircle2,
  Loader2,
  MapPin,
  Store,
} from "lucide-react";
import { PhotoUpload } from "@/components/seller/PhotoUpload";
import { centsToDollars, parseCents, sellerApi } from "@/lib/sellerApi";
import type {
  DeliveryMode,
  KosherCertification,
  SellerRestaurant,
  UpdateRestaurantRequest,
} from "@/types/seller";

const KOSHER_CERTIFICATIONS: KosherCertification[] = [
  "OU",
  "OK",
  "Kof-K",
  "Star-K",
  "cRc",
  "Badatz",
  "Chof-K",
  "other",
];

function certLabel(cert: KosherCertification): string {
  return cert === "other" ? "Other" : cert;
}

export default function SellerSettingsPage() {
  const [restaurant, setRestaurant] = useState<SellerRestaurant | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // ── Form state ──
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [phone, setPhone] = useState("");
  const [email, setEmail] = useState("");
  const [cuisine, setCuisine] = useState("");
  const [street, setStreet] = useState("");
  const [city, setCity] = useState("");
  const [stateField, setStateField] = useState("");
  const [zip, setZip] = useState("");
  const [deliveryFee, setDeliveryFee] = useState("");
  const [minOrder, setMinOrder] = useState("");
  const [estMin, setEstMin] = useState("");
  const [estMax, setEstMax] = useState("");
  // "restaurant" = self-delivery, "external" = Uber Direct. The legacy
  // "platform" (KosherEats couriers) mode is never offered as a choice but
  // passes through untouched so a profile edit doesn't silently reroute a
  // platform restaurant (same rule as the iOS settings screen).
  const [deliveryMode, setDeliveryMode] = useState<DeliveryMode>("external");
  const [certification, setCertification] = useState<KosherCertification>("OU");
  const [certifyingAgency, setCertifyingAgency] = useState("");
  const [isCholovYisroel, setIsCholovYisroel] = useState(false);
  const [isPasYisroel, setIsPasYisroel] = useState(false);
  const [isGlattKosher, setIsGlattKosher] = useState(false);
  const [certUrl, setCertUrl] = useState("");

  const [uploadsInFlight, setUploadsInFlight] = useState(0);
  const trackUpload = {
    start: () => setUploadsInFlight((n) => n + 1),
    end: () => setUploadsInFlight((n) => Math.max(0, n - 1)),
  };

  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);
  const savedTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  function populate(r: SellerRestaurant) {
    setName(r.name);
    setDescription(r.description);
    setPhone(r.phone);
    setEmail(r.email);
    setCuisine((r.cuisine_type ?? []).join(", "));
    setStreet(r.street);
    setCity(r.city);
    setStateField(r.state);
    setZip(r.zip_code);
    setDeliveryFee(centsToDollars(r.delivery_fee));
    setMinOrder(centsToDollars(r.min_order));
    setEstMin(String(r.est_delivery_min));
    setEstMax(String(r.est_delivery_max));
    setDeliveryMode(r.delivery_mode || "external");
    setCertification(
      KOSHER_CERTIFICATIONS.includes(r.kosher_certification) ? r.kosher_certification : "other",
    );
    setCertifyingAgency(r.certifying_agency);
    setIsCholovYisroel(r.is_cholov_yisroel);
    setIsPasYisroel(r.is_pas_yisroel);
    setIsGlattKosher(r.is_glatt_kosher);
    setCertUrl(r.kosher_certificate_url);
  }

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const r = await sellerApi.restaurants.get();
      setRestaurant(r);
      populate(r);
    } catch (err) {
      setError((err as Error).message || "Failed to load settings");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  useEffect(() => {
    return () => {
      if (savedTimer.current) clearTimeout(savedTimer.current);
    };
  }, []);

  function validate(): { body: UpdateRestaurantRequest } | { problem: string } {
    const trimmedName = name.trim();
    const trimmedEmail = email.trim();
    const trimmedState = stateField.trim();

    if (!trimmedName) return { problem: "Restaurant name is required." };
    if (trimmedName.length > 200) {
      return { problem: "Restaurant name must be 200 characters or fewer." };
    }
    if (description.trim().length > 2000) {
      return { problem: "Description is too long (max 2000 characters)." };
    }
    if (!phone.trim()) return { problem: "Phone number is required." };
    if (!trimmedEmail || !trimmedEmail.includes("@") || trimmedEmail.includes(" ")) {
      return { problem: "A valid email address is required." };
    }
    if (trimmedState && trimmedState.length !== 2) {
      return { problem: "State abbreviation must be exactly 2 characters (e.g. NY)." };
    }
    if (!zip.trim()) return { problem: "ZIP code is required." };

    const feeCents = parseCents(deliveryFee);
    if (feeCents === null) {
      return { problem: "Enter a valid delivery fee in dollars, e.g. 4.99." };
    }
    const minOrderCents = parseCents(minOrder);
    if (minOrderCents === null) {
      return { problem: "Enter a valid minimum order in dollars, e.g. 15.00." };
    }

    const parsedMin = Number(estMin.trim());
    const parsedMax = Number(estMax.trim());
    if (!Number.isInteger(parsedMin) || parsedMin <= 0) {
      return { problem: "Estimated minimum delivery time must be a positive number of minutes." };
    }
    if (!Number.isInteger(parsedMax) || parsedMax <= 0) {
      return { problem: "Estimated maximum delivery time must be a positive number of minutes." };
    }
    if (parsedMin > parsedMax) {
      return { problem: "Estimated minimum delivery time can't exceed the maximum." };
    }

    const body: UpdateRestaurantRequest = {
      name: trimmedName,
      description: description.trim(),
      phone: phone.trim(),
      email: trimmedEmail,
      street: street.trim(),
      city: city.trim(),
      state: trimmedState,
      zip_code: zip.trim(),
      cuisine_type: cuisine
        .split(",")
        .map((c) => c.trim())
        .filter(Boolean),
      delivery_fee: feeCents,
      min_order: minOrderCents,
      est_delivery_min: parsedMin,
      est_delivery_max: parsedMax,
      delivery_mode: deliveryMode,
      kosher_certification: certification,
      certifying_agency: certifyingAgency.trim(),
      is_cholov_yisroel: isCholovYisroel,
      is_pas_yisroel: isPasYisroel,
      is_glatt_kosher: isGlattKosher,
    };
    // Never send an empty certificate URL — the backend COALESCE would
    // overwrite the real certificate with "" (the column is NOT NULL, so
    // only omission keeps it intact).
    if (certUrl) body.kosher_certificate_url = certUrl;
    return { body };
  }

  async function save() {
    if (saving) return;
    if (uploadsInFlight > 0) {
      setSaveError("The certificate is still uploading — one moment.");
      return;
    }
    const result = validate();
    if ("problem" in result) {
      setSaveError(result.problem);
      setSaved(false);
      return;
    }
    setSaving(true);
    setSaveError(null);
    setSaved(false);
    try {
      // The backend returns the full post-update restaurant; repopulate so
      // the form and server stay in lockstep (e.g. normalized certification).
      const updated = await sellerApi.restaurants.update(result.body);
      setRestaurant(updated);
      populate(updated);
      setSaved(true);
      if (savedTimer.current) clearTimeout(savedTimer.current);
      savedTimer.current = setTimeout(() => setSaved(false), 3000);
    } catch (err) {
      setSaveError((err as Error).message || "Failed to save settings. Please try again.");
    } finally {
      setSaving(false);
    }
  }

  // ── Render ─────────────────────────────────────────────────

  if (loading) {
    return (
      <div>
        <h1 className="text-2xl font-bold mb-6">Settings</h1>
        <div className="space-y-4">
          {Array.from({ length: 4 }).map((_, i) => (
            <div key={i} className="card p-5 h-40 animate-pulse" aria-hidden="true" />
          ))}
        </div>
      </div>
    );
  }

  if (error || !restaurant) {
    const noRestaurant = (error ?? "").toLowerCase().includes("restaurant not found");
    return (
      <div>
        <h1 className="text-2xl font-bold mb-6">Settings</h1>
        <div className="card p-10 text-center">
          {noRestaurant ? (
            <>
              <Store className="w-10 h-10 text-dark-500 mx-auto mb-3" aria-hidden="true" />
              <p className="font-semibold mb-1">No restaurant yet</p>
              <p className="text-sm text-dark-400 mb-5">
                Set up your restaurant to manage its settings.
              </p>
              <Link href="/seller/onboarding" className="btn-primary inline-block">
                Set up your restaurant
              </Link>
            </>
          ) : (
            <>
              <p className="text-red-400 mb-4">{error || "Couldn't load your restaurant settings."}</p>
              <button onClick={load} className="btn-secondary">
                Try again
              </button>
            </>
          )}
        </div>
      </div>
    );
  }

  return (
    <div className="max-w-2xl">
      <h1 className="text-2xl font-bold mb-6">Settings</h1>

      <form
        onSubmit={(e) => {
          e.preventDefault();
          save();
        }}
        className="space-y-5"
      >
        {/* ── Restaurant info ── */}
        <section className="card p-6 space-y-5">
          <SectionTitle icon={<Store className="w-4 h-4" />} title="Restaurant info" />
          <Field id="st-name" label="Name" value={name} onChange={setName} required />
          <div>
            <label htmlFor="st-description" className="block text-sm text-dark-300 mb-1.5">
              Description
            </label>
            <textarea
              id="st-description"
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              rows={3}
              maxLength={2000}
              className="input w-full resize-none"
            />
          </div>
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
            <Field id="st-phone" label="Phone" value={phone} onChange={setPhone} type="tel" required />
            <Field id="st-email" label="Email" value={email} onChange={setEmail} type="email" required />
          </div>
          <Field
            id="st-cuisine"
            label="Cuisine types"
            value={cuisine}
            onChange={setCuisine}
            placeholder="Israeli, Middle Eastern"
            hint="Comma-separated — helps customers find you in search."
          />
        </section>

        {/* ── Address ── */}
        <section className="card p-6 space-y-5">
          <SectionTitle icon={<MapPin className="w-4 h-4" />} title="Address" />
          <Field id="st-street" label="Street" value={street} onChange={setStreet} />
          <div className="grid grid-cols-1 sm:grid-cols-3 gap-4">
            <Field id="st-city" label="City" value={city} onChange={setCity} />
            <Field id="st-state" label="State" value={stateField} onChange={setStateField} placeholder="NY" />
            <Field id="st-zip" label="ZIP code" value={zip} onChange={setZip} required />
          </div>
        </section>

        {/* ── Delivery ── */}
        <section className="card p-6 space-y-5">
          <SectionTitle icon={<Car className="w-4 h-4" />} title="Delivery" />
          <div>
            <span className="block text-sm text-dark-300 mb-2">Delivery method</span>
            <div className="grid grid-cols-2 gap-2" role="radiogroup" aria-label="Delivery method">
              {(
                [
                  { value: "external", label: "Uber Direct" },
                  { value: "restaurant", label: "Self-delivery" },
                ] as const
              ).map((opt) => (
                <button
                  key={opt.value}
                  type="button"
                  role="radio"
                  aria-checked={deliveryMode === opt.value}
                  onClick={() => setDeliveryMode(opt.value)}
                  className={`py-2.5 px-3 rounded-xl text-sm font-semibold transition-colors ${
                    deliveryMode === opt.value
                      ? "bg-brand-500 text-white"
                      : "bg-dark-800 text-dark-300 border border-dark-700 hover:bg-dark-700 hover:text-white"
                  }`}
                >
                  {opt.label}
                </button>
              ))}
            </div>
            {deliveryMode === "platform" && (
              <p className="text-xs text-dark-500 mt-2">
                Currently: KosherEats couriers — pick a method above to change.
              </p>
            )}
          </div>
          <p className="text-xs text-dark-500">
            The delivery fee below is your self-delivery fee — what you charge and keep when you
            deliver an order yourself.
          </p>
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
            <Field
              id="st-fee"
              label="Delivery fee ($)"
              value={deliveryFee}
              onChange={setDeliveryFee}
              inputMode="decimal"
              placeholder="4.99"
            />
            <Field
              id="st-min-order"
              label="Minimum order ($)"
              value={minOrder}
              onChange={setMinOrder}
              inputMode="decimal"
              placeholder="15.00"
            />
          </div>
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
            <Field
              id="st-est-min"
              label="Est. delivery min (minutes)"
              value={estMin}
              onChange={setEstMin}
              inputMode="numeric"
              placeholder="20"
            />
            <Field
              id="st-est-max"
              label="Est. delivery max (minutes)"
              value={estMax}
              onChange={setEstMax}
              inputMode="numeric"
              placeholder="45"
            />
          </div>
        </section>

        {/* ── Kosher certification ── */}
        <section className="card p-6 space-y-5">
          <SectionTitle icon={<BadgeCheck className="w-4 h-4" />} title="Kosher certification" />
          <div>
            <span className="block text-sm text-dark-300 mb-2">Certification</span>
            <div className="flex flex-wrap gap-2" role="radiogroup" aria-label="Kosher certification">
              {KOSHER_CERTIFICATIONS.map((cert) => (
                <button
                  key={cert}
                  type="button"
                  role="radio"
                  aria-checked={certification === cert}
                  onClick={() => setCertification(cert)}
                  className={`px-4 py-2 rounded-xl text-sm font-semibold transition-colors ${
                    certification === cert
                      ? "bg-brand-500 text-white"
                      : "bg-dark-800 text-dark-300 border border-dark-700 hover:bg-dark-700 hover:text-white"
                  }`}
                >
                  {certLabel(cert)}
                </button>
              ))}
            </div>
          </div>
          <Field
            id="st-agency"
            label="Certifying agency"
            value={certifyingAgency}
            onChange={setCertifyingAgency}
            placeholder="Vaad Harabonim of Flatbush"
            hint='The specific agency or rav behind your certificate — especially if you picked "Other".'
          />
          <div>
            <span className="block text-sm text-dark-300 mb-2">Additional certifications</span>
            <div className="space-y-2">
              <ToggleRow label="Cholov Yisroel" checked={isCholovYisroel} onChange={setIsCholovYisroel} />
              <ToggleRow label="Pas Yisroel" checked={isPasYisroel} onChange={setIsPasYisroel} />
              <ToggleRow label="Glatt Kosher" checked={isGlattKosher} onChange={setIsGlattKosher} />
            </div>
          </div>
          <PhotoUpload
            label="Certificate"
            hint="Your current kosher certificate. Pick a new photo to replace it, then Save — the KosherEats team reviews certificate changes."
            kind="restaurant/certificate"
            value={certUrl}
            onChange={setCertUrl}
            aspectClass="aspect-[4/3]"
            allowRemove={false}
            track={trackUpload}
          />
        </section>

        {saveError && (
          <div
            role="alert"
            className="bg-red-900/30 border border-red-800 text-red-400 rounded-xl px-4 py-3 text-sm"
          >
            {saveError}
          </div>
        )}
        {saved && (
          <div
            role="status"
            className="flex items-center gap-2 bg-green-500/10 border border-green-500/30 text-green-400 rounded-xl px-4 py-3 text-sm"
          >
            <CheckCircle2 className="w-4 h-4 shrink-0" aria-hidden="true" />
            Settings saved
          </div>
        )}

        <button
          type="submit"
          disabled={saving || uploadsInFlight > 0}
          className="btn-primary w-full flex items-center justify-center gap-2 disabled:opacity-50 disabled:cursor-not-allowed"
        >
          {saving && <Loader2 className="w-4 h-4 animate-spin" aria-hidden="true" />}
          {saving ? "Saving…" : "Save changes"}
        </button>
      </form>
    </div>
  );
}

// ── Small form primitives (matches onboarding) ───────────────

function SectionTitle({ icon, title }: { icon: React.ReactNode; title: string }) {
  return (
    <h2 className="flex items-center gap-2 text-base font-semibold">
      <span className="text-brand-500">{icon}</span>
      {title}
    </h2>
  );
}

function Field({
  id,
  label,
  value,
  onChange,
  type = "text",
  inputMode,
  placeholder,
  required = false,
  hint,
}: {
  id: string;
  label: string;
  value: string;
  onChange: (v: string) => void;
  type?: string;
  inputMode?: "decimal" | "numeric";
  placeholder?: string;
  required?: boolean;
  hint?: string;
}) {
  return (
    <div>
      <label htmlFor={id} className="block text-sm text-dark-300 mb-1.5">
        {label}
        {required && <span className="text-brand-500 ml-0.5">*</span>}
      </label>
      <input
        id={id}
        type={type}
        inputMode={inputMode}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder={placeholder}
        className="input w-full"
      />
      {hint && <p className="text-xs text-dark-500 mt-1.5">{hint}</p>}
    </div>
  );
}

function ToggleRow({
  label,
  checked,
  onChange,
}: {
  label: string;
  checked: boolean;
  onChange: (v: boolean) => void;
}) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      onClick={() => onChange(!checked)}
      className="flex items-center justify-between w-full px-4 py-3 rounded-xl bg-dark-800 border border-dark-700 hover:border-dark-600 transition-colors"
    >
      <span className="text-sm font-medium">{label}</span>
      <span
        className={`relative inline-flex h-6 w-11 shrink-0 rounded-full transition-colors ${
          checked ? "bg-brand-500" : "bg-dark-700"
        }`}
        aria-hidden="true"
      >
        <span
          className={`absolute top-0.5 h-5 w-5 rounded-full bg-white transition-transform ${
            checked ? "translate-x-[1.375rem]" : "translate-x-0.5"
          }`}
        />
      </span>
    </button>
  );
}
