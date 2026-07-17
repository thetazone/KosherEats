"use client";

// Shared photo-upload tile for seller pages (deals, settings). Runs the full
// presign -> S3 PUT -> public-URL flow via lib/sellerApi.uploadImage and
// reports the durable URL through onChange. The tile shows the current image
// (tap to replace); `allowRemove` gates the "Remove photo" affordance —
// leave it off for images the backend must never have blanked (e.g. the
// kosher certificate, where PUT /seller/restaurant COALESCEs a "" into a
// real overwrite).

import { useRef, useState } from "react";
import { ImagePlus, Loader2, X } from "lucide-react";
import { uploadImage, type SellerUploadKind } from "@/lib/sellerApi";

export function PhotoUpload({
  label,
  hint,
  kind,
  value,
  onChange,
  aspectClass = "aspect-video",
  allowRemove = true,
  track,
}: {
  label: string;
  hint?: string;
  kind: SellerUploadKind;
  value: string;
  onChange: (url: string) => void;
  aspectClass?: string;
  allowRemove?: boolean;
  /** Optional in-flight counter so parents can block Save mid-upload. */
  track?: { start: () => void; end: () => void };
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
    track?.start();
    try {
      const url = await uploadImage(file, kind);
      onChange(url);
    } catch (err) {
      setUploadError((err as Error).message || "Photo upload failed — please try again.");
    } finally {
      setUploading(false);
      track?.end();
    }
  }

  return (
    <div>
      <span className="block text-sm text-dark-300 mb-1.5">{label}</span>
      {hint && <p className="text-xs text-dark-500 mb-2">{hint}</p>}
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
            <ImagePlus className="w-6 h-6 text-brand-500" aria-hidden="true" />
            <span className="text-xs">Tap to add photo</span>
          </span>
        )}
        {uploading && (
          <span className="absolute inset-0 flex items-center justify-center bg-black/50">
            <Loader2 className="w-6 h-6 animate-spin text-white" aria-hidden="true" />
          </span>
        )}
      </button>
      {value && !uploading && allowRemove && (
        <button
          type="button"
          onClick={() => {
            onChange("");
            setUploadError(null);
          }}
          className="flex items-center gap-1.5 min-h-[44px] text-xs font-medium text-red-400 hover:text-red-300 transition-colors mt-1"
        >
          <X className="w-3.5 h-3.5" aria-hidden="true" />
          Remove photo
        </button>
      )}
      {uploadError && <p className="text-xs text-red-400 mt-2">{uploadError}</p>}
    </div>
  );
}
