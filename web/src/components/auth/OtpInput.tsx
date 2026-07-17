"use client";

import type { ClipboardEvent, KeyboardEvent } from "react";
import { useEffect, useRef } from "react";

interface OtpInputProps {
  /** Contiguous digit string, at most `length` chars ("", "1", "123456"). */
  value: string;
  onChange: (code: string) => void;
  /** Fires once the final digit lands (typed, pasted, or autofilled). */
  onComplete?: (code: string) => void;
  length?: number;
  disabled?: boolean;
  autoFocus?: boolean;
  /** Paints the red error ring (e.g. after a rejected code). */
  error?: boolean;
}

// Six-box one-time-code entry. The value is modeled as a contiguous digit
// string: box i shows value[i], the first empty box is the cursor. Supports
// paste anywhere (full code replaces the value), iOS keyboard autofill via
// autocomplete="one-time-code" on the first box (which may drop all six
// digits into a single input), and backspace/arrow navigation.
export function OtpInput({
  value,
  onChange,
  onComplete,
  length = 6,
  disabled = false,
  autoFocus = true,
  error = false,
}: OtpInputProps) {
  const inputsRef = useRef<Array<HTMLInputElement | null>>([]);

  useEffect(() => {
    if (autoFocus) inputsRef.current[0]?.focus();
  }, [autoFocus]);

  // Sanitize, propagate, move focus to the first empty box, and report
  // completion when the last digit lands.
  const commit = (raw: string) => {
    const clean = raw.replace(/\D/g, "").slice(0, length);
    onChange(clean);
    inputsRef.current[Math.min(clean.length, length - 1)]?.focus();
    if (clean.length === length) onComplete?.(clean);
  };

  const handleChange = (index: number, raw: string) => {
    if (!raw) {
      // Content removed (e.g. cut) — drop this digit and everything after.
      commit(value.slice(0, index));
      return;
    }
    const clean = raw.replace(/\D/g, "");
    if (!clean) return; // non-digit keystroke — ignore
    if (clean.length >= length) {
      // Keyboard autofill dropped the entire code into one box.
      commit(clean);
      return;
    }
    // Single digit typed (content is selected on focus, so a stray second
    // char means "replace this box" — take the newest digit).
    commit(value.slice(0, index) + clean.slice(-1) + value.slice(index + 1));
  };

  const handleKeyDown = (index: number, e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === "Backspace") {
      e.preventDefault();
      if (!value) return;
      // Clear this box's digit if it has one, otherwise the previous box's.
      const target = value[index] ? index : index - 1;
      if (target < 0) return;
      commit(value.slice(0, target) + value.slice(target + 1));
    } else if (e.key === "ArrowLeft") {
      e.preventDefault();
      inputsRef.current[Math.max(index - 1, 0)]?.focus();
    } else if (e.key === "ArrowRight") {
      e.preventDefault();
      inputsRef.current[Math.min(index + 1, length - 1)]?.focus();
    }
  };

  const handlePaste = (e: ClipboardEvent<HTMLDivElement>) => {
    e.preventDefault();
    const clean = e.clipboardData.getData("text").replace(/\D/g, "");
    if (clean) commit(clean);
  };

  return (
    <div className="flex justify-center gap-2" onPaste={handlePaste}>
      {Array.from({ length }).map((_, i) => (
        <input
          key={i}
          ref={(el) => {
            inputsRef.current[i] = el;
          }}
          type="text"
          inputMode="numeric"
          pattern="[0-9]*"
          autoComplete={i === 0 ? "one-time-code" : "off"}
          value={value[i] ?? ""}
          disabled={disabled}
          aria-label={`Digit ${i + 1} of ${length}`}
          onChange={(e) => handleChange(i, e.target.value)}
          onKeyDown={(e) => handleKeyDown(i, e)}
          onFocus={(e) => e.target.select()}
          className={`w-12 h-14 text-center text-xl font-semibold bg-dark-800 border rounded-xl text-white focus:outline-none focus:ring-1 transition-colors disabled:opacity-50 ${
            error
              ? "border-red-800 focus:border-red-500 focus:ring-red-500"
              : "border-dark-700 focus:border-brand-500 focus:ring-brand-500"
          }`}
        />
      ))}
    </div>
  );
}
