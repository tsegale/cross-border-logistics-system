# 9. Shipment state machine, out-of-order tracking events, and optimistic locking

Date: 2026-11-08 · Status: Accepted

## Context
Shipments on the Walvis Bay (`NAWVB`) to Hamburg (`DEHAM`) corridor pass through ocean and air carriers, port terminals, and customs authorities in multiple time zones (`Africa/Windhoek`, `Africa/Johannesburg`, `Europe/Berlin`). Three engineering challenges arise:
1. **Unambiguous tracking numbers**: Operators read tracking numbers over phone or radio and type them into portals; characters like `I`/`1`/`L` and `O`/`0` are easily confused, and single-key typos should be caught before hitting the database.
2. **Late and out-of-order EDI/webhook events**: A weighbridge or gate-in scan at Walvis Bay (`AT_ORIGIN_PORT`) often arrives via batch EDI hours after the vessel departure event (`IN_TRANSIT`) was already recorded. Naive event processing would move the shipment status backwards from `IN_TRANSIT` to `AT_ORIGIN_PORT`, or duplicate events when a carrier retries a webhook.
3. **Concurrent operator updates**: Two operators updating or cancelling the same shipment can overwrite each other's state transitions unless updates are guarded by optimistic concurrency control.

## Decision
1. **Crockford Base32 tracking numbers with ISO 7064 MOD 37-2 check character (`TrackingNumber`)**:
   - Format: `FF-<8 Crockford Base32 chars>-<1 check char>` (e.g., `FF-00100000-G`).
   - Uses Crockford's 32-character alphabet (`0123456789ABCDEFGHJKMNPQRSTVWXYZ`), which excludes `I`, `L`, `O`, and `U` and normalizes lowercase + `i/l -> 1`, `o -> 0` on input.
   - The check character is computed via ISO/IEC 7064 MOD 37-2 polynomial weights so 100% of single-character substitutions and 100% of adjacent transpositions are rejected with `400 invalid-tracking-number`.
2. **Explicit enum state machine (`ShipmentStatus`)**:
   - Modelled as a self-contained Java enum with an explicit `allowedNextStatuses()` transition table (not Spring Statemachine):
     `AWAITING_PAYMENT -> BOOKED -> PICKED_UP -> AT_ORIGIN_PORT -> IN_TRANSIT -> AT_DESTINATION_PORT -> CUSTOMS_HOLD <-> CUSTOMS_CLEARED -> OUT_FOR_DELIVERY -> DELIVERED`.
   - `CANCELLED` is allowed only before cargo is picked up (`AWAITING_PAYMENT` or `BOOKED`). `EXCEPTION` is reachable from any active state.
   - Any illegal transition throws `409 invalid-shipment-state-transition` naming `currentStatus`, `requestedStatus`, and `allowedNextStatuses`.
3. **Out-of-order and duplicate-safe tracking events (`Shipment.applyTrackingEvent`)**:
   - `shipments` tracks `latest_event_at` (`TIMESTAMPTZ`).
   - When `POST /api/v1/shipments/{id}/tracking-events` arrives:
     - If `carrier_event_id` is present and `(shipment_id, carrier_event_id)` already exists in `tracking_events` (enforced by partial unique index `uq_tracking_events_carrier_event`), the existing event is returned (`200 OK`, `duplicateIgnored = true`) without re-applying the transition.
     - If `occurredAt >= latestEventAt` (or `latestEventAt IS NULL`), the state transition is validated (`status.assertCanTransitionTo(targetStatus)`), `shipments.status` and `latest_event_at` advance, and the `TrackingEvent` is stored with `applied_to_status = true`.
     - If `occurredAt < latestEventAt`, the event is appended to `tracking_events` with `applied_to_status = false` and `shipments.status` is **never moved backwards**.
4. **Optimistic locking with `@Version` and HTTP `ETag` / `If-Match`**:
   - `shipments.version` is a JPA `@Version` column exposed as a quoted HTTP `ETag` (`"0"`, `"1"`, ...) on `POST /api/v1/shipments`, `GET /api/v1/shipments/{id}`, `POST /api/v1/shipments/{id}/tracking-events`, and `POST /api/v1/shipments/{id}/cancellation`.
   - Mutation endpoints accept `If-Match` and reject stale versions with `412 precondition-failed`.
5. **Public tracking view (`GET /api/v1/shipments/track/{trackingNumber}`)**:
   - Omits all customer PII and commercial fields (`shipper`, `consignee`, `payerAccountNumber`, `declaredValue`, `quotedTotalAmount`) and converts every event's `occurredAt` into both UTC (`occurredAtUtc`) and the event location's IANA time zone (`occurredAtLocal`, e.g., `Africa/Windhoek` `+02:00` and `Europe/Berlin` `+01:00`/`+02:00`).

### Index Verification (`EXPLAIN (ANALYZE, BUFFERS)` on `20,000` `shipments` rows)

```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT s.id, s.tracking_number, s.status, s.created_at
FROM shipments s
WHERE s.status = 'IN_TRANSIT'
  AND (s.created_at, s.id) < (TIMESTAMPTZ '2026-01-01 02:30:00Z', '01980000-2328-7000-8000-000000002328'::uuid)
ORDER BY s.created_at DESC, s.id DESC
LIMIT 21;
```

```text
 Limit  (cost=0.29..16.84 rows=21 width=50) (actual time=0.050..0.072 rows=21 loops=1)
   Buffers: shared hit=7
   ->  Index Scan using idx_shipments_created_id_desc on shipments s  (cost=0.29..2364.64 rows=2999 width=50) (actual time=0.049..0.069 rows=21 loops=1)
         Index Cond: (ROW(created_at, id) < ROW('2026-01-01 02:30:00+00'::timestamp with time zone, '01980000-2328-7000-8000-000000002328'::uuid))
         Filter: ((status)::text = 'IN_TRANSIT'::text)
         Rows Removed by Filter: 42
         Buffers: shared hit=7
 Planning:
   Buffers: shared hit=60
 Planning Time: 1.089 ms
 Execution Time: 0.163 ms
```

## Consequences
- Late carrier scans are preserved in the chronological tracking timeline (`appliedToStatus = false`) for auditability without corrupting current shipment state.
- Keyset pagination over `shipments` executes in `0.163 ms` (`7` buffer hits) at 20,000 shipments.
