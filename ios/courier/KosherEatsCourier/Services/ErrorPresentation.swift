import Foundation

// Error-presentation utilities shared across courier ViewModels. Centralizes
// the rule that we never surface URLSession.cancelled / Task-cancellation
// errors as user-facing toasts — those are operational noise from view
// tear-down racing with in-flight requests, not actionable failures.
//
// Usage in a VM catch block:
//
//     } catch {
//         if let msg = userFacingMessage(for: error) {
//             errorMessage = msg
//         }
//     }
//
// `userFacingMessage` returns nil for cancellation noise, so the caller can
// just no-op the assignment and the user sees nothing about it.

/// Returns true when `error` represents a Task or URLSession cancellation.
/// SwiftUI tears down view-scoped Tasks whenever the hosting view disappears
/// (e.g. navigating from AvailableDeliveries to LiveDelivery after a claim);
/// any in-flight URLSession request gets cancelled with URLError.cancelled.
/// Surfacing that as "Action Failed: cancelled" confused testers because the
/// action they kicked off had already succeeded — they just moved on.
func isUserCancellationNoise(_ error: Error) -> Bool {
    if (error as? CancellationError) != nil { return true }
    if let urlErr = error as? URLError, urlErr.code == .cancelled { return true }
    if let api = error as? APIError, case .networkError(let inner) = api,
       let urlErr = inner as? URLError, urlErr.code == .cancelled {
        return true
    }
    return false
}

/// Returns the message to assign to a VM's errorMessage, or nil if the error
/// is just cancellation noise that shouldn't be shown to the user.
func userFacingMessage(for error: Error) -> String? {
    if isUserCancellationNoise(error) { return nil }
    if let localized = (error as? LocalizedError)?.errorDescription { return localized }
    return error.localizedDescription
}
