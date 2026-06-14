import SwiftUI

// Profile → My Orders entry point. Now that the bottom-nav Cart slot serves
// the in-flight "what's happening right now" view, the historical browse
// surface lives in the profile drawer instead.
//
// Reuses OrdersListView's existing Active/Past segmented view rather than
// forking a new past-only screen — the Active segment is still useful when
// the user navigates here and happens to have an in-flight order they want
// to inspect from the same screen.
struct ProfileOrdersView: View {
    @State private var pendingTrackingOrderId: String?
    @State private var pendingDetailOrderId: String?

    var body: some View {
        // Push OrdersListContent (not OrdersListView) so this joins the enclosing
        // NavigationStack rather than nesting its own. The title/displayMode are
        // passed into the content so they bind to the active stack's nav bar
        // instead of being swallowed by an inner stack's "Orders"/.large.
        OrdersListContent(
            pendingTrackingOrderId: $pendingTrackingOrderId,
            pendingDetailOrderId: $pendingDetailOrderId,
            navigationTitle: "My Orders",
            titleDisplayMode: .inline
        )
        .accessibilityElement(children: .contain)
        .accessibilityLabel("My Orders")
        .accessibilityHint("View your active and past orders")
    }
}
