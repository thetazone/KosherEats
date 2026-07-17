"use client";

import { OtpInput } from "@/components/auth/OtpInput";
import { user as userApi } from "@/lib/api";
import { CheckCircle2, Loader2, Mail, Smartphone } from "lucide-react";
import { useCallback, useEffect, useState } from "react";

// Mandatory consumer verification: the backend hard-gates payments.intent and
// orders.create with 403 "verification_required" until BOTH email_verified and
// phone_verified are true (backend/internal/handlers/email_otp.go
// RequireVerifiedMiddleware). This component walks whichever legs are still
// missing, in order email → phone — the web twin of the iOS
// AccountVerificationView.
//
// Email leg:  /user/email/start → 6-digit emailed code → /user/email/verify
//             (writes email + email_verified=true; 409 if another account
//             owns the address).
// Phone leg:  /user/phone/change/start → SMS code → /user/phone/change/verify
//             (E.164 built from the country-code select + national digits).
//             Twilio Verify configures the SMS code length server-side, so the
//             code input accepts 4-8 digits instead of hard-coding six.

type Step = "email-entry" | "email-code" | "phone-entry" | "phone-code";

const RESEND_COOLDOWN_SECONDS = 30;
const EMAIL_CODE_LENGTH = 6;

// Addresses the backend refuses to treat as a reachable inbox (see
// isUnverifiableEmail backend-side) — never prefill them.
function isPlaceholderEmail(email: string): boolean {
  return (
    email.endsWith("@phone.koshereats.local") ||
    email.endsWith("@privaterelay.appleid.com")
  );
}

export interface Country {
  iso: string; // ISO-3166-1 alpha-2 — unique key (several countries share +1)
  name: string;
  flag: string;
  dialCode: string; // leading "+" included
}

// Curated subset of the iOS picker list (ios/consumer/.../Models/Country.swift):
// major markets plus the Jewish-diaspora hubs the backend is likely to see.
// Exported for reuse by the /account phone-change flow.
export const COUNTRIES: Country[] = [
  { iso: "US", name: "United States", flag: "🇺🇸", dialCode: "+1" },
  { iso: "CA", name: "Canada", flag: "🇨🇦", dialCode: "+1" },
  { iso: "IL", name: "Israel", flag: "🇮🇱", dialCode: "+972" },
  { iso: "GB", name: "United Kingdom", flag: "🇬🇧", dialCode: "+44" },
  { iso: "AR", name: "Argentina", flag: "🇦🇷", dialCode: "+54" },
  { iso: "AU", name: "Australia", flag: "🇦🇺", dialCode: "+61" },
  { iso: "AT", name: "Austria", flag: "🇦🇹", dialCode: "+43" },
  { iso: "BE", name: "Belgium", flag: "🇧🇪", dialCode: "+32" },
  { iso: "BR", name: "Brazil", flag: "🇧🇷", dialCode: "+55" },
  { iso: "CL", name: "Chile", flag: "🇨🇱", dialCode: "+56" },
  { iso: "CO", name: "Colombia", flag: "🇨🇴", dialCode: "+57" },
  { iso: "CR", name: "Costa Rica", flag: "🇨🇷", dialCode: "+506" },
  { iso: "DK", name: "Denmark", flag: "🇩🇰", dialCode: "+45" },
  { iso: "FR", name: "France", flag: "🇫🇷", dialCode: "+33" },
  { iso: "DE", name: "Germany", flag: "🇩🇪", dialCode: "+49" },
  { iso: "GR", name: "Greece", flag: "🇬🇷", dialCode: "+30" },
  { iso: "HU", name: "Hungary", flag: "🇭🇺", dialCode: "+36" },
  { iso: "IE", name: "Ireland", flag: "🇮🇪", dialCode: "+353" },
  { iso: "IT", name: "Italy", flag: "🇮🇹", dialCode: "+39" },
  { iso: "MX", name: "Mexico", flag: "🇲🇽", dialCode: "+52" },
  { iso: "MA", name: "Morocco", flag: "🇲🇦", dialCode: "+212" },
  { iso: "NL", name: "Netherlands", flag: "🇳🇱", dialCode: "+31" },
  { iso: "NZ", name: "New Zealand", flag: "🇳🇿", dialCode: "+64" },
  { iso: "PA", name: "Panama", flag: "🇵🇦", dialCode: "+507" },
  { iso: "PL", name: "Poland", flag: "🇵🇱", dialCode: "+48" },
  { iso: "PT", name: "Portugal", flag: "🇵🇹", dialCode: "+351" },
  { iso: "RU", name: "Russia", flag: "🇷🇺", dialCode: "+7" },
  { iso: "ZA", name: "South Africa", flag: "🇿🇦", dialCode: "+27" },
  { iso: "ES", name: "Spain", flag: "🇪🇸", dialCode: "+34" },
  { iso: "SE", name: "Sweden", flag: "🇸🇪", dialCode: "+46" },
  { iso: "CH", name: "Switzerland", flag: "🇨🇭", dialCode: "+41" },
  { iso: "UA", name: "Ukraine", flag: "🇺🇦", dialCode: "+380" },
  { iso: "UY", name: "Uruguay", flag: "🇺🇾", dialCode: "+598" },
];

interface VerificationGateProps {
  token: string;
  emailVerified: boolean;
  phoneVerified: boolean;
  /** Account email for prefill; placeholder/relay addresses are skipped. */
  initialEmail?: string;
  /** Fires once BOTH legs are verified. */
  onComplete: () => void;
}

function errorMessage(err: unknown): string {
  return err instanceof Error ? err.message : "Something went wrong. Please try again.";
}

export function VerificationGate({
  token,
  emailVerified,
  phoneVerified,
  initialEmail,
  onComplete,
}: VerificationGateProps) {
  const [emailDone, setEmailDone] = useState(emailVerified);
  const [phoneDone, setPhoneDone] = useState(phoneVerified);
  const [step, setStep] = useState<Step>(
    emailVerified ? "phone-entry" : "email-entry"
  );

  const [email, setEmail] = useState(
    initialEmail && !isPlaceholderEmail(initialEmail) ? initialEmail : ""
  );
  const [emailCode, setEmailCode] = useState("");

  const [countryIso, setCountryIso] = useState("US");
  const [phoneDigits, setPhoneDigits] = useState("");
  const [phoneCode, setPhoneCode] = useState("");
  // Frozen at send time so "enter the code we sent to X" stays accurate even
  // if the user edits the inputs afterwards.
  const [sentPhoneE164, setSentPhoneE164] = useState("");
  const [sentPhoneDisplay, setSentPhoneDisplay] = useState("");

  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [info, setInfo] = useState("");
  const [resendCooldown, setResendCooldown] = useState(0);

  const country = COUNTRIES.find((c) => c.iso === countryIso) ?? COUNTRIES[0];
  const normalizedEmail = email.trim().toLowerCase();
  const isEmailShaped =
    normalizedEmail.includes("@") && normalizedEmail.includes(".") && normalizedEmail.length >= 5;

  // Tick the resend cooldown down once per second (same as /auth).
  useEffect(() => {
    if (resendCooldown <= 0) return;
    const handle = setTimeout(() => setResendCooldown((s) => s - 1), 1000);
    return () => clearTimeout(handle);
  }, [resendCooldown]);

  const goToStep = (next: Step) => {
    setError("");
    setInfo("");
    setStep(next);
  };

  // ----- Email leg -----

  async function sendEmailCode(isResend = false) {
    if (loading || (isResend && resendCooldown > 0)) return;
    setError("");
    setInfo("");
    setLoading(true);
    try {
      await userApi.emailChange.start(token, normalizedEmail);
      setEmailCode("");
      setResendCooldown(RESEND_COOLDOWN_SECONDS);
      if (isResend) {
        setInfo("We sent you a new code.");
      } else {
        goToStep("email-code");
      }
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setLoading(false);
    }
  }

  const verifyEmailCode = useCallback(
    async (submitted: string) => {
      if (submitted.length !== EMAIL_CODE_LENGTH || loading) return;
      setError("");
      setLoading(true);
      try {
        await userApi.emailChange.verify(token, normalizedEmail, submitted);
        setEmailDone(true);
        if (phoneDone) {
          onComplete();
        } else {
          setError("");
          setInfo("");
          setStep("phone-entry");
        }
      } catch (err) {
        setError(errorMessage(err));
        setEmailCode("");
      } finally {
        setLoading(false);
      }
    },
    [token, normalizedEmail, loading, phoneDone, onComplete]
  );

  // ----- Phone leg -----

  async function sendPhoneCode(isResend = false) {
    if (loading || (isResend && resendCooldown > 0)) return;
    const e164 = isResend ? sentPhoneE164 : country.dialCode + phoneDigits;
    if (!e164) return;
    setError("");
    setInfo("");
    setLoading(true);
    try {
      await userApi.phoneChange.start(token, e164);
      setPhoneCode("");
      setResendCooldown(RESEND_COOLDOWN_SECONDS);
      if (isResend) {
        setInfo("We sent you a new code.");
      } else {
        setSentPhoneE164(e164);
        setSentPhoneDisplay(`${country.dialCode} ${phoneDigits}`);
        goToStep("phone-code");
      }
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setLoading(false);
    }
  }

  async function verifyPhoneCode() {
    const submitted = phoneCode.trim();
    if (submitted.length < 4 || loading) return;
    setError("");
    setLoading(true);
    try {
      await userApi.phoneChange.verify(token, sentPhoneE164, submitted);
      setPhoneDone(true);
      onComplete();
    } catch (err) {
      setError(errorMessage(err));
      setPhoneCode("");
    } finally {
      setLoading(false);
    }
  }

  // ----- Copy -----

  const heading =
    step === "email-entry" || step === "email-code" ? "Verify your email" : "Verify your phone";
  const subheading =
    step === "email-entry"
      ? "We'll send a 6-digit code to confirm it's really you."
      : step === "email-code"
      ? `Enter the 6-digit code we sent to ${normalizedEmail}.`
      : step === "phone-entry"
      ? "We'll text you a code to confirm your number."
      : `Enter the code we sent to ${sentPhoneDisplay}.`;

  const onEmailLeg = step === "email-entry" || step === "email-code";
  const needsBoth = !emailVerified && !phoneVerified;

  return (
    <div className="card p-6 md:p-8">
      {/* Progress: only shown when both legs are outstanding */}
      {needsBoth && (
        <div className="flex items-center gap-3 mb-6 text-sm">
          <span
            className={`flex items-center gap-1.5 ${
              emailDone ? "text-brand-400" : onEmailLeg ? "text-white font-medium" : "text-dark-400"
            }`}
          >
            {emailDone ? <CheckCircle2 className="w-4 h-4" /> : <Mail className="w-4 h-4" />}
            Email
          </span>
          <span className="flex-1 h-px bg-dark-700" aria-hidden="true" />
          <span
            className={`flex items-center gap-1.5 ${
              phoneDone ? "text-brand-400" : !onEmailLeg ? "text-white font-medium" : "text-dark-400"
            }`}
          >
            {phoneDone ? <CheckCircle2 className="w-4 h-4" /> : <Smartphone className="w-4 h-4" />}
            Phone
          </span>
        </div>
      )}

      <h2 className="text-xl font-bold mb-1">{heading}</h2>
      <p className="text-dark-400 text-sm mb-6">{subheading}</p>

      {info && (
        <div className="bg-dark-800 border border-dark-700 text-dark-300 rounded-xl px-4 py-3 mb-4 text-sm">
          {info}
        </div>
      )}
      {error && (
        <div className="bg-red-900/30 border border-red-800 text-red-400 rounded-xl px-4 py-3 mb-4 text-sm">
          {error}
        </div>
      )}

      {step === "email-entry" && (
        <form
          onSubmit={(e) => {
            e.preventDefault();
            void sendEmailCode();
          }}
          className="space-y-4"
        >
          <div>
            <label htmlFor="verify-email" className="block text-sm text-dark-300 mb-1.5">
              Email
            </label>
            <input
              id="verify-email"
              type="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              className="input w-full"
              placeholder="you@example.com"
              autoComplete="email"
              autoFocus
              required
            />
          </div>
          <button
            type="submit"
            disabled={loading || !isEmailShaped}
            className="btn-primary w-full flex items-center justify-center gap-2 disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {loading && <Loader2 className="w-4 h-4 animate-spin" aria-hidden="true" />}
            {loading ? "Sending…" : "Send code"}
          </button>
        </form>
      )}

      {step === "email-code" && (
        <div className="space-y-4">
          <OtpInput
            value={emailCode}
            onChange={setEmailCode}
            onComplete={verifyEmailCode}
            length={EMAIL_CODE_LENGTH}
            disabled={loading}
            error={!!error}
          />
          <button
            type="button"
            onClick={() => void verifyEmailCode(emailCode)}
            disabled={loading || emailCode.length !== EMAIL_CODE_LENGTH}
            className="btn-primary w-full flex items-center justify-center gap-2 disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {loading && <Loader2 className="w-4 h-4 animate-spin" aria-hidden="true" />}
            {loading ? "Verifying…" : "Verify"}
          </button>
          <div className="flex items-center justify-between text-sm">
            <button
              type="button"
              onClick={() => void sendEmailCode(true)}
              disabled={loading || resendCooldown > 0}
              className="inline-flex items-center min-h-[44px] text-brand-400 hover:text-brand-300 disabled:text-dark-500 disabled:cursor-not-allowed transition-colors"
            >
              {resendCooldown > 0 ? `Resend in ${resendCooldown}s` : "Resend code"}
            </button>
            <button
              type="button"
              onClick={() => goToStep("email-entry")}
              className="inline-flex items-center min-h-[44px] text-dark-400 hover:text-white transition-colors"
            >
              Use a different email
            </button>
          </div>
        </div>
      )}

      {step === "phone-entry" && (
        <form
          onSubmit={(e) => {
            e.preventDefault();
            void sendPhoneCode();
          }}
          className="space-y-4"
        >
          <div>
            <label htmlFor="verify-phone" className="block text-sm text-dark-300 mb-1.5">
              Phone number
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
                id="verify-phone"
                type="tel"
                inputMode="tel"
                value={phoneDigits}
                onChange={(e) => setPhoneDigits(e.target.value.replace(/\D/g, ""))}
                className="input w-full"
                placeholder="5551234567"
                autoComplete="tel-national"
                autoFocus
                required
              />
            </div>
            <p className="text-xs text-dark-500 mt-1.5">
              Standard message rates may apply.
            </p>
          </div>
          <button
            type="submit"
            disabled={loading || phoneDigits.length < 7}
            className="btn-primary w-full flex items-center justify-center gap-2 disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {loading && <Loader2 className="w-4 h-4 animate-spin" aria-hidden="true" />}
            {loading ? "Sending…" : "Send code"}
          </button>
        </form>
      )}

      {step === "phone-code" && (
        <form
          onSubmit={(e) => {
            e.preventDefault();
            void verifyPhoneCode();
          }}
          className="space-y-4"
        >
          <div>
            <label htmlFor="verify-phone-code" className="block text-sm text-dark-300 mb-1.5">
              Verification code
            </label>
            {/* Single free-length field (not the 6-box OtpInput): the SMS code
                length is configured in Twilio Verify server-side and must not
                be hard-coded here. */}
            <input
              id="verify-phone-code"
              type="text"
              inputMode="numeric"
              pattern="[0-9]*"
              autoComplete="one-time-code"
              value={phoneCode}
              onChange={(e) => setPhoneCode(e.target.value.replace(/\D/g, "").slice(0, 8))}
              className={`input w-full text-center text-xl font-semibold tracking-[0.5em] ${
                error ? "border-red-800 focus:border-red-500 focus:ring-red-500" : ""
              }`}
              placeholder="••••"
              autoFocus
              required
            />
          </div>
          <button
            type="submit"
            disabled={loading || phoneCode.trim().length < 4}
            className="btn-primary w-full flex items-center justify-center gap-2 disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {loading && <Loader2 className="w-4 h-4 animate-spin" aria-hidden="true" />}
            {loading ? "Verifying…" : "Verify"}
          </button>
          <div className="flex items-center justify-between text-sm">
            <button
              type="button"
              onClick={() => void sendPhoneCode(true)}
              disabled={loading || resendCooldown > 0}
              className="inline-flex items-center min-h-[44px] text-brand-400 hover:text-brand-300 disabled:text-dark-500 disabled:cursor-not-allowed transition-colors"
            >
              {resendCooldown > 0 ? `Resend in ${resendCooldown}s` : "Resend code"}
            </button>
            <button
              type="button"
              onClick={() => goToStep("phone-entry")}
              className="inline-flex items-center min-h-[44px] text-dark-400 hover:text-white transition-colors"
            >
              Use a different number
            </button>
          </div>
        </form>
      )}
    </div>
  );
}
