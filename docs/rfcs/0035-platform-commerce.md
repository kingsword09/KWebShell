# RFC 0035: Explicit platform commerce capability

- Status: Proposed
- Priority: P2
- Owners: platform-specific commerce service
- Depends on: RFC 0003, RFC 0004, RFC 0030
- Electron migration surface: `inAppPurchase`
- Target mapping: macOS-only `REWRITE` initially

## Objective

If a product requires store commerce, publish a separate platform-specific
contract for product discovery, purchase, restoration, receipt verification
input, and transaction completion. Do not imply cross-store equivalence.

## Common KMP contract

Models define provider/store ID, product, localized price facts, transaction
state, signed receipt/reference, restoration, and finish acknowledgement. The
service key names the store and target. Server-side receipt verification remains
application infrastructure.

## Platform provider contract

The first permissible provider is StoreKit for a signed/notarized macOS
application. Windows Store or other providers require separate accepted RFC
revisions and test accounts. Linux has no generic provider.

## Acceptance

1. Store sandbox tests cover product query, success, user cancel, pending,
   failure, restore, duplicate delivery, receipt, and finish semantics.
2. Transactions persist until explicitly acknowledged and survive application
   restart without duplicate entitlement grant.
3. Renderer can request only catalog product IDs declared by application policy
   and cannot finish/forge transactions.
4. Sensitive account/receipt data is redacted and never enters generic bridge
   logs.
5. Migration fixture for Electron `inAppPurchase` documents method/event
   differences and blocks unsupported targets.
6. Package identity, entitlements, and store receipt environment are verified.

## Non-goals

No payment processor, entitlement server, fake Windows/Linux implementation,
hard-coded test products, or promise of store review approval.
