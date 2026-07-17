"use client";

import { OtpInput } from "@/components/auth/OtpInput";
import { COUNTRIES } from "@/components/auth/VerificationGate";
import { Header } from "@/components/layout/Header";
import {
  linkedProviders as linkedProvidersApi,
  notificationPreferences as notificationPreferencesApi,
  user as userApi,
} from "@/lib/api";
import type { LinkedProvider, NotificationPreferences, User } from "@/types";
import {
  AlertTriangle,
  BadgeCheck,
  ChevronRight,
  CreditCard,
  KeyRound,
  Loader2,
  LogOut,
  Mail,
  MapPin,
  ShieldCheck,
  Smartphone,
} from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useCallback, useEffect, useState } from "react";

// Account hub — the web twin of the iOS ProfileView. Profile name edits go
// through PUT /user/profile (which ignores phone; phone is a login factor).
// Email and phone changes are OTP-verified flows (/user/email/* and
// /user/phone/change/*) — never direct writes. Deleting the account requires
// a typed confirmation because DELETE /user/account is irreversible.

const RESEND_COOLDOWN_SECONDS = 30;
const EMAIL_CODE_LENGTH = 6;
const DELETE_CONFIRM_WORD = "DELETE";

function errorMessage(err: unknown): string {
  return err instanceof Error ? err.message : "Something went wrong. Please try again.";
}

function isUnauthorized(err: unknown): boolean {
  const msg = String(err instanceof Error ? err.message : err).toLowerCase();
  return msg.includes("401") || msg.includes("unauthorized") || msg.includes("invalid token");
}

// Placeholder addresses the backend synthesizes for phone-first / Apple-relay
// accounts — show "No email yet" instead of the internal placeholder.
function isPlaceholderEmail(email: string): boolean {
  return (
    email.endsWith("@phone.koshereats.local") || email.endsWith("@privaterelay.appleid.com")
  );
}

function providerLabel(provider: string): string {
  switch (provider) {
    case "google":
      return "Google";
    case "apple":
      return "Apple";
    case "phone":
      return "Phone number";
    case "email":
      return "Email & password";
    default:
      return provider.charAt(0).toUpperCase() + provider.slice(1);
  }
}

function providerIcon(provider: string) {
  switch (provider) {
    case "phone":
      return <Smartphone className="w-4 h-4" aria-hidden="true" />;
    case "email":
      return <Mail className="w-4 h-4" aria-hidden="true" />;
    default:
      return <KeyRound className="w-4 h-4" aria-hidden="true" />;
  }
}

// ---------------------------------------------------------------------------
// Email change flow (OTP): /user/email/start → 6-digit code → /user/email/verify
// ---------------------------------------------------------------------------

function EmailChangeFlow({
  token,
  onDone,
  onCancel,
}: {
  token: string;
  onDone: () => void;
  onCancel: () => void;
}) {
  const [step, setStep] = useState<"entry" | "code">("entry");
  const [email, setEmail] = useState("");
  const [code, setCode] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [info, setInfo] = useState("");
  const [cooldown, setCooldown] = useState(0);

  const normalized = email.trim().toLowerCase();
  const isEmailShaped =
    normalized.includes("@") && normalized.includes(".") && normalized.length >= 5;

  useEffect(() => {
    if (cooldown <= 0) return;
    const handle = setTimeout(() => setCooldown((s) => s - 1), 1000);
    return () => clearTimeout(handle);
  }, [cooldown]);

  async function sendCode(isResend = false) {
    if (busy || (isResend && cooldown > 0)) return;
    setError("");
    setInfo("");
    setBusy(true);
    try {
      await userApi.emailChange.start(token, normalized);
      setCode("");
      setCooldown(RESEND_COOLDOWN_SECONDS);
      if (isResend) setInfo("We sent you a new code.");
      else setStep("code");
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  const verifyCode = useCallback(
    async (submitted: string) => {
      if (submitted.length !== EMAIL_CODE_LENGTH || busy) return;
      setError("");
      setBusy(true);
      try {
        await userApi.emailChange.verify(token, normalized, submitted);
        onDone();
      } catch (err) {
        setError(errorMessage(err));
        setCode("");
      } finally {
        setBusy(false);
      }
    },
    [token, normalized, busy, onDone]
  );

  return (
    <div className="mt-3 rounded-xl border border-dark-700 bg-dark-800/50 p-4 space-y-4">
      {info && (
        <div className="bg-dark-800 border border-dark-700 text-dark-300 rounded-xl px-4 py-3 text-sm">
          {info}
        </div>
      )}
      {error && (
        <div className="bg-red-900/30 border border-red-800 text-red-400 rounded-xl px-4 py-3 text-sm">
          {error}
        </div>
      )}

      {step === "entry" ? (
        <form
          onSubmit={(e) => {
            e.preventDefault();
            void sendCode();
          }}
          className="space-y-3"
        >
          <div>
            <label htmlFor="account-new-email" className="block text-sm text-dark-300 mb-1.5">
              New email
            </label>
            <input
              id="account-new-email"
              type="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              className="input w-full"
              placeholder="you@example.com"
              autoComplete="email"
              autoFocus
              required
            />
            <p className="text-xs text-dark-500 mt-1.5">
              We&apos;ll send a 6-digit code to confirm you own this inbox.
            </p>
          </div>
          <div className="flex gap-2">
            <button
              type="submit"
              disabled={busy || !isEmailShaped}
              className="btn-primary text-sm py-2 px-4 inline-flex items-center gap-2 disabled:opacity-50 disabled:cursor-not-allowed"
            >
              {busy && <Loader2 className="w-4 h-4 animate-spin" aria-hidden="true" />}
              {busy ? "Sending…" : "Send code"}
            </button>
            <button type="button" onClick={onCancel} className="btn-secondary text-sm py-2 px-4">
              Cancel
            </button>
          </div>
        </form>
      ) : (
        <div className="space-y-3">
          <p className="text-sm text-dark-400">
            Enter the 6-digit code we sent to <span className="text-white">{normalized}</span>.
          </p>
          <OtpInput
            value={code}
            onChange={setCode}
            onComplete={verifyCode}
            length={EMAIL_CODE_LENGTH}
            disabled={busy}
            error={!!error}
          />
          <button
            type="button"
            onClick={() => void verifyCode(code)}
            disabled={busy || code.length !== EMAIL_CODE_LENGTH}
            className="btn-primary w-full text-sm flex items-center justify-center gap-2 disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {busy && <Loader2 className="w-4 h-4 animate-spin" aria-hidden="true" />}
            {busy ? "Verifying…" : "Verify"}
          </button>
          <div className="flex items-center justify-between text-sm">
            <button
              type="button"
              onClick={() => void sendCode(true)}
              disabled={busy || cooldown > 0}
              className="text-brand-400 hover:text-brand-300 disabled:text-dark-500 disabled:cursor-not-allowed transition-colors"
            >
              {cooldown > 0 ? `Resend in ${cooldown}s` : "Resend code"}
            </button>
            <button
              type="button"
              onClick={() => {
                setError("");
                setInfo("");
                setStep("entry");
              }}
              className="text-dark-400 hover:text-white transition-colors"
            >
              Use a different email
            </button>
          </div>
        </div>
      )}
    </div>
  );
}

// ---------------------------------------------------------------------------
// Phone change flow (OTP): /user/phone/change/start → SMS code →
// /user/phone/change/verify. The SMS code length is configured server-side in
// Twilio Verify, so the code input accepts 4-8 digits (same as the verify gate).
// ---------------------------------------------------------------------------

function PhoneChangeFlow({
  token,
  onDone,
  onCancel,
}: {
  token: string;
  onDone: () => void;
  onCancel: () => void;
}) {
  const [step, setStep] = useState<"entry" | "code">("entry");
  const [countryIso, setCountryIso] = useState("US");
  const [digits, setDigits] = useState("");
  const [code, setCode] = useState("");
  const [sentE164, setSentE164] = useState("");
  const [sentDisplay, setSentDisplay] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [info, setInfo] = useState("");
  const [cooldown, setCooldown] = useState(0);

  const country = COUNTRIES.find((c) => c.iso === countryIso) ?? COUNTRIES[0];

  useEffect(() => {
    if (cooldown <= 0) return;
    const handle = setTimeout(() => setCooldown((s) => s - 1), 1000);
    return () => clearTimeout(handle);
  }, [cooldown]);

  async function sendCode(isResend = false) {
    if (busy || (isResend && cooldown > 0)) return;
    const e164 = isResend ? sentE164 : country.dialCode + digits;
    if (!e164) return;
    setError("");
    setInfo("");
    setBusy(true);
    try {
      await userApi.phoneChange.start(token, e164);
      setCode("");
      setCooldown(RESEND_COOLDOWN_SECONDS);
      if (isResend) {
        setInfo("We sent you a new code.");
      } else {
        setSentE164(e164);
        setSentDisplay(`${country.dialCode} ${digits}`);
        setStep("code");
      }
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  async function verifyCode() {
    const submitted = code.trim();
    if (submitted.length < 4 || busy) return;
    setError("");
    setBusy(true);
    try {
      await userApi.phoneChange.verify(token, sentE164, submitted);
      onDone();
    } catch (err) {
      setError(errorMessage(err));
      setCode("");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="mt-3 rounded-xl border border-dark-700 bg-dark-800/50 p-4 space-y-4">
      {info && (
        <div className="bg-dark-800 border border-dark-700 text-dark-300 rounded-xl px-4 py-3 text-sm">
          {info}
        </div>
      )}
      {error && (
        <div className="bg-red-900/30 border border-red-800 text-red-400 rounded-xl px-4 py-3 text-sm">
          {error}
        </div>
      )}

      {step === "entry" ? (
        <form
          onSubmit={(e) => {
            e.preventDefault();
            void sendCode();
          }}
          className="space-y-3"
        >
          <div>
            <label htmlFor="account-new-phone" className="block text-sm text-dark-300 mb-1.5">
              New phone number
            </label>
            <div className="flex gap-2">
              <select
                aria-label="Country code"
                className="input w-32 shrink-0"
                value={countryIso}
                onChange={(e) => setCountryIso(e.target.value)}
              >
                {COUNTRIES.map((c) => (
                  <option key={c.iso} value={c.iso}>
                    {c.flag} {c.dialCode} {c.name}
                  </option>
                ))}
              </select>
              <input
                id="account-new-phone"
                type="tel"
                inputMode="numeric"
                value={digits}
                onChange={(e) => setDigits(e.target.value.replace(/\D/g, ""))}
                className="input w-full"
                placeholder="5551234567"
                autoComplete="tel-national"
                autoFocus
                required
              />
            </div>
            <p className="text-xs text-dark-500 mt-1.5">
              We&apos;ll text a code to confirm the number. Standard message rates may apply.
            </p>
          </div>
          <div className="flex gap-2">
            <button
              type="submit"
              disabled={busy || digits.length < 7}
              className="btn-primary text-sm py-2 px-4 inline-flex items-center gap-2 disabled:opacity-50 disabled:cursor-not-allowed"
            >
              {busy && <Loader2 className="w-4 h-4 animate-spin" aria-hidden="true" />}
              {busy ? "Sending…" : "Send code"}
            </button>
            <button type="button" onClick={onCancel} className="btn-secondary text-sm py-2 px-4">
              Cancel
            </button>
          </div>
        </form>
      ) : (
        <form
          onSubmit={(e) => {
            e.preventDefault();
            void verifyCode();
          }}
          className="space-y-3"
        >
          <p className="text-sm text-dark-400">
            Enter the code we sent to <span className="text-white">{sentDisplay}</span>.
          </p>
          <input
            type="text"
            inputMode="numeric"
            pattern="[0-9]*"
            autoComplete="one-time-code"
            value={code}
            onChange={(e) => setCode(e.target.value.replace(/\D/g, "").slice(0, 8))}
            aria-label="Verification code"
            className={`input w-full text-center text-xl font-semibold tracking-[0.5em] ${
              error ? "border-red-800 focus:border-red-500 focus:ring-red-500" : ""
            }`}
            placeholder="••••"
            autoFocus
            required
          />
          <button
            type="submit"
            disabled={busy || code.trim().length < 4}
            className="btn-primary w-full text-sm flex items-center justify-center gap-2 disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {busy && <Loader2 className="w-4 h-4 animate-spin" aria-hidden="true" />}
            {busy ? "Verifying…" : "Verify"}
          </button>
          <div className="flex items-center justify-between text-sm">
            <button
              type="button"
              onClick={() => void sendCode(true)}
              disabled={busy || cooldown > 0}
              className="text-brand-400 hover:text-brand-300 disabled:text-dark-500 disabled:cursor-not-allowed transition-colors"
            >
              {cooldown > 0 ? `Resend in ${cooldown}s` : "Resend code"}
            </button>
            <button
              type="button"
              onClick={() => {
                setError("");
                setInfo("");
                setStep("entry");
              }}
              className="text-dark-400 hover:text-white transition-colors"
            >
              Use a different number
            </button>
          </div>
        </form>
      )}
    </div>
  );
}

// ---------------------------------------------------------------------------
// Notification preference toggle (PUT requires all three fields, so the parent
// sends the full object on every flip).
// ---------------------------------------------------------------------------

function PrefToggle({
  label,
  description,
  checked,
  disabled,
  onToggle,
}: {
  label: string;
  description: string;
  checked: boolean;
  disabled: boolean;
  onToggle: () => void;
}) {
  return (
    <div className="flex items-center justify-between gap-4 py-3">
      <div>
        <div className="text-sm font-medium text-white">{label}</div>
        <div className="text-xs text-dark-400 mt-0.5">{description}</div>
      </div>
      <button
        type="button"
        role="switch"
        aria-checked={checked}
        aria-label={label}
        disabled={disabled}
        onClick={onToggle}
        className={`relative w-11 h-6 rounded-full transition-colors shrink-0 disabled:opacity-50 disabled:cursor-not-allowed ${
          checked ? "bg-brand-500" : "bg-dark-700"
        }`}
      >
        <span
          className={`absolute top-0.5 left-0.5 w-5 h-5 rounded-full bg-white transition-transform ${
            checked ? "translate-x-5" : ""
          }`}
          aria-hidden="true"
        />
      </button>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Page
// ---------------------------------------------------------------------------

export default function AccountPage() {
  const router = useRouter();

  const [token, setToken] = useState<string | null>(null);
  const [profile, setProfile] = useState<User | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);

  // Name edit
  const [editingName, setEditingName] = useState(false);
  const [firstName, setFirstName] = useState("");
  const [lastName, setLastName] = useState("");
  const [savingName, setSavingName] = useState(false);
  const [nameError, setNameError] = useState("");
  const [nameSaved, setNameSaved] = useState(false);

  // Contact change flows (mutually exclusive)
  const [contactFlow, setContactFlow] = useState<"none" | "email" | "phone">("none");

  // Notification preferences (section-scoped state — a prefs failure must not
  // take down the whole hub)
  const [prefs, setPrefs] = useState<NotificationPreferences | null>(null);
  const [prefsError, setPrefsError] = useState<string | null>(null);
  const [prefsSaving, setPrefsSaving] = useState(false);

  // Linked providers (section-scoped state)
  const [providers, setProviders] = useState<LinkedProvider[] | null>(null);
  const [providersError, setProvidersError] = useState<string | null>(null);
  const [pendingUnlink, setPendingUnlink] = useState<string | null>(null);
  const [unlinking, setUnlinking] = useState(false);
  const [unlinkError, setUnlinkError] = useState("");

  // Delete account
  const [deleteOpen, setDeleteOpen] = useState(false);
  const [deleteText, setDeleteText] = useState("");
  const [deleting, setDeleting] = useState(false);
  const [deleteError, setDeleteError] = useState("");

  const loadProfile = useCallback(
    async (t: string) => {
      setLoading(true);
      setLoadError(null);
      try {
        const p = await userApi.getProfile(t);
        setProfile(p);
        // Keep the cached header user in sync (name/email/phone may change here).
        window.localStorage.setItem("user", JSON.stringify(p));
      } catch (err) {
        if (isUnauthorized(err)) {
          window.localStorage.removeItem("token");
          router.replace("/auth");
          return;
        }
        setLoadError(errorMessage(err));
      } finally {
        setLoading(false);
      }
    },
    [router]
  );

  const loadPrefs = useCallback(async (t: string) => {
    setPrefsError(null);
    try {
      setPrefs(await notificationPreferencesApi.get(t));
    } catch (err) {
      setPrefsError(errorMessage(err));
    }
  }, []);

  const loadProviders = useCallback(async (t: string) => {
    setProvidersError(null);
    try {
      setProviders(await linkedProvidersApi.list(t));
    } catch (err) {
      setProvidersError(errorMessage(err));
    }
  }, []);

  useEffect(() => {
    const t = typeof window !== "undefined" ? window.localStorage.getItem("token") : null;
    if (!t) {
      router.replace("/auth");
      return;
    }
    setToken(t);
    void loadProfile(t);
    void loadPrefs(t);
    void loadProviders(t);
  }, [router, loadProfile, loadPrefs, loadProviders]);

  // Silent profile refresh after a mutation (no skeleton flash).
  const refreshProfile = useCallback(async () => {
    if (!token) return;
    try {
      const p = await userApi.getProfile(token);
      setProfile(p);
      window.localStorage.setItem("user", JSON.stringify(p));
    } catch {
      // Non-fatal — the mutation already succeeded server-side.
    }
  }, [token]);

  function startEditName() {
    if (!profile) return;
    setFirstName(profile.first_name);
    setLastName(profile.last_name);
    setNameError("");
    setEditingName(true);
  }

  async function saveName(e: React.FormEvent) {
    e.preventDefault();
    if (!token || !profile || savingName) return;
    const first = firstName.trim();
    const last = lastName.trim();
    if (!first || !last) {
      setNameError("First and last name are required.");
      return;
    }
    setSavingName(true);
    setNameError("");
    try {
      // phone rides along for API-shape parity but the backend ignores it —
      // phone changes only happen through the OTP flow below.
      await userApi.updateProfile(token, {
        first_name: first,
        last_name: last,
        phone: profile.phone,
      });
      await refreshProfile();
      setEditingName(false);
      setNameSaved(true);
      setTimeout(() => setNameSaved(false), 2500);
    } catch (err) {
      setNameError(errorMessage(err));
    } finally {
      setSavingName(false);
    }
  }

  async function togglePref(key: keyof NotificationPreferences) {
    if (!token || !prefs || prefsSaving) return;
    const previous = prefs;
    const next = { ...prefs, [key]: !prefs[key] };
    // Optimistic flip with revert-on-failure. PUT sends all three fields —
    // partial updates are rejected by the backend by design.
    setPrefs(next);
    setPrefsSaving(true);
    setPrefsError(null);
    try {
      const saved = await notificationPreferencesApi.update(token, next);
      setPrefs(saved);
    } catch (err) {
      setPrefs(previous);
      setPrefsError(errorMessage(err));
    } finally {
      setPrefsSaving(false);
    }
  }

  async function unlinkProvider(provider: string) {
    if (!token || unlinking) return;
    setUnlinking(true);
    setUnlinkError("");
    try {
      await linkedProvidersApi.unlink(token, provider);
      setPendingUnlink(null);
      await loadProviders(token);
    } catch (err) {
      setUnlinkError(errorMessage(err));
    } finally {
      setUnlinking(false);
    }
  }

  function signOut() {
    window.localStorage.removeItem("token");
    window.localStorage.removeItem("refresh_token");
    window.localStorage.removeItem("user");
    router.replace("/");
  }

  async function deleteAccount(e: React.FormEvent) {
    e.preventDefault();
    if (!token || deleting || deleteText !== DELETE_CONFIRM_WORD) return;
    setDeleting(true);
    setDeleteError("");
    try {
      await userApi.deleteAccount(token);
      // The account is gone — drop the whole local session.
      window.localStorage.removeItem("token");
      window.localStorage.removeItem("refresh_token");
      window.localStorage.removeItem("user");
      router.replace("/");
    } catch (err) {
      setDeleteError(errorMessage(err));
      setDeleting(false);
    }
  }

  const emailVerified = profile?.email_verified === true;
  const phoneVerified = profile?.phone_verified === true;
  const displayEmail =
    profile && profile.email && !isPlaceholderEmail(profile.email) ? profile.email : "";

  const verifiedBadge = (
    <span className="inline-flex items-center gap-1 text-xs text-brand-400">
      <BadgeCheck className="w-3.5 h-3.5" aria-hidden="true" />
      Verified
    </span>
  );

  const unverifiedBadge = (
    <Link
      href="/account/verify"
      className="inline-flex items-center gap-1 text-xs text-yellow-500 hover:text-yellow-400 transition-colors"
    >
      <ShieldCheck className="w-3.5 h-3.5" aria-hidden="true" />
      Verify now
    </Link>
  );

  return (
    <>
      <Header />
      <main className="flex-1 w-full max-w-2xl mx-auto px-4 py-8">
        <h1 className="text-3xl font-extrabold mb-6">Account</h1>

        {loading ? (
          <div className="space-y-4" aria-hidden="true">
            {Array.from({ length: 3 }).map((_, i) => (
              <div key={i} className="card p-6 animate-pulse space-y-4">
                <div className="h-5 bg-dark-800 rounded w-1/3" />
                <div className="h-4 bg-dark-800 rounded w-2/3" />
                <div className="h-10 bg-dark-800 rounded-xl" />
              </div>
            ))}
          </div>
        ) : loadError ? (
          <div className="card p-12 text-center">
            <h2 className="text-xl font-bold mb-2">Couldn&apos;t load your account</h2>
            <p className="text-dark-400 mb-6">{loadError}</p>
            <button
              onClick={() => token && loadProfile(token)}
              className="btn-primary inline-block"
            >
              Retry
            </button>
          </div>
        ) : profile ? (
          <div className="space-y-6">
            {/* ---- Profile ---- */}
            <section className="card p-6">
              <div className="flex items-center justify-between mb-4">
                <h2 className="text-lg font-bold">Profile</h2>
                {nameSaved && (
                  <span className="text-xs text-brand-400" role="status">
                    Saved
                  </span>
                )}
              </div>

              {editingName ? (
                <form onSubmit={saveName} className="space-y-3 mb-6">
                  {nameError && (
                    <div className="bg-red-900/30 border border-red-800 text-red-400 rounded-xl px-4 py-3 text-sm">
                      {nameError}
                    </div>
                  )}
                  <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                    <div>
                      <label
                        htmlFor="account-first-name"
                        className="block text-sm text-dark-300 mb-1.5"
                      >
                        First name
                      </label>
                      <input
                        id="account-first-name"
                        type="text"
                        value={firstName}
                        onChange={(e) => setFirstName(e.target.value)}
                        className="input w-full"
                        autoComplete="given-name"
                        autoFocus
                        required
                      />
                    </div>
                    <div>
                      <label
                        htmlFor="account-last-name"
                        className="block text-sm text-dark-300 mb-1.5"
                      >
                        Last name
                      </label>
                      <input
                        id="account-last-name"
                        type="text"
                        value={lastName}
                        onChange={(e) => setLastName(e.target.value)}
                        className="input w-full"
                        autoComplete="family-name"
                        required
                      />
                    </div>
                  </div>
                  <div className="flex gap-2">
                    <button
                      type="submit"
                      disabled={savingName || !firstName.trim() || !lastName.trim()}
                      className="btn-primary text-sm py-2 px-4 inline-flex items-center gap-2 disabled:opacity-50 disabled:cursor-not-allowed"
                    >
                      {savingName && (
                        <Loader2 className="w-4 h-4 animate-spin" aria-hidden="true" />
                      )}
                      {savingName ? "Saving…" : "Save"}
                    </button>
                    <button
                      type="button"
                      onClick={() => setEditingName(false)}
                      className="btn-secondary text-sm py-2 px-4"
                    >
                      Cancel
                    </button>
                  </div>
                </form>
              ) : (
                <div className="flex items-center justify-between gap-4 mb-6">
                  <div>
                    <div className="text-sm text-dark-400">Name</div>
                    <div className="text-white font-medium">
                      {profile.first_name} {profile.last_name}
                    </div>
                  </div>
                  <button
                    type="button"
                    onClick={startEditName}
                    className="text-brand-400 hover:text-brand-300 text-sm font-medium transition-colors"
                  >
                    Edit
                  </button>
                </div>
              )}

              {/* Email row */}
              <div className="border-t border-dark-800 pt-4 mb-4">
                <div className="flex items-center justify-between gap-4">
                  <div className="min-w-0">
                    <div className="flex items-center gap-2 text-sm text-dark-400">
                      <Mail className="w-4 h-4" aria-hidden="true" />
                      Email
                      {displayEmail && (emailVerified ? verifiedBadge : unverifiedBadge)}
                    </div>
                    <div className="text-white font-medium truncate">
                      {displayEmail || <span className="text-dark-500">No email yet</span>}
                    </div>
                  </div>
                  <button
                    type="button"
                    onClick={() =>
                      setContactFlow(contactFlow === "email" ? "none" : "email")
                    }
                    className="text-brand-400 hover:text-brand-300 text-sm font-medium transition-colors shrink-0"
                  >
                    {contactFlow === "email" ? "Close" : displayEmail ? "Change" : "Add"}
                  </button>
                </div>
                {contactFlow === "email" && token && (
                  <EmailChangeFlow
                    token={token}
                    onDone={() => {
                      setContactFlow("none");
                      void refreshProfile();
                    }}
                    onCancel={() => setContactFlow("none")}
                  />
                )}
              </div>

              {/* Phone row */}
              <div className="border-t border-dark-800 pt-4">
                <div className="flex items-center justify-between gap-4">
                  <div className="min-w-0">
                    <div className="flex items-center gap-2 text-sm text-dark-400">
                      <Smartphone className="w-4 h-4" aria-hidden="true" />
                      Phone
                      {profile.phone && (phoneVerified ? verifiedBadge : unverifiedBadge)}
                    </div>
                    <div className="text-white font-medium truncate">
                      {profile.phone || <span className="text-dark-500">No phone yet</span>}
                    </div>
                  </div>
                  <button
                    type="button"
                    onClick={() =>
                      setContactFlow(contactFlow === "phone" ? "none" : "phone")
                    }
                    className="text-brand-400 hover:text-brand-300 text-sm font-medium transition-colors shrink-0"
                  >
                    {contactFlow === "phone" ? "Close" : profile.phone ? "Change" : "Add"}
                  </button>
                </div>
                {contactFlow === "phone" && token && (
                  <PhoneChangeFlow
                    token={token}
                    onDone={() => {
                      setContactFlow("none");
                      void refreshProfile();
                    }}
                    onCancel={() => setContactFlow("none")}
                  />
                )}
              </div>
            </section>

            {/* ---- Addresses ---- */}
            <Link
              href="/account/addresses"
              className="card p-6 flex items-center justify-between gap-4 hover:border-dark-700 transition-colors"
            >
              <div className="flex items-center gap-3">
                <MapPin className="w-5 h-5 text-brand-400" aria-hidden="true" />
                <div>
                  <div className="font-bold">Delivery addresses</div>
                  <div className="text-sm text-dark-400">
                    Add, remove, or set your default address
                  </div>
                </div>
              </div>
              <ChevronRight className="w-5 h-5 text-dark-400 shrink-0" aria-hidden="true" />
            </Link>

            {/* ---- Payment methods ---- */}
            <Link
              href="/account/payments"
              className="card p-6 flex items-center justify-between gap-4 hover:border-dark-700 transition-colors"
            >
              <div className="flex items-center gap-3">
                <CreditCard className="w-5 h-5 text-brand-400" aria-hidden="true" />
                <div>
                  <div className="font-bold">Payment methods</div>
                  <div className="text-sm text-dark-400">
                    Save a card to check out faster
                  </div>
                </div>
              </div>
              <ChevronRight className="w-5 h-5 text-dark-400 shrink-0" aria-hidden="true" />
            </Link>

            {/* ---- Notifications ---- */}
            <section className="card p-6">
              <h2 className="text-lg font-bold mb-2">Notifications</h2>
              {prefsError && (
                <div className="bg-red-900/30 border border-red-800 text-red-400 rounded-xl px-4 py-3 mb-3 text-sm flex items-center justify-between gap-3">
                  <span>{prefsError}</span>
                  <button
                    type="button"
                    onClick={() => token && loadPrefs(token)}
                    className="text-red-300 hover:text-white font-medium transition-colors shrink-0"
                  >
                    Retry
                  </button>
                </div>
              )}
              {prefs ? (
                <div className="divide-y divide-dark-800">
                  <PrefToggle
                    label="Order updates"
                    description="Status changes on your active orders"
                    checked={prefs.order_updates}
                    disabled={prefsSaving}
                    onToggle={() => void togglePref("order_updates")}
                  />
                  <PrefToggle
                    label="Chat messages"
                    description="Messages from the restaurant or your courier"
                    checked={prefs.chat_messages}
                    disabled={prefsSaving}
                    onToggle={() => void togglePref("chat_messages")}
                  />
                  <PrefToggle
                    label="Promotions"
                    description="Deals and offers from restaurants you'll love"
                    checked={prefs.promotions}
                    disabled={prefsSaving}
                    onToggle={() => void togglePref("promotions")}
                  />
                </div>
              ) : !prefsError ? (
                <div className="space-y-3 animate-pulse" aria-hidden="true">
                  {Array.from({ length: 3 }).map((_, i) => (
                    <div key={i} className="h-10 bg-dark-800 rounded-xl" />
                  ))}
                </div>
              ) : null}
            </section>

            {/* ---- Linked sign-in methods ---- */}
            <section className="card p-6">
              <h2 className="text-lg font-bold mb-2">Sign-in methods</h2>
              {providersError && (
                <div className="bg-red-900/30 border border-red-800 text-red-400 rounded-xl px-4 py-3 mb-3 text-sm flex items-center justify-between gap-3">
                  <span>{providersError}</span>
                  <button
                    type="button"
                    onClick={() => token && loadProviders(token)}
                    className="text-red-300 hover:text-white font-medium transition-colors shrink-0"
                  >
                    Retry
                  </button>
                </div>
              )}
              {unlinkError && (
                <div className="bg-red-900/30 border border-red-800 text-red-400 rounded-xl px-4 py-3 mb-3 text-sm">
                  {unlinkError}
                </div>
              )}
              {providers ? (
                providers.length === 0 ? (
                  <p className="text-sm text-dark-400">No linked sign-in methods.</p>
                ) : (
                  <ul className="divide-y divide-dark-800">
                    {providers.map((p) => (
                      <li
                        key={p.provider}
                        className="flex items-center justify-between gap-4 py-3"
                      >
                        <div className="flex items-center gap-3 min-w-0">
                          <span className="text-dark-400">{providerIcon(p.provider)}</span>
                          <div>
                            <div className="text-sm font-medium text-white">
                              {providerLabel(p.provider)}
                            </div>
                            <div className="text-xs text-dark-500">
                              Linked{" "}
                              {new Date(p.created_at).toLocaleDateString(undefined, {
                                month: "short",
                                year: "numeric",
                              })}
                            </div>
                          </div>
                        </div>
                        {providers.length > 1 &&
                          (pendingUnlink === p.provider ? (
                            <div className="flex items-center gap-2 shrink-0">
                              <button
                                type="button"
                                onClick={() => void unlinkProvider(p.provider)}
                                disabled={unlinking}
                                className="text-red-400 hover:text-red-300 text-sm font-medium transition-colors disabled:opacity-50"
                              >
                                {unlinking ? "Removing…" : "Confirm"}
                              </button>
                              <button
                                type="button"
                                onClick={() => {
                                  setPendingUnlink(null);
                                  setUnlinkError("");
                                }}
                                disabled={unlinking}
                                className="text-dark-400 hover:text-white text-sm transition-colors disabled:opacity-50"
                              >
                                Cancel
                              </button>
                            </div>
                          ) : (
                            <button
                              type="button"
                              onClick={() => {
                                setPendingUnlink(p.provider);
                                setUnlinkError("");
                              }}
                              className="text-dark-400 hover:text-red-400 text-sm font-medium transition-colors shrink-0"
                            >
                              Unlink
                            </button>
                          ))}
                      </li>
                    ))}
                  </ul>
                )
              ) : !providersError ? (
                <div className="space-y-3 animate-pulse" aria-hidden="true">
                  {Array.from({ length: 2 }).map((_, i) => (
                    <div key={i} className="h-10 bg-dark-800 rounded-xl" />
                  ))}
                </div>
              ) : null}
              {providers && providers.length === 1 && (
                <p className="text-xs text-dark-500 mt-2">
                  You can&apos;t remove your only sign-in method.
                </p>
              )}
            </section>

            {/* ---- Sign out ---- */}
            <section className="card p-6">
              <button
                type="button"
                onClick={signOut}
                className="btn-secondary w-full flex items-center justify-center gap-2 text-sm"
              >
                <LogOut className="w-4 h-4" aria-hidden="true" />
                Sign out
              </button>
            </section>

            {/* ---- Danger zone ---- */}
            <section className="card p-6 border-red-900/50">
              <div className="flex items-center gap-2 mb-2">
                <AlertTriangle className="w-4 h-4 text-red-400" aria-hidden="true" />
                <h2 className="text-lg font-bold text-red-400">Delete account</h2>
              </div>
              <p className="text-sm text-dark-400 mb-4">
                This will permanently delete your account and all associated data. This action
                cannot be undone.
              </p>
              {deleteOpen ? (
                <form onSubmit={deleteAccount} className="space-y-3">
                  {deleteError && (
                    <div className="bg-red-900/30 border border-red-800 text-red-400 rounded-xl px-4 py-3 text-sm">
                      {deleteError}
                    </div>
                  )}
                  <div>
                    <label
                      htmlFor="account-delete-confirm"
                      className="block text-sm text-dark-300 mb-1.5"
                    >
                      Type <span className="font-mono font-semibold">{DELETE_CONFIRM_WORD}</span>{" "}
                      to confirm
                    </label>
                    <input
                      id="account-delete-confirm"
                      type="text"
                      value={deleteText}
                      onChange={(e) => setDeleteText(e.target.value)}
                      className="input w-full"
                      placeholder={DELETE_CONFIRM_WORD}
                      autoComplete="off"
                      autoFocus
                    />
                  </div>
                  <div className="flex gap-2">
                    <button
                      type="submit"
                      disabled={deleting || deleteText !== DELETE_CONFIRM_WORD}
                      className="bg-red-600 hover:bg-red-700 text-white font-semibold py-2 px-4 rounded-xl transition-colors text-sm inline-flex items-center gap-2 disabled:opacity-50 disabled:cursor-not-allowed"
                    >
                      {deleting && <Loader2 className="w-4 h-4 animate-spin" aria-hidden="true" />}
                      {deleting ? "Deleting…" : "Permanently delete my account"}
                    </button>
                    <button
                      type="button"
                      onClick={() => {
                        setDeleteOpen(false);
                        setDeleteText("");
                        setDeleteError("");
                      }}
                      disabled={deleting}
                      className="btn-secondary text-sm py-2 px-4 disabled:opacity-50"
                    >
                      Cancel
                    </button>
                  </div>
                </form>
              ) : (
                <button
                  type="button"
                  onClick={() => setDeleteOpen(true)}
                  className="text-red-400 hover:text-red-300 text-sm font-medium transition-colors"
                >
                  Delete my account…
                </button>
              )}
            </section>
          </div>
        ) : null}
      </main>
    </>
  );
}
