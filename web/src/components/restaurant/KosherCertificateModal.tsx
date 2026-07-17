"use client";

import { hasRealCertificatePhoto } from "@/lib/kosher";
import { AlertTriangle, FileQuestion, X, ZoomIn, ZoomOut } from "lucide-react";
import { useEffect, useState } from "react";

// Zoom stops for the certificate image. Width-based zoom (rather than a CSS
// transform) so the scroll container grows with the image and panning works
// with native scrolling.
const ZOOM_LEVELS = [1, 1.5, 2, 3];

// KosherCertificateModal is the web port of the iOS KosherCertificateSheet:
// a full-screen viewer for the restaurant's kosher certificate photo. The
// parent controls visibility — render it only while open, pass onClose to
// dismiss. Handles three degraded states gracefully: no photo on file,
// image still loading, and image failed to load.
export function KosherCertificateModal({
  url,
  restaurantName,
  onClose,
}: {
  url?: string | null;
  restaurantName: string;
  onClose: () => void;
}) {
  const [zoomIndex, setZoomIndex] = useState(0);
  const [loaded, setLoaded] = useState(false);
  const [loadFailed, setLoadFailed] = useState(false);

  // Only a real uploaded photo renders as the certificate image; a
  // placeholder/stock-image host degrades to the "on file" state instead of
  // presenting a fake certificate (defense-in-depth — the opener is already
  // gated on the same check). See @/lib/kosher.
  const hasPhoto = hasRealCertificatePhoto(url);
  const zoom = ZOOM_LEVELS[zoomIndex];

  // Escape closes; lock body scroll while the modal is up.
  useEffect(() => {
    function onKeyDown(e: KeyboardEvent) {
      if (e.key === "Escape") onClose();
    }
    document.addEventListener("keydown", onKeyDown);
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => {
      document.removeEventListener("keydown", onKeyDown);
      document.body.style.overflow = previousOverflow;
    };
  }, [onClose]);

  const canZoomIn = hasPhoto && loaded && !loadFailed && zoomIndex < ZOOM_LEVELS.length - 1;
  const canZoomOut = hasPhoto && loaded && !loadFailed && zoomIndex > 0;

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-label={`Kosher certificate for ${restaurantName}`}
      className="fixed inset-0 z-[60] bg-dark-950/95 flex flex-col"
      onClick={onClose}
    >
      {/* Toolbar */}
      <div
        className="flex items-center justify-between gap-3 px-4 py-3 border-b border-dark-800 bg-dark-900"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="min-w-0">
          <h2 className="font-bold truncate">Kosher Certificate</h2>
          <p className="text-dark-400 text-sm truncate">{restaurantName}</p>
        </div>
        <div className="flex items-center gap-2 flex-shrink-0">
          <button
            onClick={() => setZoomIndex((i) => Math.max(i - 1, 0))}
            disabled={!canZoomOut}
            aria-label="Zoom out"
            className="w-11 h-11 rounded-xl bg-dark-800 hover:bg-dark-700 disabled:opacity-40 disabled:hover:bg-dark-800 flex items-center justify-center transition-colors"
          >
            <ZoomOut className="w-5 h-5" aria-hidden="true" />
          </button>
          <button
            onClick={() => setZoomIndex((i) => Math.min(i + 1, ZOOM_LEVELS.length - 1))}
            disabled={!canZoomIn}
            aria-label="Zoom in"
            className="w-11 h-11 rounded-xl bg-dark-800 hover:bg-dark-700 disabled:opacity-40 disabled:hover:bg-dark-800 flex items-center justify-center transition-colors"
          >
            <ZoomIn className="w-5 h-5" aria-hidden="true" />
          </button>
          <button
            onClick={onClose}
            aria-label="Close certificate view"
            className="w-11 h-11 rounded-xl bg-dark-800 hover:bg-dark-700 flex items-center justify-center transition-colors"
          >
            <X className="w-5 h-5" aria-hidden="true" />
          </button>
        </div>
      </div>

      {/* Body — scrolls in both axes when zoomed past the viewport. */}
      <div
        className="flex-1 overflow-auto"
        onClick={(e) => e.stopPropagation()}
      >
        {!hasPhoto ? (
          <StateMessage
            icon={<FileQuestion className="w-10 h-10 text-dark-500" aria-hidden="true" />}
            title="Certificate on file with KosherEats"
            body={`${restaurantName} hasn't uploaded a photo of their kosher certificate yet. You can ask the restaurant or the certifying agency directly to verify.`}
          />
        ) : loadFailed ? (
          <StateMessage
            icon={<AlertTriangle className="w-10 h-10 text-dark-500" aria-hidden="true" />}
            title="Unable to load certificate"
            body={`The certificate image for ${restaurantName} could not be loaded. Please try again later.`}
          />
        ) : (
          <div className="relative p-4 pb-[calc(1rem+env(safe-area-inset-bottom))] min-h-full flex items-start justify-center">
            {!loaded && (
              <div className="absolute inset-0 flex items-center justify-center text-dark-400">
                Loading certificate…
              </div>
            )}
            {/* Plain <img> (not next/image): the natural-size width-zoom
                pattern needs an unconstrained element, matching the seller
                photo previews elsewhere in the app. */}
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img
              src={url ?? undefined}
              alt={`Kosher certificate for ${restaurantName}`}
              onLoad={() => setLoaded(true)}
              onError={() => setLoadFailed(true)}
              onClick={() => setZoomIndex((i) => (i === 0 ? 2 : 0))}
              className={`rounded-xl max-w-none transition-opacity ${
                loaded ? "opacity-100" : "opacity-0"
              } ${zoomIndex > 0 ? "cursor-zoom-out" : "cursor-zoom-in"}`}
              style={{ width: `${zoom * 100}%` }}
            />
          </div>
        )}
      </div>
    </div>
  );
}

function StateMessage({
  icon,
  title,
  body,
}: {
  icon: React.ReactNode;
  title: string;
  body: string;
}) {
  return (
    <div className="min-h-full flex flex-col items-center justify-center text-center px-8 py-16 gap-3">
      {icon}
      <h3 className="font-semibold text-dark-200">{title}</h3>
      <p className="text-dark-400 text-sm max-w-md">{body}</p>
    </div>
  );
}
