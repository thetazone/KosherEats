"use client";

import { OtpInput } from "@/components/auth/OtpInput";
import { VerificationGate } from "@/components/auth/VerificationGate";
import { Header } from "@/components/layout/Header";
import { auth, user } from "@/lib/api";
import type { AuthResponse } from "@/types";
import { ArrowLeft, Eye, EyeOff, Loader2 } from "lucide-react";
import Link from "next/link";
import { Suspense, useCallback, useEffect, useState } from "react";
import { useSearchParams } from "next/navigation";

// Unified email-first auth. One email box up front; /auth/email/check routes
// the user to "enter your password" (existing account) or into the verified
// signup flow (email OTP via /auth/email/start + /auth/email/verify, then
// /auth/register carries the chosen password as the final step).
type Step = "email" | "password" | "otp" | "details" | "verify-phone";

const RESEND_COOLDOWN_SECONDS = 30;

function storeSession(data: AuthResponse) {
  localStorage.setItem("token", data.token);
  localStorage.setItem("refresh_token", data.refresh_token);
  localStorage.setItem("user", JSON.stringify(data.user));
}

// Post-auth destination (?next= carries the page that sent the user here,
// e.g. a restaurant page mid-order, or /cart from checkout's pending-order
// recovery, which must resume there after a forced re-login). Only internal
// same-origin paths are allowed — absolute URLs and protocol-relative
// "//host" are dropped, so a crafted link can't turn sign-in into an open
// redirect. Same rule as /account/verify.
function sanitizeNext(raw: string | null): string {
  if (raw && raw.startsWith("/") && !raw.startsWith("//")) return raw;
  return "/";
}

function errorMessage(err: unknown): string {
  return err instanceof Error ? err.message : "Something went wrong. Please try again.";
}

function AuthFlow() {
  const searchParams = useSearchParams();
  // Where to land after sign-in/sign-up — defaults to home when no (valid)
  // ?next= was provided.
  const nextPath = sanitizeNext(searchParams.get("next"));

  const [step, setStep] = useState<Step>("email");
  const [email, setEmail] = useState(searchParams.get("email") ?? "");
  const [password, setPassword] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [code, setCode] = useState("");
  const [firstName, setFirstName] = useState("");
  const [lastName, setLastName] = useState("");
  // Session token from register, handed to the phone-verification step (its
  // /user/phone/change calls are authenticated).
  const [sessionToken, setSessionToken] = useState("");

  const [error, setError] = useState("");
  const [info, setInfo] = useState("");
  const [loading, setLoading] = useState(false);
  const [resendCooldown, setResendCooldown] = useState(0);

  // Tick the resend cooldown down once per second.
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

  // Step 1 — route the email: existing account → password, new → email OTP.
  const handleEmailSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError("");
    setLoading(true);
    try {
      const trimmed = email.trim().toLowerCase();
      setEmail(trimmed);
      const { exists } = await auth.emailCheck({ email: trimmed });
      if (exists) {
        setPassword("");
        goToStep("password");
      } else {
        await auth.emailOtp.start(trimmed);
        setCode("");
        setResendCooldown(RESEND_COOLDOWN_SECONDS);
        goToStep("otp");
      }
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setLoading(false);
    }
  };

  // Step 2a — existing account: password sign-in.
  const handleLogin = async (e: React.FormEvent) => {
    e.preventDefault();
    setError("");
    setLoading(true);
    try {
      const data = await auth.login({ email, password });
      storeSession(data);
      // Full navigation (not router.push) so the Header re-reads the stored
      // session; honors ?next= to resume an interrupted flow (e.g. adding an
      // item on a restaurant page).
      window.location.href = nextPath;
    } catch (err) {
      setError(errorMessage(err));
      setLoading(false);
    }
  };

  // Step 2b — new account: verify the emailed 6-digit code.
  const handleVerifyCode = useCallback(
    async (submitted: string) => {
      if (submitted.length !== 6 || loading) return;
      setError("");
      setLoading(true);
      try {
        await auth.emailOtp.verify(email, submitted);
        goToStep("details");
      } catch (err) {
        setError(errorMessage(err));
        setCode("");
      } finally {
        setLoading(false);
      }
    },
    [email, loading]
  );

  const handleResendCode = async () => {
    if (resendCooldown > 0 || loading) return;
    setError("");
    try {
      await auth.emailOtp.start(email);
      setCode("");
      setResendCooldown(RESEND_COOLDOWN_SECONDS);
      setInfo("We sent you a new code.");
    } catch (err) {
      setError(errorMessage(err));
    }
  };

  // Step 3 — new account: profile + password, then register (the backend
  // checks the OTP proof stamped by email/verify before creating the account).
  const handleRegister = async (e: React.FormEvent) => {
    e.preventDefault();
    setError("");
    setLoading(true);
    try {
      const data = await auth.register({
        email,
        password,
        first_name: firstName.trim(),
        last_name: lastName.trim(),
        // Phone is collected + verified in the next step, not here.
        phone: "",
      });
      // Persist the session first — the phone-verification step calls the
      // authenticated /user/phone/change endpoints with this token.
      storeSession(data);
      setSessionToken(data.token);
      // Consumers are created phone_verified=false and the backend hard-gates
      // ordering on a verified phone, so finish phone verification here instead
      // of stranding a half-verified account that 403s at checkout. (If the
      // account somehow arrives already phone-verified, skip straight through.)
      if (data.user.phone_verified) {
        window.location.href = nextPath;
      } else {
        goToStep("verify-phone");
        setLoading(false);
      }
    } catch (err) {
      const message = errorMessage(err);
      // The verify proof only lasts ~30 minutes — if it lapsed, restart the
      // OTP leg instead of stranding the user on a dead form.
      if (/not verified/i.test(message)) {
        try {
          await auth.emailOtp.start(email);
          setCode("");
          setResendCooldown(RESEND_COOLDOWN_SECONDS);
          setStep("otp");
          setError("");
          setInfo("Your verification expired — we emailed you a new code.");
        } catch {
          setError(message);
        }
      } else {
        setError(message);
      }
      setLoading(false);
    }
  };

  const heading =
    step === "email"
      ? "Welcome"
      : step === "password"
      ? "Welcome back"
      : step === "otp"
      ? "Verify your email"
      : step === "verify-phone"
      ? "One last step"
      : "Create your account";

  const subheading =
    step === "email"
      ? "Sign in or create an account"
      : step === "password"
      ? "Enter your password to sign in"
      : step === "otp"
      ? `We sent a 6-digit code to ${email}`
      : step === "verify-phone"
      ? "Verify your phone to finish setting up your account"
      : "Just a few more details";

  return (
    <div className="w-full max-w-md">
      {/* Logo */}
      <div className="text-center mb-8">
        <h1 className="text-3xl font-extrabold">
          <span className="text-brand-500">Kosher</span>
          <span className="text-white">Eats</span>
        </h1>
        <p className="text-white font-semibold mt-4">{heading}</p>
        <p className="text-dark-400 mt-1 text-sm">{subheading}</p>
      </div>

      {/* Back / change email — only before the account exists. Once we reach
          verify-phone the account is created, so there's no going back. */}
      {(step === "password" || step === "otp" || step === "details") && (
        <button
          type="button"
          onClick={() => {
            setPassword("");
            setCode("");
            goToStep("email");
          }}
          className="flex items-center gap-1.5 text-dark-400 hover:text-white text-sm mb-6 transition-colors"
        >
          <ArrowLeft className="w-4 h-4" />
          Use a different email
        </button>
      )}

      {/* Info + error banners */}
      {info && (
        <div className="bg-dark-800 border border-dark-700 text-dark-300 rounded-xl px-4 py-3 mb-6 text-sm">
          {info}
        </div>
      )}
      {error && (
        <div className="bg-red-900/30 border border-red-800 text-red-400 rounded-xl px-4 py-3 mb-6 text-sm">
          {error}
        </div>
      )}

      {/* Step 1 — email entry. Google/Apple social sign-in was removed until the
          provider SDK flows (Google Identity Services / AppleID JS) are wired
          up — the backend already exposes POST /auth/social; re-add the
          "or" divider + provider buttons below this form when that lands. */}
      {step === "email" && (
        <form onSubmit={handleEmailSubmit} className="space-y-4">
          <div>
            <label htmlFor="email" className="block text-sm text-dark-300 mb-1.5">
              Email
            </label>
            <input
              id="email"
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
            disabled={loading}
            className="btn-primary w-full flex items-center justify-center gap-2 disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {loading && <Loader2 className="w-4 h-4 animate-spin" aria-hidden="true" />}
            {loading ? "Please wait..." : "Continue"}
          </button>
        </form>
      )}

      {/* Step 2a — password sign-in */}
      {step === "password" && (
        <form onSubmit={handleLogin} className="space-y-4">
          <div>
            <label className="block text-sm text-dark-300 mb-1.5">Email</label>
            <input type="email" value={email} className="input w-full opacity-60" disabled readOnly />
          </div>
          <div>
            <label htmlFor="password" className="block text-sm text-dark-300 mb-1.5">
              Password
            </label>
            <div className="relative">
              <input
                id="password"
                type={showPassword ? "text" : "password"}
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                className="input w-full pr-12"
                placeholder="••••••••"
                autoComplete="current-password"
                autoFocus
                required
              />
              <button
                type="button"
                onClick={() => setShowPassword((s) => !s)}
                className="absolute right-4 top-1/2 -translate-y-1/2 text-dark-400 hover:text-white transition-colors"
                aria-label={showPassword ? "Hide password" : "Show password"}
              >
                {showPassword ? <EyeOff className="w-5 h-5" /> : <Eye className="w-5 h-5" />}
              </button>
            </div>
          </div>
          <button
            type="submit"
            disabled={loading}
            className="btn-primary w-full flex items-center justify-center gap-2 disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {loading && <Loader2 className="w-4 h-4 animate-spin" aria-hidden="true" />}
            {loading ? "Please wait..." : "Sign In"}
          </button>
          <p className="text-center">
            <Link
              href={`/auth/forgot?email=${encodeURIComponent(email)}`}
              className="text-brand-400 hover:text-brand-500 text-sm transition-colors"
            >
              Forgot password?
            </Link>
          </p>
        </form>
      )}

      {/* Step 2b — email OTP verification */}
      {step === "otp" && (
        <div className="space-y-6">
          <OtpInput
            value={code}
            onChange={(next) => {
              setCode(next);
              if (error) setError("");
            }}
            onComplete={handleVerifyCode}
            disabled={loading}
            error={Boolean(error)}
          />
          <button
            type="button"
            onClick={() => handleVerifyCode(code)}
            disabled={loading || code.length !== 6}
            className="btn-primary w-full flex items-center justify-center gap-2 disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {loading && <Loader2 className="w-4 h-4 animate-spin" aria-hidden="true" />}
            {loading ? "Verifying..." : "Verify"}
          </button>
          <p className="text-center text-sm text-dark-400">
            Didn&apos;t get it?{" "}
            {resendCooldown > 0 ? (
              <span>Resend in {resendCooldown}s</span>
            ) : (
              <button
                type="button"
                onClick={handleResendCode}
                className="text-brand-400 hover:text-brand-500 transition-colors"
              >
                Resend code
              </button>
            )}
          </p>
        </div>
      )}

      {/* Step 3 — new-account details */}
      {step === "details" && (
        <form onSubmit={handleRegister} className="space-y-4">
          <div className="grid grid-cols-2 gap-4">
            <div>
              <label htmlFor="first-name" className="block text-sm text-dark-300 mb-1.5">
                First name
              </label>
              <input
                id="first-name"
                type="text"
                value={firstName}
                onChange={(e) => setFirstName(e.target.value)}
                className="input w-full"
                placeholder="First name"
                autoComplete="given-name"
                autoFocus
                required
              />
            </div>
            <div>
              <label htmlFor="last-name" className="block text-sm text-dark-300 mb-1.5">
                Last name
              </label>
              <input
                id="last-name"
                type="text"
                value={lastName}
                onChange={(e) => setLastName(e.target.value)}
                className="input w-full"
                placeholder="Last name"
                autoComplete="family-name"
              />
            </div>
          </div>
          <div>
            <label htmlFor="new-password" className="block text-sm text-dark-300 mb-1.5">
              Password
            </label>
            <div className="relative">
              <input
                id="new-password"
                type={showPassword ? "text" : "password"}
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                className="input w-full pr-12"
                placeholder="At least 8 characters"
                autoComplete="new-password"
                minLength={8}
                maxLength={72}
                required
              />
              <button
                type="button"
                onClick={() => setShowPassword((s) => !s)}
                className="absolute right-4 top-1/2 -translate-y-1/2 text-dark-400 hover:text-white transition-colors"
                aria-label={showPassword ? "Hide password" : "Show password"}
              >
                {showPassword ? <EyeOff className="w-5 h-5" /> : <Eye className="w-5 h-5" />}
              </button>
            </div>
          </div>
          <button
            type="submit"
            disabled={loading}
            className="btn-primary w-full flex items-center justify-center gap-2 disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {loading && <Loader2 className="w-4 h-4 animate-spin" aria-hidden="true" />}
            {loading ? "Please wait..." : "Create Account"}
          </button>
        </form>
      )}

      {/* Step 4 — verify phone. The account now exists and email is verified,
          so VerificationGate runs only its phone leg; finishing lands the user
          on ?next= (resuming an interrupted order/checkout). */}
      {step === "verify-phone" && (
        <VerificationGate
          token={sessionToken}
          emailVerified
          phoneVerified={false}
          initialEmail={email}
          onComplete={async () => {
            // Refresh the cached user before navigating so the header reflects
            // verified status immediately (mirrors /account/verify). Non-fatal:
            // verification already succeeded server-side, so navigate regardless.
            try {
              const profile = await user.getProfile(sessionToken);
              localStorage.setItem("user", JSON.stringify(profile));
            } catch {
              // ignore — proceed to the next destination anyway
            }
            window.location.href = nextPath;
          }}
        />
      )}

      <p className="text-center text-dark-500 text-sm mt-8">
        By continuing, you agree to KosherEats&apos; Terms of Service and Privacy
        Policy.
      </p>

      {/* Role switch — this is the customer sign-in; sellers have a separate
          dashboard login. Mirrors the "Looking to order food?" link on
          /seller/login so neither role gets stranded on the wrong page. */}
      {step === "email" && (
        <p className="text-center text-dark-500 text-sm mt-4 border-t border-dark-800 pt-6">
          Own a restaurant?{" "}
          <Link
            href="/seller/login"
            className="text-brand-400 hover:text-brand-500 transition-colors"
          >
            Sign in to your seller dashboard
          </Link>
        </p>
      )}
    </div>
  );
}

export default function AuthPage() {
  return (
    <>
      <Header />
      <main className="flex-1 flex items-center justify-center px-4 py-16">
        {/* useSearchParams (email prefill) requires a Suspense boundary. */}
        <Suspense fallback={<div className="w-full max-w-md" />}>
          <AuthFlow />
        </Suspense>
      </main>
    </>
  );
}
