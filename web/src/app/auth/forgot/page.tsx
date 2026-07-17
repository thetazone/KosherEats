"use client";

import { OtpInput } from "@/components/auth/OtpInput";
import { Header } from "@/components/layout/Header";
import { auth } from "@/lib/api";
import { ArrowLeft, CheckCircle2, Eye, EyeOff, Loader2 } from "lucide-react";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { Suspense, useEffect, useState } from "react";

// Password reset: /auth/password/forgot emails a 6-digit code (the backend
// always answers 200 so the form can't probe which emails have accounts),
// then /auth/password/reset trades code + new password for an updated
// credential.
type Step = "email" | "reset" | "done";

const RESEND_COOLDOWN_SECONDS = 30;

function errorMessage(err: unknown): string {
  return err instanceof Error ? err.message : "Something went wrong. Please try again.";
}

function ForgotPasswordFlow() {
  const searchParams = useSearchParams();

  const [step, setStep] = useState<Step>("email");
  const [email, setEmail] = useState(searchParams.get("email") ?? "");
  const [code, setCode] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [showPassword, setShowPassword] = useState(false);

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

  // Step 1 — request the code. Always succeeds from the user's perspective.
  const handleSendCode = async (e: React.FormEvent) => {
    e.preventDefault();
    setError("");
    setLoading(true);
    try {
      const trimmed = email.trim().toLowerCase();
      setEmail(trimmed);
      const { message } = await auth.forgot({ email: trimmed });
      setCode("");
      setNewPassword("");
      setResendCooldown(RESEND_COOLDOWN_SECONDS);
      setInfo(message);
      setStep("reset");
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setLoading(false);
    }
  };

  const handleResendCode = async () => {
    if (resendCooldown > 0 || loading) return;
    setError("");
    try {
      const { message } = await auth.forgot({ email });
      setCode("");
      setResendCooldown(RESEND_COOLDOWN_SECONDS);
      setInfo(message);
    } catch (err) {
      setError(errorMessage(err));
    }
  };

  // Step 2 — trade code + new password for an updated credential.
  const handleReset = async (e: React.FormEvent) => {
    e.preventDefault();
    if (code.length !== 6) {
      setError("Enter the 6-digit code from your email.");
      return;
    }
    setError("");
    setInfo("");
    setLoading(true);
    try {
      await auth.reset({ email, code, new_password: newPassword });
      setStep("done");
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="w-full max-w-md">
      {/* Logo */}
      <div className="text-center mb-8">
        <h1 className="text-3xl font-extrabold">
          <span className="text-brand-500">Kosher</span>
          <span className="text-white">Eats</span>
        </h1>
        <p className="text-white font-semibold mt-4">
          {step === "done" ? "Password updated" : "Reset your password"}
        </p>
        <p className="text-dark-400 mt-1 text-sm">
          {step === "email"
            ? "Enter your account email and we'll send you a 6-digit reset code."
            : step === "reset"
            ? `Enter the code we sent to ${email} and choose a new password.`
            : "You can now sign in with your new password."}
        </p>
      </div>

      {/* Back link */}
      {step === "email" && (
        <Link
          href="/auth"
          className="inline-flex items-center gap-1.5 text-dark-400 hover:text-white text-sm mb-6 transition-colors"
        >
          <ArrowLeft className="w-4 h-4" />
          Back to sign in
        </Link>
      )}
      {step === "reset" && (
        <button
          type="button"
          onClick={() => {
            setError("");
            setInfo("");
            setCode("");
            setStep("email");
          }}
          className="flex items-center gap-1.5 text-dark-400 hover:text-white text-sm mb-6 transition-colors"
        >
          <ArrowLeft className="w-4 h-4" />
          Use a different email
        </button>
      )}

      {/* Info + error banners */}
      {info && step !== "done" && (
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
        <form onSubmit={handleSendCode} className="space-y-4">
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
            {loading ? "Please wait..." : "Send reset code"}
          </button>
        </form>
      )}

      {/* Step 2 — code + new password */}
      {step === "reset" && (
        <form onSubmit={handleReset} className="space-y-6">
          <div>
            <label className="block text-sm text-dark-300 mb-3 text-center">
              Reset code
            </label>
            <OtpInput
              value={code}
              onChange={(next) => {
                setCode(next);
                if (error) setError("");
              }}
              disabled={loading}
              error={Boolean(error)}
            />
          </div>
          <div>
            <label htmlFor="new-password" className="block text-sm text-dark-300 mb-1.5">
              New password
            </label>
            <div className="relative">
              <input
                id="new-password"
                type={showPassword ? "text" : "password"}
                value={newPassword}
                onChange={(e) => setNewPassword(e.target.value)}
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
            disabled={loading || code.length !== 6}
            className="btn-primary w-full flex items-center justify-center gap-2 disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {loading && <Loader2 className="w-4 h-4 animate-spin" aria-hidden="true" />}
            {loading ? "Please wait..." : "Reset password"}
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
        </form>
      )}

      {/* Step 3 — success */}
      {step === "done" && (
        <div className="card p-8 text-center">
          <CheckCircle2 className="w-12 h-12 text-brand-500 mx-auto mb-4" />
          <p className="text-dark-300 mb-6">
            Your password has been updated. Sign in with your new password to
            continue.
          </p>
          <Link
            href={`/auth?email=${encodeURIComponent(email)}`}
            className="btn-primary inline-block"
          >
            Sign in
          </Link>
        </div>
      )}
    </div>
  );
}

export default function ForgotPasswordPage() {
  return (
    <>
      <Header />
      <main className="flex-1 flex items-center justify-center px-4 py-16">
        {/* useSearchParams (email prefill) requires a Suspense boundary. */}
        <Suspense fallback={<div className="w-full max-w-md" />}>
          <ForgotPasswordFlow />
        </Suspense>
      </main>
    </>
  );
}
