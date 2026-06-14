"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { adminApi } from "@/lib/adminApi";

/**
 * Two-step flow:
 *   1. Create a seller user account (fresh email + password)
 *   2. Create the restaurant with that seller as owner
 *
 * Both steps happen on submit. This removes the "which seller owns this
 * restaurant?" lookup problem for the admin — each new restaurant gets a
 * dedicated seller login they can hand off to the restaurant operator.
 */
// An already-registered seller email is a recoverable condition: it means a
// prior submit created the seller but the restaurant step failed. We proceed to
// step 2 instead of bricking the flow with a hard duplicate-email error.
function isDuplicateEmail(err: unknown): boolean {
  const msg = String(err instanceof Error ? err.message : err).toLowerCase();
  return msg.includes("already exists") || msg.includes("already registered") || msg.includes("duplicate") || msg.includes("409");
}

export default function NewRestaurantPage() {
  const router = useRouter();
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // Persisted across resubmits so a failed step 2 doesn't force step 1 to
  // re-run createSeller (which would fail on duplicate email).
  const [createdSellerId, setCreatedSellerId] = useState<string | null>(null);

  // Seller account fields
  const [sellerEmail, setSellerEmail] = useState("");
  const [sellerPassword, setSellerPassword] = useState("");
  const [sellerFirstName, setSellerFirstName] = useState("");
  const [sellerLastName, setSellerLastName] = useState("");
  const [sellerPhone, setSellerPhone] = useState("");

  // Restaurant fields
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [phone, setPhone] = useState("");
  const [email, setEmail] = useState("");
  const [street, setStreet] = useState("");
  const [city, setCity] = useState("");
  const [state, setState] = useState("");
  const [zip, setZip] = useState("");
  const [latitude, setLatitude] = useState("");
  const [longitude, setLongitude] = useState("");
  const [cuisine, setCuisine] = useState("");
  const [kosherCert, setKosherCert] = useState("OU");
  const [isGlatt, setIsGlatt] = useState(false);
  const [isCholovYisroel, setIsCholovYisroel] = useState(false);
  const [isPasYisroel, setIsPasYisroel] = useState(false);
  const [deliveryFee, setDeliveryFee] = useState("3.99");
  const [imageUrl, setImageUrl] = useState("");

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);

    // Validate coordinates before any network call. No geocoding API key is
    // available, so the admin enters lat/lng manually; we guard against the
    // (0,0) "Null Island" default that buries restaurants in distance sorting.
    const lat = parseFloat(latitude);
    const lng = parseFloat(longitude);
    if (!Number.isFinite(lat) || !Number.isFinite(lng)) {
      setError("Latitude and longitude are required and must be valid numbers.");
      return;
    }
    if (lat < -90 || lat > 90) {
      setError("Latitude must be between -90 and 90.");
      return;
    }
    if (lng < -180 || lng > 180) {
      setError("Longitude must be between -180 and 180.");
      return;
    }
    if (lat === 0 && lng === 0) {
      setError("Coordinates can't be (0, 0). Enter the restaurant's real latitude and longitude.");
      return;
    }

    setLoading(true);
    try {
      // Step 1: create seller — skipped on resubmit if a seller already exists.
      let sellerId = createdSellerId;
      if (!sellerId) {
        try {
          const seller = await adminApi.createSeller({
            email: sellerEmail,
            password: sellerPassword,
            first_name: sellerFirstName,
            last_name: sellerLastName,
            phone: sellerPhone,
          });
          sellerId = seller.id;
          setCreatedSellerId(seller.id);
        } catch (err) {
          if (isDuplicateEmail(err)) {
            // A previous submit already created this seller. Surface the
            // restaurant fields step instead of bricking on duplicate email.
            setError(
              "A seller with this email already exists (likely from a previous attempt). Fix the restaurant fields below and submit again to finish.",
            );
            return;
          }
          setError(`Step 1 (create seller) failed: ${(err as Error).message}`);
          return;
        }
      }

      // Step 2: create restaurant owned by that seller
      try {
        await adminApi.createRestaurant({
          owner_id: sellerId,
          name,
          description,
          image_url: imageUrl,
          phone,
          email,
          street,
          city,
          state,
          zip_code: zip,
          lat,
          lng,
          kosher_certification: kosherCert,
          is_glatt_kosher: isGlatt,
          is_cholov_yisroel: isCholovYisroel,
          is_pas_yisroel: isPasYisroel,
          cuisine_type: cuisine.split(",").map((c) => c.trim()).filter(Boolean),
          delivery_fee: Math.round(parseFloat(deliveryFee || "0") * 100),
          min_order: 0,
          est_delivery_min: 25,
          est_delivery_max: 45,
        });
      } catch (err) {
        // Seller is already created and held in state, so resubmitting will
        // skip step 1 and retry only the restaurant creation.
        setError(`Step 2 (create restaurant) failed: ${(err as Error).message}`);
        return;
      }

      router.push("/admin/restaurants");
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="max-w-3xl">
      <h1 className="text-3xl font-bold mb-2">New Restaurant</h1>
      <p className="text-neutral-400 mb-8">
        Creates a seller account + restaurant in one step. Share the seller login with the operator.
      </p>

      <form onSubmit={onSubmit} className="space-y-8">
        <Section title="Seller Account">
          <Field label="Email" value={sellerEmail} onChange={setSellerEmail} type="email" required />
          <Field label="Temporary password" value={sellerPassword} onChange={setSellerPassword} type="password" required />
          <Field label="First name" value={sellerFirstName} onChange={setSellerFirstName} required />
          <Field label="Last name" value={sellerLastName} onChange={setSellerLastName} />
          <Field label="Phone" value={sellerPhone} onChange={setSellerPhone} type="tel" required />
        </Section>

        <Section title="Restaurant Info">
          <Field label="Name" value={name} onChange={setName} required />
          <TextArea label="Description" value={description} onChange={setDescription} />
          <Field label="Cover image URL" value={imageUrl} onChange={setImageUrl} placeholder="https://…" />
          <Field label="Phone" value={phone} onChange={setPhone} type="tel" />
          <Field label="Email" value={email} onChange={setEmail} type="email" />
        </Section>

        <Section title="Address">
          <Field label="Street" value={street} onChange={setStreet} required />
          <div className="grid grid-cols-3 gap-3">
            <Field label="City" value={city} onChange={setCity} required />
            <Field label="State" value={state} onChange={setState} required />
            <Field label="Zip" value={zip} onChange={setZip} required />
          </div>
          <div className="grid grid-cols-2 gap-3">
            <Field label="Latitude" value={latitude} onChange={setLatitude} type="number" placeholder="40.7128" required />
            <Field label="Longitude" value={longitude} onChange={setLongitude} type="number" placeholder="-74.0060" required />
          </div>
          <p className="text-xs text-neutral-500">
            Required for distance-based listing. Look up the exact coordinates (e.g. right-click the spot in Google Maps) — don&apos;t leave these at (0, 0).
          </p>
        </Section>

        <Section title="Kashrus">
          <div>
            <label className="block text-sm text-neutral-400 mb-2">Certification</label>
            <select
              value={kosherCert}
              onChange={(e) => setKosherCert(e.target.value)}
              className="w-full bg-neutral-800 border border-neutral-700 rounded-lg px-4 py-3 text-white"
            >
              <option value="OU">OU</option>
              <option value="OK">OK</option>
              <option value="Kof-K">Kof-K</option>
              <option value="Star-K">Star-K</option>
              <option value="cRc">cRc</option>
              <option value="Badatz">Badatz</option>
              <option value="Chof-K">Chof-K</option>
              <option value="other">Other</option>
            </select>
          </div>
          <Checkbox label="Glatt Kosher" checked={isGlatt} onChange={setIsGlatt} />
          <Checkbox label="Cholov Yisroel" checked={isCholovYisroel} onChange={setIsCholovYisroel} />
          <Checkbox label="Pas Yisroel" checked={isPasYisroel} onChange={setIsPasYisroel} />
        </Section>

        <Section title="Delivery">
          <Field label="Cuisine types (comma-separated)" value={cuisine} onChange={setCuisine} placeholder="Israeli, Middle Eastern" />
          <Field label="Delivery fee ($)" value={deliveryFee} onChange={setDeliveryFee} type="text" />
        </Section>

        {error && <div className="text-red-400">{error}</div>}

        <div className="flex gap-3">
          <button
            type="submit"
            disabled={loading}
            className="bg-orange-500 hover:bg-orange-600 text-white font-semibold rounded-lg px-6 py-3 transition disabled:opacity-50"
          >
            {loading ? "Creating…" : "Create Restaurant"}
          </button>
          <button
            type="button"
            onClick={() => router.back()}
            className="text-neutral-400 hover:text-white px-6 py-3"
          >
            Cancel
          </button>
        </div>
      </form>
    </div>
  );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div className="bg-neutral-900 border border-neutral-800 rounded-xl p-6 space-y-4">
      <h2 className="text-lg font-semibold">{title}</h2>
      {children}
    </div>
  );
}

function Field({
  label,
  value,
  onChange,
  type = "text",
  required = false,
  placeholder,
}: {
  label: string;
  value: string;
  onChange: (v: string) => void;
  type?: string;
  required?: boolean;
  placeholder?: string;
}) {
  return (
    <div>
      <label className="block text-sm text-neutral-400 mb-2">{label}</label>
      <input
        type={type}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder={placeholder}
        required={required}
        className="w-full bg-neutral-800 border border-neutral-700 rounded-lg px-4 py-3 text-white focus:border-orange-500 focus:outline-none"
      />
    </div>
  );
}

function TextArea({ label, value, onChange }: { label: string; value: string; onChange: (v: string) => void }) {
  return (
    <div>
      <label className="block text-sm text-neutral-400 mb-2">{label}</label>
      <textarea
        value={value}
        onChange={(e) => onChange(e.target.value)}
        rows={3}
        className="w-full bg-neutral-800 border border-neutral-700 rounded-lg px-4 py-3 text-white focus:border-orange-500 focus:outline-none"
      />
    </div>
  );
}

function Checkbox({ label, checked, onChange }: { label: string; checked: boolean; onChange: (v: boolean) => void }) {
  return (
    <label className="flex items-center gap-2 cursor-pointer">
      <input
        type="checkbox"
        checked={checked}
        onChange={(e) => onChange(e.target.checked)}
        className="w-4 h-4 rounded border-neutral-700 bg-neutral-800 text-orange-500 focus:ring-orange-500"
      />
      <span className="text-sm text-neutral-300">{label}</span>
    </label>
  );
}
