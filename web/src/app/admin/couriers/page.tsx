"use client";

import { useEffect, useState } from "react";
import { adminApi, AdminCourier, AdminCourierDetail } from "@/lib/adminApi";

/**
 * Courier approval queue. Shows all couriers sorted by how urgent their
 * review is (pending_background at the top). Clicking a row opens a detail
 * modal with the uploaded document photos so the admin can actually verify
 * the courier before approving. Approve/Reject lives inside the modal.
 */
export default function CouriersPage() {
  const [couriers, setCouriers] = useState<AdminCourier[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);

  async function load() {
    try {
      const data = await adminApi.couriers();
      setCouriers(data);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    load();
  }, []);

  if (loading) return <div className="text-dark-500">Loading couriers…</div>;
  if (error) return <div className="text-danger-400">Failed: {error}</div>;

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-3xl font-bold">Couriers</h1>
        <p className="text-dark-400 mt-1">
          {couriers.length} total •{" "}
          {couriers.filter((c) => c.onboarding_status !== "approved" && c.onboarding_status !== "rejected").length}{" "}
          pending review
        </p>
        <p className="text-xs text-dark-500 mt-2">Click a row to review documents and approve or reject.</p>
      </div>

      <div className="bg-dark-900 border border-dark-800 rounded-xl overflow-hidden">
        <table className="w-full">
          <thead className="bg-dark-800/50">
            <tr>
              <th className="text-left px-4 py-3 text-xs text-dark-400 uppercase">Name</th>
              <th className="text-left px-4 py-3 text-xs text-dark-400 uppercase">Contact</th>
              <th className="text-left px-4 py-3 text-xs text-dark-400 uppercase">Vehicle</th>
              <th className="text-left px-4 py-3 text-xs text-dark-400 uppercase">Status</th>
              <th className="text-left px-4 py-3 text-xs text-dark-400 uppercase">Deliveries</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-dark-800">
            {couriers.map((c) => (
              <tr
                key={c.id}
                onClick={() => setSelectedId(c.id)}
                className="hover:bg-dark-800/50 transition cursor-pointer"
              >
                <td className="px-4 py-3">
                  <div className="font-medium">{c.first_name} {c.last_name}</div>
                  {c.is_online && (
                    <div className="text-xs text-success-400 mt-0.5">● Online</div>
                  )}
                </td>
                <td className="px-4 py-3">
                  <div className="text-sm">{c.email}</div>
                  <div className="text-xs text-dark-500">{c.phone}</div>
                </td>
                <td className="px-4 py-3 text-sm">
                  {c.vehicle_make ? `${c.vehicle_make} ${c.vehicle_model}` : c.vehicle_type || "—"}
                  {c.license_plate && <div className="text-xs text-dark-500">{c.license_plate}</div>}
                </td>
                <td className="px-4 py-3">
                  <StatusPill status={c.onboarding_status} />
                </td>
                <td className="px-4 py-3 text-sm">
                  {c.total_deliveries}
                  {c.rating > 0 && <span className="text-warning-400 ml-2">★ {c.rating.toFixed(1)}</span>}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {selectedId && (
        <CourierReviewModal
          courierId={selectedId}
          onClose={() => setSelectedId(null)}
          onDecision={async () => {
            setSelectedId(null);
            await load();
          }}
        />
      )}
    </div>
  );
}

function StatusPill({ status }: { status: AdminCourier["onboarding_status"] }) {
  const config: Record<AdminCourier["onboarding_status"], { label: string; cls: string }> = {
    approved: { label: "Approved", cls: "bg-success-500/20 text-success-400" },
    rejected: { label: "Rejected", cls: "bg-danger-500/20 text-danger-400" },
    suspended: { label: "Suspended", cls: "bg-brand-500/20 text-brand-400" },
    pending_info: { label: "Pending info", cls: "bg-dark-500/20 text-dark-300" },
    pending_documents: { label: "Pending docs", cls: "bg-warning-500/20 text-warning-400" },
    pending_background: { label: "Background check", cls: "bg-warning-500/30 text-warning-300" },
  };
  const c = config[status];
  return <span className={`text-xs px-2 py-1 rounded font-medium ${c.cls}`}>{c.label}</span>;
}

/**
 * Detail modal. Fetches the full profile (including document URLs) on open
 * so list payloads stay lean. Renders the four document photos side by side
 * with links to open each at full resolution.
 */
function CourierReviewModal({
  courierId,
  onClose,
  onDecision,
}: {
  courierId: string;
  onClose: () => void;
  onDecision: () => void;
}) {
  const [detail, setDetail] = useState<AdminCourierDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  useEffect(() => {
    adminApi
      .courierDetail(courierId)
      .then(setDetail)
      .catch((e) => setErr((e as Error).message))
      .finally(() => setLoading(false));
  }, [courierId]);

  async function approve() {
    setBusy(true);
    try {
      await adminApi.approveCourier(courierId);
      onDecision();
    } catch (e) {
      alert((e as Error).message);
      setBusy(false);
    }
  }

  async function reject() {
    if (!confirm("Reject this courier? They will not be able to claim deliveries.")) return;
    setBusy(true);
    try {
      await adminApi.rejectCourier(courierId);
      onDecision();
    } catch (e) {
      alert((e as Error).message);
      setBusy(false);
    }
  }

  const isTerminal = detail && (detail.onboarding_status === "approved" || detail.onboarding_status === "rejected");

  return (
    <div
      className="fixed inset-0 bg-black/70 backdrop-blur-sm flex items-start justify-center overflow-y-auto z-50 p-6"
      onClick={onClose}
    >
      <div
        className="bg-dark-900 border border-dark-800 rounded-xl w-full max-w-4xl my-6"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-start justify-between p-6 border-b border-dark-800">
          <div>
            <h2 className="text-2xl font-bold">
              {detail ? `${detail.first_name} ${detail.last_name}` : "Loading…"}
            </h2>
            {detail && (
              <div className="mt-2 flex items-center gap-2">
                <StatusPill status={detail.onboarding_status} />
                {detail.phone_verified && <span className="text-xs text-success-400">✓ Phone verified</span>}
              </div>
            )}
          </div>
          <button
            onClick={onClose}
            className="text-dark-400 hover:text-white text-2xl leading-none"
            aria-label="Close"
          >
            ×
          </button>
        </div>

        {loading && <div className="p-10 text-center text-dark-500">Loading courier…</div>}
        {err && <div className="p-10 text-center text-danger-400">{err}</div>}

        {detail && (
          <div className="p-6 space-y-6">
            <section>
              <h3 className="text-sm font-semibold text-dark-400 uppercase tracking-wide mb-3">Contact</h3>
              <div className="grid grid-cols-2 gap-4 text-sm">
                <InfoItem label="Email" value={detail.email} />
                <InfoItem label="Phone" value={detail.phone} />
              </div>
            </section>

            <section>
              <h3 className="text-sm font-semibold text-dark-400 uppercase tracking-wide mb-3">Vehicle</h3>
              <div className="grid grid-cols-2 gap-4 text-sm">
                <InfoItem label="Type" value={detail.vehicle_type || "—"} />
                {detail.vehicle_make && <InfoItem label="Make / Model" value={`${detail.vehicle_make} ${detail.vehicle_model}`} />}
                {detail.vehicle_year > 0 && <InfoItem label="Year" value={String(detail.vehicle_year)} />}
                {detail.vehicle_color && <InfoItem label="Color" value={detail.vehicle_color} />}
                {detail.license_plate && <InfoItem label="License plate" value={detail.license_plate} />}
                {detail.drivers_license_number && (
                  <InfoItem label="Driver's license #" value={detail.drivers_license_number} />
                )}
              </div>
            </section>

            <section>
              <h3 className="text-sm font-semibold text-dark-400 uppercase tracking-wide mb-3">
                Documents
              </h3>
              <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
                <DocCard label="Driver's license" url={detail.drivers_license_url} />
                <DocCard label="Insurance" url={detail.insurance_url} />
                <DocCard label="Registration" url={detail.vehicle_registration_url} />
                <DocCard label="Selfie" url={detail.profile_photo_url} />
              </div>
            </section>

            <section>
              <h3 className="text-sm font-semibold text-dark-400 uppercase tracking-wide mb-3">
                Background check
              </h3>
              <div className="text-sm text-dark-300">
                Status: <span className="font-mono">{detail.background_check_status}</span>
                {detail.background_check_ref && (
                  <span className="ml-3 text-dark-500">ref: {detail.background_check_ref}</span>
                )}
              </div>
            </section>

            {!isTerminal && (
              <div className="flex gap-3 pt-4 border-t border-dark-800">
                <button
                  onClick={approve}
                  disabled={busy}
                  className="flex-1 bg-success-500/20 text-success-400 hover:bg-success-500/30 font-medium px-4 py-3 rounded-lg transition disabled:opacity-50"
                >
                  Approve courier
                </button>
                <button
                  onClick={reject}
                  disabled={busy}
                  className="flex-1 bg-danger-500/20 text-danger-400 hover:bg-danger-500/30 font-medium px-4 py-3 rounded-lg transition disabled:opacity-50"
                >
                  Reject
                </button>
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  );
}

function InfoItem({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <div className="text-xs text-dark-500 uppercase tracking-wide">{label}</div>
      <div className="text-dark-200 mt-1">{value}</div>
    </div>
  );
}

/**
 * Thumbnail for a single uploaded document. Empty URLs show a "not uploaded"
 * placeholder; uploaded ones render the image and link to the full-size
 * version in a new tab so the admin can zoom in.
 */
function DocCard({ label, url }: { label: string; url: string }) {
  if (!url) {
    return (
      <div className="bg-dark-800/50 border border-dashed border-dark-700 rounded-lg aspect-[4/3] flex flex-col items-center justify-center text-center p-3">
        <div className="text-xs text-dark-500 uppercase tracking-wide">{label}</div>
        <div className="text-xs text-dark-600 mt-2">Not uploaded</div>
      </div>
    );
  }
  return (
    <a
      href={url}
      target="_blank"
      rel="noopener noreferrer"
      className="group block bg-dark-800 border border-dark-700 rounded-lg overflow-hidden hover:border-brand-500/60 transition"
    >
      <div className="aspect-[4/3] bg-dark-950 relative">
        {/* eslint-disable-next-line @next/next/no-img-element */}
        <img src={url} alt={label} className="w-full h-full object-cover" />
      </div>
      <div className="px-3 py-2 text-xs text-dark-300 group-hover:text-white transition flex items-center justify-between">
        <span>{label}</span>
        <span className="text-dark-500 group-hover:text-brand-400">↗</span>
      </div>
    </a>
  );
}
