package com.freightflow.logistics.shipment;

import com.freightflow.logistics.common.MoneyRounding;
import com.freightflow.logistics.config.LogisticsProperties;
import com.freightflow.logistics.idempotency.IdempotencyService;
import com.freightflow.logistics.idempotency.IdempotencyService.IdempotentResult;
import com.freightflow.logistics.location.Location;
import com.freightflow.logistics.location.LocationRepository;
import com.freightflow.logistics.location.UnknownLocationException;
import com.freightflow.logistics.quote.FreightQuote;
import com.freightflow.logistics.quote.FreightQuoteRepository;
import com.freightflow.logistics.quote.QuoteNotFoundException;
import com.freightflow.logistics.shipment.dto.CancelShipmentRequest;
import com.freightflow.logistics.shipment.dto.CreateShipmentRequest;
import com.freightflow.logistics.shipment.dto.PartyDto;
import com.freightflow.logistics.shipment.dto.PublicTrackingResponse;
import com.freightflow.logistics.shipment.dto.PublicTrackingResponse.PublicLocationDto;
import com.freightflow.logistics.shipment.dto.PublicTrackingResponse.PublicTrackingEventDto;
import com.freightflow.logistics.shipment.dto.RecordTrackingEventRequest;
import com.freightflow.logistics.shipment.dto.ShipmentPageResponse;
import com.freightflow.logistics.shipment.dto.ShipmentResponse;
import com.freightflow.logistics.shipment.dto.TrackingEventResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ShipmentService {

    private static final Logger log = LoggerFactory.getLogger(ShipmentService.class);
    public static final String IDEMPOTENCY_SCOPE = "shipment";

    private final ShipmentRepository shipments;
    private final TrackingEventRepository trackingEvents;
    private final FreightQuoteRepository quotes;
    private final LocationRepository locations;
    private final IdempotencyService idempotency;
    private final LogisticsProperties properties;
    private final Clock clock;

    public ShipmentService(
            ShipmentRepository shipments,
            TrackingEventRepository trackingEvents,
            FreightQuoteRepository quotes,
            LocationRepository locations,
            IdempotencyService idempotency,
            LogisticsProperties properties,
            Clock clock) {
        this.shipments = shipments;
        this.trackingEvents = trackingEvents;
        this.quotes = quotes;
        this.locations = locations;
        this.idempotency = idempotency;
        this.properties = properties;
        this.clock = clock;
    }

    private record CanonicalCreateShipmentPayload(
            UUID quoteId,
            PartyDto shipper,
            PartyDto consignee,
            String payerAccountNumber,
            String goodsDescription,
            BigDecimal declaredValue,
            String declaredValueCurrency
    ) {
    }

    public IdempotentResult<ShipmentResponse> createShipment(CreateShipmentRequest request, String idempotencyKey) {
        String normalizedAccount = AccountNumberValidator.validate(request.payerAccountNumber());
        String requestedCurrency = request.declaredValueCurrency() != null && !request.declaredValueCurrency().isBlank()
                ? MoneyRounding.validateSupportedCurrency(request.declaredValueCurrency(), properties.normalizedCurrencies()).getCurrencyCode()
                : null;
        if (requestedCurrency != null && MoneyRounding.hasExcessMinorPrecision(request.declaredValue(), requestedCurrency)) {
            throw new IllegalArgumentException(
                    "declaredValue has more than " + MoneyRounding.minorUnits(requestedCurrency)
                            + " decimal places for currency " + requestedCurrency
            );
        }

        CanonicalCreateShipmentPayload payload = new CanonicalCreateShipmentPayload(
                request.quoteId(),
                new PartyDto(
                        request.shipper().name().trim(),
                        request.shipper().address().trim(),
                        request.shipper().contact().trim()
                ),
                new PartyDto(
                        request.consignee().name().trim(),
                        request.consignee().address().trim(),
                        request.consignee().contact().trim()
                ),
                normalizedAccount,
                request.goodsDescription().trim(),
                request.declaredValue().stripTrailingZeros(),
                requestedCurrency
        );

        return idempotency.execute(
                IDEMPOTENCY_SCOPE,
                idempotencyKey,
                payload,
                201,
                ShipmentResponse.class,
                () -> createShipmentInternal(request, normalizedAccount, requestedCurrency)
        );
    }

    private ShipmentResponse createShipmentInternal(
            CreateShipmentRequest request,
            String normalizedAccount,
            String requestedCurrency) {
        FreightQuote quote = quotes.findById(request.quoteId())
                .orElseThrow(() -> new QuoteNotFoundException(request.quoteId()));
        Instant now = clock.instant();
        if (!now.isBefore(quote.getValidUntil())) {
            throw new QuoteExpiredException(quote.getId(), quote.getValidUntil());
        }

        String declaredValueCurrency = requestedCurrency != null ? requestedCurrency : quote.getCurrency();
        if (MoneyRounding.hasExcessMinorPrecision(request.declaredValue(), declaredValueCurrency)) {
            throw new IllegalArgumentException(
                    "declaredValue has more than " + MoneyRounding.minorUnits(declaredValueCurrency)
                            + " decimal places for currency " + declaredValueCurrency
            );
        }

        String trackingNumber = TrackingNumber.generate(shipments::nextTrackingSequence);
        Shipment shipment = Shipment.createFromQuote(
                trackingNumber,
                quote,
                request.shipper(),
                request.consignee(),
                normalizedAccount,
                request.goodsDescription(),
                request.declaredValue(),
                declaredValueCurrency,
                now
        );
        Shipment saved = shipments.saveAndFlush(shipment);
        log.info("Booked shipment {} (trackingNumber={}, quoteId={}, status={})",
                saved.getId(), saved.getTrackingNumber(), saved.getQuoteId(), saved.getStatus());
        return ShipmentResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public ShipmentResponse getShipment(UUID id) {
        Shipment shipment = shipments.findById(id)
                .orElseThrow(() -> new ShipmentNotFoundException(id.toString()));
        return ShipmentResponse.from(shipment);
    }

    @Transactional(readOnly = true)
    public ShipmentPageResponse listShipments(ShipmentStatus status, String rawCursor, int size) {
        if (size < 1 || size > 100) {
            throw new InvalidShipmentCursorException("Page size must be between 1 and 100");
        }
        String statusFilter = status != null ? status.name() : null;
        ShipmentCursor cursor = rawCursor != null && !rawCursor.isBlank()
                ? ShipmentCursor.decode(rawCursor, statusFilter)
                : null;

        Pageable pageable = PageRequest.of(0, size + 1);
        List<Shipment> rows;
        if (status == null) {
            rows = cursor == null
                    ? shipments.findFirstPage(pageable)
                    : shipments.findPageAfter(cursor.createdAt(), cursor.shipmentId(), pageable);
        } else {
            rows = cursor == null
                    ? shipments.findFirstPageByStatus(status, pageable)
                    : shipments.findPageByStatusAfter(status, cursor.createdAt(), cursor.shipmentId(), pageable);
        }

        boolean hasMore = rows.size() > size;
        List<Shipment> pageRows = hasMore ? rows.subList(0, size) : rows;
        List<ShipmentResponse> items = pageRows.stream()
                .map(ShipmentResponse::from)
                .toList();

        String nextCursor = null;
        if (hasMore) {
            Shipment last = pageRows.get(pageRows.size() - 1);
            nextCursor = new ShipmentCursor(statusFilter, last.getCreatedAt(), last.getId()).encode();
        }
        return new ShipmentPageResponse(items, nextCursor);
    }

    @Transactional
    public ShipmentResponse cancelShipment(UUID id, CancelShipmentRequest request, String ifMatchHeader) {
        Shipment shipment = shipments.findById(id)
                .orElseThrow(() -> new ShipmentNotFoundException(id.toString()));
        ETags.assertMatches(ifMatchHeader, shipment.getVersion());

        String reason = request != null ? request.reason() : null;
        shipment.cancel(reason, clock.instant());
        Shipment saved = shipments.saveAndFlush(shipment);
        log.info("Cancelled shipment {} (trackingNumber={}, version={})",
                saved.getId(), saved.getTrackingNumber(), saved.getVersion());
        return ShipmentResponse.from(saved);
    }

    @Transactional
    public TrackingEventResponse recordTrackingEvent(
            UUID shipmentId,
            RecordTrackingEventRequest request,
            String ifMatchHeader) {
        Shipment shipment = shipments.findById(shipmentId)
                .orElseThrow(() -> new ShipmentNotFoundException(shipmentId.toString()));

        String normalizedLoc = request.location().trim().toUpperCase(Locale.ROOT);
        Location location = locations.findById(normalizedLoc)
                .orElseThrow(() -> new UnknownLocationException("location", normalizedLoc));

        String carrierEventId = request.carrierEventId() != null && !request.carrierEventId().isBlank()
                ? request.carrierEventId().trim()
                : null;
        if (carrierEventId != null) {
            Optional<TrackingEvent> existing = trackingEvents.findByShipmentIdAndCarrierEventId(shipmentId, carrierEventId);
            if (existing.isPresent()) {
                log.info("Ignored duplicate tracking event carrierEventId={} on shipment {}", carrierEventId, shipmentId);
                return TrackingEventResponse.from(
                        existing.get(),
                        location,
                        shipment.getStatus(),
                        shipment.getVersion(),
                        true
                );
            }
        }

        ETags.assertMatches(ifMatchHeader, shipment.getVersion());

        Instant now = clock.instant();
        boolean appliedToStatus = shipment.applyTrackingEvent(request.targetStatus(), request.occurredAt(), now);
        Shipment savedShipment = shipments.saveAndFlush(shipment);

        TrackingEvent event = TrackingEvent.create(
                shipmentId,
                carrierEventId,
                request.eventType(),
                request.targetStatus(),
                location.getUnlocode(),
                request.description(),
                request.occurredAt(),
                now,
                appliedToStatus
        );
        TrackingEvent savedEvent = trackingEvents.saveAndFlush(event);
        log.info("Recorded tracking event {} on shipment {} (targetStatus={}, appliedToStatus={}, currentStatus={})",
                savedEvent.getId(), shipmentId, request.targetStatus(), appliedToStatus, savedShipment.getStatus());

        return TrackingEventResponse.from(
                savedEvent,
                location,
                savedShipment.getStatus(),
                savedShipment.getVersion(),
                false
        );
    }

    public record PublicTrackingResult(PublicTrackingResponse body, long version) {
    }

    @Transactional(readOnly = true)
    public PublicTrackingResult trackByTrackingNumber(String rawTrackingNumber) {
        String normalized = TrackingNumber.validate(rawTrackingNumber);
        Shipment shipment = shipments.findByTrackingNumber(normalized)
                .orElseThrow(() -> new ShipmentNotFoundException(normalized));

        Map<String, Location> locationByCode = locations.findAll().stream()
                .collect(Collectors.toMap(Location::getUnlocode, Function.identity()));

        Location origin = locationByCode.get(shipment.getOriginUnlocode());
        Location destination = locationByCode.get(shipment.getDestinationUnlocode());

        List<TrackingEvent> events = trackingEvents.findByShipmentIdOrderByOccurredAtAscIdAsc(shipment.getId());
        List<PublicTrackingEventDto> eventDtos = events.stream()
                .map(ev -> PublicTrackingEventDto.from(ev, locationByCode.get(ev.getLocationUnlocode())))
                .toList();

        PublicTrackingResponse response = new PublicTrackingResponse(
                shipment.getTrackingNumber(),
                PublicLocationDto.from(origin),
                PublicLocationDto.from(destination),
                shipment.getMode(),
                shipment.getStatus(),
                shipment.getLatestEventAt(),
                eventDtos
        );
        return new PublicTrackingResult(response, shipment.getVersion());
    }
}
