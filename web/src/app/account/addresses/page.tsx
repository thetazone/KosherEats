"use client";

import { formatAddress } from "@/components/checkout/checkoutShared";
import { Header } from "@/components/layout/Header";
import { user as userApi } from "@/lib/api";
import type { Address } from "@/types";
import { ArrowLeft, Loader2, MapPin, Plus } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useCallback, useEffect, useState } from "react";

// Saved delivery addresses: list / add / delete / set default. The backend
// stores lat/lng verbatim (no geocoding, and no web-side geocoding key), so
// the user supplies coordinates manually — same pattern and validation as the
// checkout address form (CheckoutPanel.saveAddress).

interface AddressForm {
  label: string;
  street: string;
  apt: string;
  city: string;
  state: string;
  zip_code: string;
  lat: string;
  lng: string;
}

const EMPTY_FORM: AddressForm = {
  label: "Home",
  street: "",
  apt: "",
  city: "",
  state: "",
  zip_code: "",
  lat: "",
  lng: "",
};

function errorMessage(err: unknown): string {
  return err instanceof Error ? err.message : "Something went wrong. Please try again.";
}

function isUnauthorized(err: unknown): boolean {
  const msg = String(err instanceof Error ? err.message : err).toLowerCase();
  return msg.includes("401") || msg.includes("unauthorized") || msg.includes("invalid token");
}

export default function AddressesPage() {
  const router = useRouter();

  const [token, setToken] = useState<string | null>(null);
  const [addresses, setAddresses] = useState<Address[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);

  // Add form
  const [showForm, setShowForm] = useState(false);
  const [form, setForm] = useState<AddressForm>(EMPTY_FORM);
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  // Row actions
  const [pendingDelete, setPendingDelete] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);

  const loadAddresses = useCallback(
    async (t: string) => {
      setLoading(true);
      setLoadError(null);
      try {
        setAddresses(await userApi.listAddresses(t));
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

  useEffect(() => {
    const t = typeof window !== "undefined" ? window.localStorage.getItem("token") : null;
    if (!t) {
      router.replace("/auth");
      return;
    }
    setToken(t);
    void loadAddresses(t);
  }, [router, loadAddresses]);

  const setField = (key: keyof AddressForm) => (e: React.ChangeEvent<HTMLInputElement>) =>
    setForm((f) => ({ ...f, [key]: e.target.value }));

  async function saveAddress(e: React.FormEvent) {
    e.preventDefault();
    if (!token || saving) return;
    const street = form.street.trim();
    const city = form.city.trim();
    const state = form.state.trim();
    const zip = form.zip_code.trim();
    if (!street || !city || !state || !zip) {
      setFormError("Please fill in street, city, state, and ZIP.");
      return;
    }
    // Reject placeholder / out-of-range coordinates (especially null-island
    // 0,0) rather than send junk into delivery routing and fee quoting.
    const lat = parseFloat(form.lat);
    const lng = parseFloat(form.lng);
    if (!Number.isFinite(lat) || !Number.isFinite(lng)) {
      setFormError("Enter the address's latitude and longitude.");
      return;
    }
    if (lat < -90 || lat > 90 || lng < -180 || lng > 180) {
      setFormError("Latitude must be between -90 and 90, longitude between -180 and 180.");
      return;
    }
    if (lat === 0 && lng === 0) {
      setFormError("Coordinates can't be (0, 0). Enter the address's real latitude and longitude.");
      return;
    }
    setSaving(true);
    setFormError(null);
    try {
      await userApi.addAddress(token, {
        label: form.label.trim() || "Home",
        street,
        ...(form.apt.trim() ? { apt: form.apt.trim() } : {}),
        city,
        state,
        zip_code: zip,
        lat,
        lng,
      });
      setAddresses(await userApi.listAddresses(token));
      setShowForm(false);
      setForm(EMPTY_FORM);
    } catch (err) {
      if (isUnauthorized(err)) {
        window.localStorage.removeItem("token");
        router.replace("/auth");
        return;
      }
      setFormError(errorMessage(err));
    } finally {
      setSaving(false);
    }
  }

  async function setDefault(id: string) {
    if (!token || busyId) return;
    setBusyId(id);
    setActionError(null);
    try {
      await userApi.setDefaultAddress(token, id);
      // Reflect immediately, then reconcile with the server's ordering
      // (default-first) on the next list load.
      setAddresses((prev) =>
        prev.map((a) => ({ ...a, is_default: a.id === id }))
      );
    } catch (err) {
      setActionError(errorMessage(err));
    } finally {
      setBusyId(null);
    }
  }

  async function deleteAddress(id: string) {
    if (!token || busyId) return;
    setBusyId(id);
    setActionError(null);
    try {
      await userApi.deleteAddress(token, id);
      setAddresses((prev) => prev.filter((a) => a.id !== id));
      setPendingDelete(null);
    } catch (err) {
      setActionError(errorMessage(err));
    } finally {
      setBusyId(null);
    }
  }

  return (
    <>
      <Header />
      <main className="flex-1 w-full max-w-2xl mx-auto px-4 py-8">
        <Link
          href="/account"
          className="inline-flex items-center gap-1.5 text-sm text-dark-400 hover:text-white transition-colors mb-4"
        >
          <ArrowLeft className="w-4 h-4" aria-hidden="true" />
          Account
        </Link>
        <div className="flex items-center justify-between mb-6">
          <h1 className="text-3xl font-extrabold">Addresses</h1>
          {!showForm && !loading && !loadError && (
            <button
              type="button"
              onClick={() => {
                setShowForm(true);
                setFormError(null);
              }}
              className="btn-primary text-sm py-2 px-4 inline-flex items-center gap-1.5"
            >
              <Plus className="w-4 h-4" aria-hidden="true" />
              Add address
            </button>
          )}
        </div>

        {loading ? (
          <div className="space-y-4" aria-hidden="true">
            {Array.from({ length: 3 }).map((_, i) => (
              <div key={i} className="card p-5 animate-pulse space-y-3">
                <div className="h-5 bg-dark-800 rounded w-1/4" />
                <div className="h-4 bg-dark-800 rounded w-2/3" />
              </div>
            ))}
          </div>
        ) : loadError ? (
          <div className="card p-12 text-center">
            <h2 className="text-xl font-bold mb-2">Couldn&apos;t load your addresses</h2>
            <p className="text-dark-400 mb-6">{loadError}</p>
            <button
              onClick={() => token && loadAddresses(token)}
              className="btn-primary inline-block"
            >
              Retry
            </button>
          </div>
        ) : (
          <div className="space-y-4">
            {actionError && (
              <div className="bg-red-900/30 border border-red-800 text-red-400 rounded-xl px-4 py-3 text-sm">
                {actionError}
              </div>
            )}

            {showForm && (
              <form onSubmit={saveAddress} className="card p-5 space-y-3">
                <h2 className="font-bold">New address</h2>
                {formError && (
                  <div className="bg-red-900/30 border border-red-800 text-red-400 rounded-xl px-4 py-3 text-sm">
                    {formError}
                  </div>
                )}
                <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                  <div>
                    <label htmlFor="addr-label" className="block text-sm text-dark-300 mb-1.5">
                      Label
                    </label>
                    <input
                      id="addr-label"
                      type="text"
                      value={form.label}
                      onChange={setField("label")}
                      className="input w-full"
                      placeholder="Home"
                    />
                  </div>
                  <div>
                    <label htmlFor="addr-apt" className="block text-sm text-dark-300 mb-1.5">
                      Apt / Suite (optional)
                    </label>
                    <input
                      id="addr-apt"
                      type="text"
                      value={form.apt}
                      onChange={setField("apt")}
                      className="input w-full"
                      placeholder="Apt 4B"
                      autoComplete="address-line2"
                    />
                  </div>
                </div>
                <div>
                  <label htmlFor="addr-street" className="block text-sm text-dark-300 mb-1.5">
                    Street
                  </label>
                  <input
                    id="addr-street"
                    type="text"
                    value={form.street}
                    onChange={setField("street")}
                    className="input w-full"
                    placeholder="123 Main St"
                    autoComplete="address-line1"
                    required
                  />
                </div>
                <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
                  <div>
                    <label htmlFor="addr-city" className="block text-sm text-dark-300 mb-1.5">
                      City
                    </label>
                    <input
                      id="addr-city"
                      type="text"
                      value={form.city}
                      onChange={setField("city")}
                      className="input w-full"
                      placeholder="Brooklyn"
                      autoComplete="address-level2"
                      required
                    />
                  </div>
                  <div>
                    <label htmlFor="addr-state" className="block text-sm text-dark-300 mb-1.5">
                      State
                    </label>
                    <input
                      id="addr-state"
                      type="text"
                      value={form.state}
                      onChange={setField("state")}
                      className="input w-full"
                      placeholder="NY"
                      autoComplete="address-level1"
                      required
                    />
                  </div>
                  <div>
                    <label htmlFor="addr-zip" className="block text-sm text-dark-300 mb-1.5">
                      ZIP
                    </label>
                    <input
                      id="addr-zip"
                      type="text"
                      value={form.zip_code}
                      onChange={setField("zip_code")}
                      className="input w-full"
                      placeholder="11223"
                      autoComplete="postal-code"
                      required
                    />
                  </div>
                </div>
                <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                  <div>
                    <label htmlFor="addr-lat" className="block text-sm text-dark-300 mb-1.5">
                      Latitude
                    </label>
                    <input
                      id="addr-lat"
                      type="text"
                      inputMode="decimal"
                      value={form.lat}
                      onChange={setField("lat")}
                      className="input w-full"
                      placeholder="40.6002"
                      required
                    />
                  </div>
                  <div>
                    <label htmlFor="addr-lng" className="block text-sm text-dark-300 mb-1.5">
                      Longitude
                    </label>
                    <input
                      id="addr-lng"
                      type="text"
                      inputMode="decimal"
                      value={form.lng}
                      onChange={setField("lng")}
                      className="input w-full"
                      placeholder="-73.9738"
                      required
                    />
                  </div>
                </div>
                <p className="text-xs text-dark-500">
                  Coordinates power delivery routing and the delivery-fee quote. You can copy them
                  from your maps app.
                </p>
                <div className="flex gap-2">
                  <button
                    type="submit"
                    disabled={saving}
                    className="btn-primary text-sm py-2 px-4 inline-flex items-center gap-2 disabled:opacity-50 disabled:cursor-not-allowed"
                  >
                    {saving && <Loader2 className="w-4 h-4 animate-spin" aria-hidden="true" />}
                    {saving ? "Saving…" : "Save address"}
                  </button>
                  <button
                    type="button"
                    onClick={() => {
                      setShowForm(false);
                      setFormError(null);
                    }}
                    disabled={saving}
                    className="btn-secondary text-sm py-2 px-4 disabled:opacity-50"
                  >
                    Cancel
                  </button>
                </div>
              </form>
            )}

            {addresses.length === 0 && !showForm ? (
              <div className="card p-12 text-center">
                <MapPin className="w-16 h-16 text-dark-600 mx-auto mb-4" aria-hidden="true" />
                <h2 className="text-xl font-bold mb-2">No saved addresses</h2>
                <p className="text-dark-400 mb-6">
                  Save a delivery address to speed through checkout.
                </p>
                <button
                  type="button"
                  onClick={() => {
                    setShowForm(true);
                    setFormError(null);
                  }}
                  className="btn-primary inline-block"
                >
                  Add your first address
                </button>
              </div>
            ) : (
              addresses.map((a) => (
                <div key={a.id} className="card p-5">
                  <div className="flex items-start justify-between gap-4">
                    <div className="min-w-0">
                      <div className="flex items-center gap-2 mb-1">
                        <span className="font-bold">{a.label}</span>
                        {a.is_default && (
                          <span className="text-xs bg-brand-900/40 text-brand-400 border border-brand-700 rounded-full px-2 py-0.5">
                            Default
                          </span>
                        )}
                      </div>
                      <p className="text-sm text-dark-300">{formatAddress(a)}</p>
                    </div>
                    <div className="flex items-center gap-3 shrink-0 text-sm">
                      {!a.is_default && (
                        <button
                          type="button"
                          onClick={() => void setDefault(a.id)}
                          disabled={busyId !== null}
                          className="text-brand-400 hover:text-brand-300 font-medium transition-colors disabled:opacity-50"
                        >
                          {busyId === a.id ? "Saving…" : "Set default"}
                        </button>
                      )}
                      {pendingDelete === a.id ? (
                        <>
                          <button
                            type="button"
                            onClick={() => void deleteAddress(a.id)}
                            disabled={busyId !== null}
                            className="text-red-400 hover:text-red-300 font-medium transition-colors disabled:opacity-50"
                          >
                            {busyId === a.id ? "Deleting…" : "Confirm"}
                          </button>
                          <button
                            type="button"
                            onClick={() => setPendingDelete(null)}
                            disabled={busyId !== null}
                            className="text-dark-400 hover:text-white transition-colors disabled:opacity-50"
                          >
                            Cancel
                          </button>
                        </>
                      ) : (
                        <button
                          type="button"
                          onClick={() => {
                            setPendingDelete(a.id);
                            setActionError(null);
                          }}
                          disabled={busyId !== null}
                          className="text-dark-400 hover:text-red-400 font-medium transition-colors disabled:opacity-50"
                        >
                          Delete
                        </button>
                      )}
                    </div>
                  </div>
                </div>
              ))
            )}
          </div>
        )}
      </main>
    </>
  );
}
