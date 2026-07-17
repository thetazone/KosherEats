"use client";

import { OtpInput } from "@/components/auth/OtpInput";
import { Header } from "@/components/layout/Header";
import { auth } from "@/lib/api";
import type { AuthResponse } from "@/types";
import { ArrowLeft, Eye, EyeOff } from "lucide-react";
import Link from "next/link";
import { Suspense, useCallback, useEffect, useState } from "react";
import { useSearchParams } from "next/navigation";

// Unified email-first auth. One email box up front; /auth/email/check routes
// the user to "enter your password" (existing account) or into the verified
// signup flow (email OTP via /auth/email/start + /auth/email/verify, then
// /auth/register carries the chosen password as the final step).
type Step = "email" | "password" | "otp" | "details";

const RESEND_COOLDOWN_SECONDS = 30;

function storeSession(data: AuthResponse) {
  localStorage.setItem("token", data.token);
  localStorage.setItem("refresh_token", data.refresh_token);
  localStorage.setItem("user", JSON.stringify(data.user));
}

function errorMessage(err: unknown): string {
  return err instanceof Error ? err.message : "Something went wrong. Please try again.";
}

function AuthFlow() {
  const searchParams = useSearchParams();

  const [step, setStep] = useState<Step>("email");
  const [email, setEmail] = useState(searchParams.get("email") ?? "");
  const [password, setPassword] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [code, setCode] = useState("");
  const [firstName, setFirstName] = useState("");
  const [lastName, setLastName] = useState("");
  const [phone, setPhone] = useState("");

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

  const handleSocialLogin = (provider: "google" | "apple") => {
    // Each provider needs its SDK flow wired up (Google Identity Services /
    // AppleID JS) plus client IDs in .env — placeholder until then.
    setError(
      `${provider.charAt(0).toUpperCase() + provider.slice(1)} login requires SDK setup. Configure your ${provider} app credentials in .env to enable.`
    );
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
      window.location.href = "/";
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
        phone: phone.trim(),
      });
      storeSession(data);
      window.location.href = "/";
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
      : "Create your account";

  const subheading =
    step === "email"
      ? "Sign in or create an account"
      : step === "password"
      ? "Enter your password to sign in"
      : step === "otp"
      ? `We sent a 6-digit code to ${email}`
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

      {/* Back / change email */}
      {step !== "email" && (
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

      {/* Step 1 — email entry */}
      {step === "email" && (
        <>
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
              className="btn-primary w-full disabled:opacity-50 disabled:cursor-not-allowed"
            >
              {loading ? "Please wait..." : "Continue"}
            </button>
          </form>

          {/* Divider */}
          <div className="flex items-center gap-4 my-8">
            <div className="flex-1 h-px bg-dark-700" />
            <span className="text-dark-500 text-sm">or</span>
            <div className="flex-1 h-px bg-dark-700" />
          </div>

          {/* Social login */}
          <div className="space-y-3">
            <button
              onClick={() => handleSocialLogin("google")}
              disabled={loading}
              className="w-full flex items-center justify-center gap-3 bg-white hover:bg-gray-100 text-gray-900 font-medium py-3 px-6 rounded-xl transition-colors"
            >
              <svg className="w-5 h-5" viewBox="0 0 24 24">
                <path fill="#4285F4" d="M22.56 12.25c0-.78-.07-1.53-.2-2.25H12v4.26h5.92a5.06 5.06 0 01-2.2 3.32v2.77h3.57c2.08-1.92 3.28-4.74 3.28-8.1z"/>
                <path fill="#34A853" d="M12 23c2.97 0 5.46-.98 7.28-2.66l-3.57-2.77c-.98.66-2.23 1.06-3.71 1.06-2.86 0-5.29-1.93-6.16-4.53H2.18v2.84C3.99 20.53 7.7 23 12 23z"/>
                <path fill="#FBBC05" d="M5.84 14.09c-.22-.66-.35-1.36-.35-2.09s.13-1.43.35-2.09V7.07H2.18C1.43 8.55 1 10.22 1 12s.43 3.45 1.18 4.93l2.85-2.22.81-.62z"/>
                <path fill="#EA4335" d="M12 5.38c1.62 0 3.06.56 4.21 1.64l3.15-3.15C17.45 2.09 14.97 1 12 1 7.7 1 3.99 3.47 2.18 7.07l3.66 2.84c.87-2.6 3.3-4.53 6.16-4.53z"/>
              </svg>
              Continue with Google
            </button>

            <button
              onClick={() => handleSocialLogin("apple")}
              disabled={loading}
              className="w-full flex items-center justify-center gap-3 bg-dark-800 hover:bg-dark-700 text-white font-medium py-3 px-6 rounded-xl border border-dark-700 transition-colors"
            >
              <svg className="w-5 h-5" fill="currentColor" viewBox="0 0 24 24">
                <path d="M17.05 20.28c-.98.95-2.05.88-3.08.4-1.09-.5-2.08-.48-3.24 0-1.44.62-2.2.44-3.06-.4C2.79 15.25 3.51 7.59 9.05 7.31c1.35.07 2.29.74 3.08.8 1.18-.24 2.31-.93 3.57-.84 1.51.12 2.65.72 3.4 1.8-3.12 1.87-2.38 5.98.48 7.13-.57 1.5-1.31 2.99-2.54 4.09zM12.03 7.25c-.15-2.23 1.66-4.07 3.74-4.25.29 2.58-2.34 4.5-3.74 4.25z"/>
              </svg>
              Continue with Apple
            </button>
          </div>
        </>
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
            className="btn-primary w-full disabled:opacity-50 disabled:cursor-not-allowed"
          >
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
            className="btn-primary w-full disabled:opacity-50 disabled:cursor-not-allowed"
          >
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
            <label htmlFor="phone" className="block text-sm text-dark-300 mb-1.5">
              Phone <span className="text-dark-500">(optional)</span>
            </label>
            <input
              id="phone"
              type="tel"
              value={phone}
              onChange={(e) => setPhone(e.target.value)}
              className="input w-full"
              placeholder="(555) 000-0000"
              autoComplete="tel"
            />
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
            className="btn-primary w-full disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {loading ? "Please wait..." : "Create Account"}
          </button>
        </form>
      )}

      <p className="text-center text-dark-500 text-sm mt-8">
        By continuing, you agree to KosherEats&apos; Terms of Service and Privacy
        Policy.
      </p>
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
