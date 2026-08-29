"use client";

import { Header } from "@/components/layout/Header";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useState } from "react";

// Where to land after a successful sign-in. Pages that bounce a signed-out
// visitor here pass ?next=<path> so they get sent back; everything else falls
// through to the browse funnel rather than the marketing page. Only relative
// same-origin paths are honored — "//evil.com" and "/\\evil.com" are read as
// protocol-relative URLs by browsers, so they'd be an open redirect.
function safeNext(raw: string | null): string {
  if (!raw || !raw.startsWith("/")) return "/search";
  if (raw.startsWith("//") || raw.startsWith("/\\")) return "/search";
  if (raw === "/auth" || raw.startsWith("/auth?")) return "/search";
  return raw;
}

function AuthPageContent() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const next = safeNext(searchParams.get("next"));

  const [isLogin, setIsLogin] = useState(true);
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [firstName, setFirstName] = useState("");
  const [lastName, setLastName] = useState("");
  const [phone, setPhone] = useState("");
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError("");
    setLoading(true);

    try {
      const endpoint = isLogin ? "/auth/login" : "/auth/register";
      const body = isLogin
        ? { email, password }
        : { email, password, first_name: firstName, last_name: lastName, phone };

      const res = await fetch(
        `${process.env.NEXT_PUBLIC_API_URL || "https://koshereats-api.fly.dev/api/v1"}${endpoint}`,
        {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(body),
        }
      );

      const data = await res.json();

      if (!res.ok) {
        setError(data.error || "Something went wrong");
        return;
      }

      // Store token
      localStorage.setItem("token", data.token);
      localStorage.setItem("refresh_token", data.refresh_token);
      localStorage.setItem("user", JSON.stringify(data.user));

      // Back to wherever the user was bounced from (defaults to /search).
      // Client-side so the in-flight app state and history survive.
      router.replace(next);
    } catch {
      setError("Network error. Please try again.");
    } finally {
      setLoading(false);
    }
  };

  return (
    <>
      <Header />
      <main className="flex-1 flex items-center justify-center px-4 py-16">
        <div className="w-full max-w-md">
          {/* Logo */}
          <div className="text-center mb-8">
            <h1 className="text-3xl font-extrabold">
              <span className="text-brand-500">Kosher</span>
              <span className="text-white">Eats</span>
            </h1>
            <p className="text-dark-400 mt-2">
              {isLogin ? "Welcome back" : "Create your account"}
            </p>
          </div>

          {/* Social Login Buttons — no provider SDK is wired up yet, so these
              stay visibly disabled rather than failing on tap. */}
          <div className="space-y-3 mb-8">
            <button
              type="button"
              disabled
              aria-disabled="true"
              aria-describedby="social-login-note"
              className="w-full flex items-center justify-center gap-3 bg-white text-dark-900 font-medium py-3 px-6 rounded-xl opacity-50 cursor-not-allowed"
            >
              {/* RUBRIC-WAIVER M1: official Google brand mark colors */}
              <svg className="w-5 h-5" viewBox="0 0 24 24" aria-hidden="true">
                <path fill="#4285F4" d="M22.56 12.25c0-.78-.07-1.53-.2-2.25H12v4.26h5.92a5.06 5.06 0 01-2.2 3.32v2.77h3.57c2.08-1.92 3.28-4.74 3.28-8.1z"/>
                <path fill="#34A853" d="M12 23c2.97 0 5.46-.98 7.28-2.66l-3.57-2.77c-.98.66-2.23 1.06-3.71 1.06-2.86 0-5.29-1.93-6.16-4.53H2.18v2.84C3.99 20.53 7.7 23 12 23z"/>
                <path fill="#FBBC05" d="M5.84 14.09c-.22-.66-.35-1.36-.35-2.09s.13-1.43.35-2.09V7.07H2.18C1.43 8.55 1 10.22 1 12s.43 3.45 1.18 4.93l2.85-2.22.81-.62z"/>
                <path fill="#EA4335" d="M12 5.38c1.62 0 3.06.56 4.21 1.64l3.15-3.15C17.45 2.09 14.97 1 12 1 7.7 1 3.99 3.47 2.18 7.07l3.66 2.84c.87-2.6 3.3-4.53 6.16-4.53z"/>
              </svg>
              Continue with Google
            </button>

            <button
              type="button"
              disabled
              aria-disabled="true"
              aria-describedby="social-login-note"
              className="w-full flex items-center justify-center gap-3 bg-dark-800 text-white font-medium py-3 px-6 rounded-xl border border-dark-700 opacity-50 cursor-not-allowed"
            >
              <svg className="w-5 h-5" fill="currentColor" viewBox="0 0 24 24" aria-hidden="true">
                <path d="M17.05 20.28c-.98.95-2.05.88-3.08.4-1.09-.5-2.08-.48-3.24 0-1.44.62-2.2.44-3.06-.4C2.79 15.25 3.51 7.59 9.05 7.31c1.35.07 2.29.74 3.08.8 1.18-.24 2.31-.93 3.57-.84 1.51.12 2.65.72 3.4 1.8-3.12 1.87-2.38 5.98.48 7.13-.57 1.5-1.31 2.99-2.54 4.09zM12.03 7.25c-.15-2.23 1.66-4.07 3.74-4.25.29 2.58-2.34 4.5-3.74 4.25z"/>
              </svg>
              Continue with Apple
            </button>

            <p id="social-login-note" className="text-center text-dark-500 text-sm">
              Google and Apple sign-in are coming soon — use your email below.
            </p>
          </div>

          {/* Divider */}
          <div className="flex items-center gap-4 mb-8">
            <div className="flex-1 h-px bg-dark-700" />
            <span className="text-dark-500 text-sm">or</span>
            <div className="flex-1 h-px bg-dark-700" />
          </div>

          {/* Toggle */}
          <div className="flex bg-dark-800 rounded-xl p-1 mb-8">
            <button
              onClick={() => setIsLogin(true)}
              className={`flex-1 py-2.5 rounded-lg text-sm font-medium transition-colors ${
                isLogin
                  ? "bg-brand-500 text-white"
                  : "text-dark-400 hover:text-white"
              }`}
            >
              Sign In
            </button>
            <button
              onClick={() => setIsLogin(false)}
              className={`flex-1 py-2.5 rounded-lg text-sm font-medium transition-colors ${
                !isLogin
                  ? "bg-brand-500 text-white"
                  : "text-dark-400 hover:text-white"
              }`}
            >
              Sign Up
            </button>
          </div>

          {/* Error */}
          {error && (
            <div
              role="alert"
              aria-live="assertive"
              className="bg-danger-900/30 border border-danger-800 text-danger-400 rounded-xl px-4 py-3 mb-6 text-sm"
            >
              {error}
            </div>
          )}

          {/* Form */}
          <form onSubmit={handleSubmit} className="space-y-4">
            {!isLogin && (
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
                    required={!isLogin}
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
                  />
                </div>
              </div>
            )}

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
                required
              />
            </div>

            {!isLogin && (
              <div>
                <label htmlFor="phone" className="block text-sm text-dark-300 mb-1.5">
                  Phone
                </label>
                <input
                  id="phone"
                  type="tel"
                  value={phone}
                  onChange={(e) => setPhone(e.target.value)}
                  className="input w-full"
                  placeholder="(555) 000-0000"
                />
              </div>
            )}

            <div>
              <label htmlFor="password" className="block text-sm text-dark-300 mb-1.5">
                Password
              </label>
              <input
                id="password"
                type="password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                className="input w-full"
                placeholder="••••••••"
                required
                minLength={8}
              />
            </div>

            <button
              type="submit"
              disabled={loading}
              className="btn-primary w-full disabled:opacity-50 disabled:cursor-not-allowed"
            >
              {loading
                ? "Please wait..."
                : isLogin
                ? "Sign In"
                : "Create Account"}
            </button>
          </form>

          <p className="text-center text-dark-500 text-sm mt-8">
            By continuing, you agree to KosherEats&apos;{" "}
            <Link href="/terms" className="text-brand-400 hover:text-brand-500 transition-colors">
              Terms of Service
            </Link>{" "}
            and{" "}
            <Link href="/privacy" className="text-brand-400 hover:text-brand-500 transition-colors">
              Privacy Policy
            </Link>
            .
          </p>
        </div>
      </main>
    </>
  );
}

export default function AuthPage() {
  return (
    <Suspense
      fallback={
        <>
          <Header />
          <main className="flex-1 flex items-center justify-center px-4 py-16">
            <div className="text-dark-400 text-sm">Loading…</div>
          </main>
        </>
      }
    >
      <AuthPageContent />
    </Suspense>
  );
}
