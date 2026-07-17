"use client";

// Seller onboarding (S15/R1). Multi-step "create your first restaurant" flow
// mirroring the iOS SellerOnboardingFlow: basics -> address -> kosher
// certification -> review -> POST /seller/restaurants -> pending-approval
// result. New restaurants land approval_status="pending" and stay hidden from
// consumers until the KosherEats team approves them, so the result step sets
// expectations rather than celebrating a launch.
//
// The backend requires name/email/phone/full address/photo/cert/cert-photo up
// front (seller.go CreateRestaurant), so each step validates before advancing
// and the kosher certificate photo hard-blocks submission.

import { useRef, useState } from "react";
import {
  ArrowLeft,
  ArrowRight,
  BadgeCheck,
  CheckCircle2,
  ImagePlus,
  Loader2,
  MapPin,
  Pencil,
  Store,
  X,
} from "lucide-react";
import { sellerApi, sellerAuth, uploadImage, type SellerUploadKind } from "@/lib/sellerApi";
import type {
  CreateRestaurantRequest,
  KosherCertification,
  SellerRestaurant,
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

const STEPS = ["Basics", "Address", "Kosher", "Review"] as const;
type StepIndex = 0 | 1 | 2 | 3;

const CERT_PHOTO_REQUIRED = "Kosher certificate photo is required";

export default function SellerOnboardingPage() {
  const [step, setStep] = useState<StepIndex>(0);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [created, setCreated] = useState<SellerRestaurant | null>(null);

  // ── Basics ──
  const [name, setName] = useState("");
  const [email, setEmail] = useState("");
  const [phone, setPhone] = useState("");
  const [cuisine, setCuisine] = useState("");
  const [description, setDescription] = useState("");
  const [imageUrl, setImageUrl] = useState("");
  const [logoUrl, setLogoUrl] = useState("");

  // ── Address ──
  const [street, setStreet] = useState("");
  const [city, setCity] = useState("");
  const [stateField, setStateField] = useState("");
  const [zip, setZip] = useState("");
  const [latitude, setLatitude] = useState("");
  const [longitude, setLongitude] = useState("");

  // ── Kosher ──
  const [certification, setCertification] = useState<KosherCertification>("OU");
  const [certifyingAgency, setCertifyingAgency] = useState("");
  const [isCholovYisroel, setIsCholovYisroel] = useState(false);
  const [isPasYisroel, setIsPasYisroel] = useState(false);
  const [isGlattKosher, setIsGlattKosher] = useState(false);
  const [certUrl, setCertUrl] = useState("");

  // Photo uploads still in flight — Continue/Submit waits for them so we never
  // POST a half-filled form or navigate away from an unfinished PUT.
  const [uploadsInFlight, setUploadsInFlight] = useState(0);
  const trackUpload = {
    start: () => setUploadsInFlight((n) => n + 1),
    end: () => setUploadsInFlight((n) => Math.max(0, n - 1)),
  };

  function validateBasics(): string | null {
    if (!name.trim()) return "Restaurant name is required.";
    if (name.trim().length > 200) return "Restaurant name is too long (max 200 characters).";
    const trimmedEmail = email.trim();
    if (!trimmedEmail || !trimmedEmail.includes("@") || trimmedEmail.includes(" ")) {
      return "A valid contact email is required.";
    }
    if (!phone.trim()) return "A contact phone number is required.";
    if (description.trim().length > 2000) return "Description is too long (max 2000 characters).";
    if (!imageUrl) return "A restaurant photo is required — it's what customers see in the marketplace.";
    return null;
  }

  // Manual lat/lng with the same guards as admin/restaurants/new — no geocoding
  // API key is wired up, and a (0,0) "Null Island" default buries the
  // restaurant in distance-sorted listings.
  function validateAddress(): string | null {
    if (!street.trim() || !city.trim() || !stateField.trim() || !zip.trim()) {
      return "Full address (street, city, state, zip) is required.";
    }
    const lat = parseFloat(latitude);
    const lng = parseFloat(longitude);
    if (!Number.isFinite(lat) || !Number.isFinite(lng)) {
      return "Latitude and longitude are required and must be valid numbers.";
    }
    if (lat < -90 || lat > 90) return "Latitude must be between -90 and 90.";
    if (lng < -180 || lng > 180) return "Longitude must be between -180 and 180.";
    if (lat === 0 && lng === 0) {
      return "Coordinates can't be (0, 0). Enter the restaurant's real latitude and longitude.";
    }
    return null;
  }

  function validateKosher(): string | null {
    if (!certUrl) return CERT_PHOTO_REQUIRED;
    return null;
  }

  const validators: Record<StepIndex, () => string | null> = {
    0: validateBasics,
    1: validateAddress,
    2: validateKosher,
    3: () => null,
  };

  function goNext() {
    if (uploadsInFlight > 0) {
      setError("A photo is still uploading — one moment.");
      return;
    }
    const problem = validators[step]();
    if (problem) {
      setError(problem);
      return;
    }
    setError(null);
    setStep((s) => Math.min(s + 1, 3) as StepIndex);
  }

  function goBack() {
    setError(null);
    setStep((s) => Math.max(s - 1, 0) as StepIndex);
  }

  function jumpTo(target: StepIndex) {
    setError(null);
    setStep(target);
  }

  async function submit() {
    // Hard-block: a restaurant must never reach review without its current
    // kosher certificate on file, even if state was mutated after step 3.
    if (!certUrl) {
      setError(CERT_PHOTO_REQUIRED);
      setStep(2);
      return;
    }
    for (const s of [0, 1] as StepIndex[]) {
      const problem = validators[s]();
      if (problem) {
        setError(problem);
        setStep(s);
        return;
      }
    }
    if (uploadsInFlight > 0) {
      setError("A photo is still uploading — one moment.");
      return;
    }

    const body: CreateRestaurantRequest = {
      name: name.trim(),
      description: description.trim() || undefined,
      image_url: imageUrl,
      logo_url: logoUrl || undefined,
      phone: phone.trim(),
      email: email.trim(),
      street: street.trim(),
      city: city.trim(),
      state: stateField.trim(),
      zip_code: zip.trim(),
      lat: parseFloat(latitude),
      lng: parseFloat(longitude),
      kosher_certification: certification,
      certifying_agency: certifyingAgency.trim() || undefined,
      kosher_certificate_url: certUrl,
      cuisine_type: cuisine
        .split(",")
        .map((c) => c.trim())
        .filter(Boolean),
      is_cholov_yisroel: isCholovYisroel,
      is_pas_yisroel: isPasYisroel,
      is_glatt_kosher: isGlattKosher,
    };

    setSubmitting(true);
    setError(null);
    try {
      const restaurant = await sellerApi.restaurants.create(body);
      // Make the new restaurant the active one so the dashboard opens scoped
      // to it. The result CTA does a full navigation so the layout's picker
      // refetches the restaurant list.
      sellerAuth.setActiveRestaurantId(restaurant.id);
      setCreated(restaurant);
    } catch (err) {
      setError((err as Error).message || "Failed to submit your restaurant. Please try again.");
    } finally {
      setSubmitting(false);
    }
  }

  // ── Pending-approval result state ──
  if (created) {
    return (
      <div className="max-w-xl mx-auto py-8">
        <div className="card p-8 text-center">
          <div className="inline-flex items-center justify-center w-14 h-14 rounded-2xl bg-brand-500/15 text-brand-500 mb-5">
            <CheckCircle2 className="w-7 h-7" />
          </div>
          <h1 className="text-2xl font-bold mb-2">{created.name} is submitted!</h1>
          <p className="text-dark-300 mb-6">
            Your restaurant is awaiting approval. You can build your menu now — it will go
            live once the KosherEats team reviews your kosher certification and approves it.
          </p>
          <div className="bg-brand-500/10 border border-brand-500/30 text-brand-300 rounded-xl px-4 py-3 text-sm mb-6 text-left">
            Status: <span className="font-semibold">Pending approval</span>. We&apos;ll email{" "}
            <span className="font-semibold">{created.email}</span> when it&apos;s reviewed.
          </div>
          <button
            onClick={() => window.location.assign("/seller")}
            className="btn-primary w-full"
          >
            Go to dashboard
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className="max-w-2xl mx-auto">
      <h1 className="text-2xl font-bold mb-1">Set up your restaurant</h1>
      <p className="text-dark-400 text-sm mb-6">
        Tell us about your restaurant and kosher certification. Submissions are reviewed by
        the KosherEats team before going live.
      </p>

      <StepHeader current={step} onJump={jumpTo} />

      {error && (
        <div
          role="alert"
          className="bg-red-900/30 border border-red-800 text-red-400 rounded-xl px-4 py-3 mb-5 text-sm"
        >
          {error}
        </div>
      )}

      {step === 0 && (
        <section className="card p-6 space-y-5">
          <SectionTitle icon={<Store className="w-4 h-4" />} title="Restaurant details" />
          <Field
            id="ob-name"
            label="Restaurant name"
            value={name}
            onChange={setName}
            placeholder="Jerusalem Grill"
            required
          />
          <Field
            id="ob-email"
            label="Contact email"
            value={email}
            onChange={setEmail}
            type="email"
            placeholder="orders@restaurant.com"
            required
          />
          <Field
            id="ob-phone"
            label="Phone"
            value={phone}
            onChange={setPhone}
            type="tel"
            inputMode="tel"
            autoComplete="tel"
            placeholder="(718) 555-0123"
            required
          />
          <Field
            id="ob-cuisine"
            label="Cuisine types"
            value={cuisine}
            onChange={setCuisine}
            placeholder="Israeli, Middle Eastern"
            hint="Comma-separated — helps customers find you in search."
          />
          <div>
            <label htmlFor="ob-description" className="block text-sm text-dark-300 mb-1.5">
              Description <span className="text-dark-500">(optional)</span>
            </label>
            <textarea
              id="ob-description"
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              rows={3}
              maxLength={2000}
              className="input w-full resize-none"
              placeholder="Family-run glatt kosher grill serving fresh laffa, shawarma, and salatim."
            />
          </div>
          <PhotoUpload
            label="Restaurant photo"
            hint="Required — the photo customers see in the marketplace. Pick a wide, landscape-friendly photo."
            kind="restaurant/cover"
            value={imageUrl}
            onChange={setImageUrl}
            aspectClass="aspect-video"
            track={trackUpload}
          />
          <PhotoUpload
            label="Logo (optional)"
            hint="Small mark shown as a badge on your card. Skip if your photo already includes your logo."
            kind="restaurant/logo"
            value={logoUrl}
            onChange={setLogoUrl}
            aspectClass="aspect-square max-w-[10rem]"
            track={trackUpload}
          />
        </section>
      )}

      {step === 1 && (
        <section className="card p-6 space-y-5">
          <SectionTitle icon={<MapPin className="w-4 h-4" />} title="Address" />
          <Field
            id="ob-street"
            label="Street"
            value={street}
            onChange={setStreet}
            placeholder="1234 Kings Highway"
            required
          />
          <div className="grid grid-cols-1 sm:grid-cols-3 gap-4">
            <Field id="ob-city" label="City" value={city} onChange={setCity} placeholder="Brooklyn" required />
            <Field id="ob-state" label="State" value={stateField} onChange={setStateField} placeholder="NY" required />
            <Field id="ob-zip" label="Zip" value={zip} onChange={setZip} placeholder="11229" required />
          </div>
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
            <Field
              id="ob-lat"
              label="Latitude"
              value={latitude}
              onChange={setLatitude}
              type="number"
              inputMode="decimal"
              placeholder="40.7128"
              required
            />
            <Field
              id="ob-lng"
              label="Longitude"
              value={longitude}
              onChange={setLongitude}
              type="number"
              inputMode="decimal"
              placeholder="-74.0060"
              required
            />
          </div>
          <p className="text-xs text-dark-500">
            Used for distance-based listings and delivery estimates. Right-click your storefront
            in Google Maps to copy the exact coordinates — don&apos;t leave these at (0, 0).
          </p>
        </section>
      )}

      {step === 2 && (
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
                  className={`px-4 py-2 min-h-[44px] rounded-xl text-sm font-semibold transition-colors ${
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
            id="ob-agency"
            label="Certifying agency (optional)"
            value={certifyingAgency}
            onChange={setCertifyingAgency}
            placeholder="Vaad Harabonim of Flatbush"
            hint='The specific agency or rav behind your certificate — especially if you picked "Other".'
          />
          <PhotoUpload
            label="Kosher certificate photo"
            hint="Required — a clear photo of your current kosher certificate. The KosherEats team reviews it before your restaurant goes live."
            kind="restaurant/certificate"
            value={certUrl}
            onChange={setCertUrl}
            aspectClass="aspect-[4/3]"
            track={trackUpload}
          />
          <div>
            <span className="block text-sm text-dark-300 mb-2">Additional certifications</span>
            <div className="space-y-2">
              <ToggleRow label="Cholov Yisroel" checked={isCholovYisroel} onChange={setIsCholovYisroel} />
              <ToggleRow label="Pas Yisroel" checked={isPasYisroel} onChange={setIsPasYisroel} />
              <ToggleRow label="Glatt Kosher" checked={isGlattKosher} onChange={setIsGlattKosher} />
            </div>
          </div>
        </section>
      )}

      {step === 3 && (
        <section className="space-y-4">
          <ReviewCard title="Restaurant details" onEdit={() => jumpTo(0)}>
            <ReviewRow label="Name" value={name.trim()} />
            <ReviewRow label="Email" value={email.trim()} />
            <ReviewRow label="Phone" value={phone.trim()} />
            <ReviewRow
              label="Cuisine"
              value={cuisine.split(",").map((c) => c.trim()).filter(Boolean).join(", ") || "—"}
            />
            {description.trim() && <ReviewRow label="Description" value={description.trim()} />}
            <div className="flex gap-3 pt-2">
              <Thumb url={imageUrl} alt="Restaurant photo" wide />
              {logoUrl && <Thumb url={logoUrl} alt="Logo" />}
            </div>
          </ReviewCard>

          <ReviewCard title="Address" onEdit={() => jumpTo(1)}>
            <ReviewRow label="Street" value={street.trim()} />
            <ReviewRow
              label="City / State / Zip"
              value={`${city.trim()}, ${stateField.trim()} ${zip.trim()}`}
            />
            <ReviewRow label="Coordinates" value={`${latitude}, ${longitude}`} />
          </ReviewCard>

          <ReviewCard title="Kosher certification" onEdit={() => jumpTo(2)}>
            <ReviewRow label="Certification" value={certLabel(certification)} />
            {certifyingAgency.trim() && (
              <ReviewRow label="Certifying agency" value={certifyingAgency.trim()} />
            )}
            <ReviewRow
              label="Additional"
              value={
                [
                  isCholovYisroel && "Cholov Yisroel",
                  isPasYisroel && "Pas Yisroel",
                  isGlattKosher && "Glatt Kosher",
                ]
                  .filter(Boolean)
                  .join(", ") || "—"
              }
            />
            <div className="pt-2">
              <Thumb url={certUrl} alt="Kosher certificate" wide />
            </div>
          </ReviewCard>

          <p className="text-xs text-dark-500">
            By submitting, your restaurant enters review. It stays hidden from customers until
            the KosherEats team approves your kosher certification.
          </p>
        </section>
      )}

      {/* Step navigation */}
      <div className="flex items-center justify-between gap-3 mt-6">
        {step > 0 ? (
          <button type="button" onClick={goBack} className="btn-secondary flex items-center gap-2">
            <ArrowLeft className="w-4 h-4" />
            Back
          </button>
        ) : (
          <span />
        )}
        {step < 3 ? (
          <button type="button" onClick={goNext} className="btn-primary flex items-center gap-2">
            Continue
            <ArrowRight className="w-4 h-4" />
          </button>
        ) : (
          <button
            type="button"
            onClick={submit}
            disabled={submitting}
            className="btn-primary flex items-center gap-2 disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {submitting && <Loader2 className="w-4 h-4 animate-spin" />}
            {submitting ? "Submitting…" : "Submit for review"}
          </button>
        )}
      </div>
    </div>
  );
}

// ── Step header ──────────────────────────────────────────────

function StepHeader({
  current,
  onJump,
}: {
  current: StepIndex;
  onJump: (step: StepIndex) => void;
}) {
  return (
    <ol className="flex items-center gap-2 mb-6" aria-label={`Step ${current + 1} of ${STEPS.length}`}>
      {STEPS.map((label, i) => {
        const idx = i as StepIndex;
        const done = idx < current;
        const active = idx === current;
        return (
          <li key={label} className="flex-1">
            <button
              type="button"
              // Completed steps are revisitable; forward jumps go through
              // Continue so validation always runs.
              onClick={() => done && onJump(idx)}
              disabled={!done}
              aria-current={active ? "step" : undefined}
              className={`w-full text-left ${done ? "cursor-pointer" : "cursor-default"}`}
            >
              <div
                className={`h-1 rounded-full mb-1.5 ${
                  done || active ? "bg-brand-500" : "bg-dark-800"
                }`}
              />
              <span
                className={`text-xs font-medium ${
                  active ? "text-brand-400" : done ? "text-dark-300" : "text-dark-500"
                }`}
              >
                {label}
              </span>
            </button>
          </li>
        );
      })}
    </ol>
  );
}

// ── Small form primitives ────────────────────────────────────

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
  autoComplete,
  placeholder,
  required = false,
  hint,
}: {
  id: string;
  label: string;
  value: string;
  onChange: (v: string) => void;
  type?: string;
  inputMode?: "decimal" | "numeric" | "tel";
  autoComplete?: string;
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
        autoComplete={autoComplete}
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

// ── Photo upload ─────────────────────────────────────────────

function PhotoUpload({
  label,
  hint,
  kind,
  value,
  onChange,
  aspectClass,
  track,
}: {
  label: string;
  hint: string;
  kind: SellerUploadKind;
  value: string;
  onChange: (url: string) => void;
  aspectClass: string;
  track: { start: () => void; end: () => void };
}) {
  const inputRef = useRef<HTMLInputElement>(null);
  const [uploading, setUploading] = useState(false);
  const [uploadError, setUploadError] = useState<string | null>(null);

  async function onFileSelected(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    // Reset so picking the same file again re-fires onChange.
    e.target.value = "";
    if (!file) return;

    setUploadError(null);
    setUploading(true);
    track.start();
    try {
      const url = await uploadImage(file, kind);
      onChange(url);
    } catch (err) {
      setUploadError((err as Error).message || "Photo upload failed — please try again.");
    } finally {
      setUploading(false);
      track.end();
    }
  }

  return (
    <div>
      <span className="block text-sm text-dark-300 mb-1.5">{label}</span>
      <p className="text-xs text-dark-500 mb-2">{hint}</p>
      <input
        ref={inputRef}
        type="file"
        accept="image/jpeg,image/png,image/webp,image/heic"
        onChange={onFileSelected}
        className="sr-only"
        aria-label={`Upload ${label.toLowerCase()}`}
      />
      <button
        type="button"
        onClick={() => inputRef.current?.click()}
        disabled={uploading}
        className={`relative w-full ${aspectClass} rounded-xl border overflow-hidden transition-colors ${
          value
            ? "border-dark-700"
            : "border-dashed border-dark-600 bg-dark-800/60 hover:border-brand-500"
        } disabled:cursor-wait`}
      >
        {value ? (
          // eslint-disable-next-line @next/next/no-img-element
          <img src={value} alt={label} className="absolute inset-0 w-full h-full object-cover" />
        ) : (
          <span className="absolute inset-0 flex flex-col items-center justify-center gap-2 text-dark-400">
            <ImagePlus className="w-6 h-6 text-brand-500" />
            <span className="text-xs">Tap to add photo</span>
          </span>
        )}
        {uploading && (
          <span className="absolute inset-0 flex items-center justify-center bg-black/50">
            <Loader2 className="w-6 h-6 animate-spin text-white" />
          </span>
        )}
      </button>
      {value && !uploading && (
        <button
          type="button"
          onClick={() => {
            onChange("");
            setUploadError(null);
          }}
          className="flex items-center gap-1.5 min-h-[44px] text-xs font-medium text-red-400 hover:text-red-300 transition-colors mt-1"
        >
          <X className="w-3.5 h-3.5" />
          Remove photo
        </button>
      )}
      {uploadError && <p className="text-xs text-red-400 mt-2">{uploadError}</p>}
    </div>
  );
}

// ── Review step pieces ───────────────────────────────────────

function ReviewCard({
  title,
  onEdit,
  children,
}: {
  title: string;
  onEdit: () => void;
  children: React.ReactNode;
}) {
  return (
    <div className="card p-5">
      <div className="flex items-center justify-between mb-3">
        <h2 className="text-base font-semibold">{title}</h2>
        <button
          type="button"
          onClick={onEdit}
          className="flex items-center gap-1.5 min-h-[44px] -my-2.5 text-xs font-medium text-brand-500 hover:text-brand-400 transition-colors"
        >
          <Pencil className="w-3.5 h-3.5" />
          Edit
        </button>
      </div>
      <div className="space-y-2">{children}</div>
    </div>
  );
}

function ReviewRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex gap-3 text-sm">
      <span className="w-36 shrink-0 text-dark-400">{label}</span>
      <span className="min-w-0 break-words">{value}</span>
    </div>
  );
}

function Thumb({ url, alt, wide = false }: { url: string; alt: string; wide?: boolean }) {
  return (
    <div
      className={`${
        wide ? "w-40 aspect-video" : "w-20 aspect-square"
      } rounded-lg overflow-hidden border border-dark-700 bg-dark-800 shrink-0`}
    >
      {/* eslint-disable-next-line @next/next/no-img-element */}
      <img src={url} alt={alt} className="w-full h-full object-cover" />
    </div>
  );
}
