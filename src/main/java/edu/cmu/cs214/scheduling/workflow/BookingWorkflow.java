package edu.cmu.cs214.scheduling.workflow;

import edu.cmu.cs214.scheduling.domain.Booking;
import edu.cmu.cs214.scheduling.domain.BookingOutcome;
import edu.cmu.cs214.scheduling.domain.BookingRequest;
import edu.cmu.cs214.scheduling.domain.BookingStore;
import edu.cmu.cs214.scheduling.domain.BookingType;
import edu.cmu.cs214.scheduling.domain.Member;
import edu.cmu.cs214.scheduling.domain.Room;
import edu.cmu.cs214.scheduling.domain.TimeSlot;
import edu.cmu.cs214.scheduling.notify.NotificationHub;
import edu.cmu.cs214.scheduling.notify.NotificationMessage;
import edu.cmu.cs214.scheduling.pricing.PriceCalculator;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The front door of the scheduler. Every booking that reaches the store goes
 * through here, and every notification the scheduler sends is published from
 * here.
 */
public class BookingWorkflow {

    private static final String FACILITIES_CONTACT = "facilities@rooms.example.edu";
    private static final int MAX_SERIES_WEEKS = 26;

    private final BookingStore store;
    private final PriceCalculator calculator;
    private final NotificationHub hub;
    private final Map<BookingType, BookingTypeHandler> handlers;

    public BookingWorkflow(BookingStore store, PriceCalculator calculator, NotificationHub hub) {
        if (store == null || calculator == null || hub == null) {
            throw new IllegalArgumentException("workflow collaborators must not be null");
        }
        this.store = store;
        this.calculator = calculator;
        this.hub = hub;
        this.handlers = new EnumMap<>(BookingType.class);
        handlers.put(BookingType.REGULAR, new RegularBookingHandler());
        handlers.put(BookingType.RECURRING, new RecurringBookingHandler());
        handlers.put(BookingType.BLOCKED, new BlockedBookingHandler());
    }

    /**
     * Validates a request, writes what it can, and reports what it did.
     *
     * @return an outcome naming every booking written and every slot passed over
     */
    public BookingOutcome submit(BookingRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        Room room = store.findRoom(request.roomId());
        if (room == null) {
            return BookingOutcome.rejected("unknown room " + request.roomId());
        }

        BookingTypeHandler handler = handlers.get(request.type());
        if (handler == null) {
            return BookingOutcome.rejected("unsupported booking type " + request.type());
        }
        return handler.submit(request, room);
    }

    /**
     * Releases a booking.
     *
     * @param adminOverride set by callers acting with facilities authority
     * @return true when something was released
     */
    public boolean cancel(long bookingId, boolean adminOverride) {
        Booking booking = store.findBooking(bookingId);
        if (booking == null || booking.isCancelled()) {
            return false;
        }
        Room room = store.findRoom(booking.getRoomId());
        String roomName = room == null ? booking.getRoomId() : room.getName();

        BookingTypeHandler handler = handlers.get(booking.getType());
        return handler != null && handler.cancel(booking, adminOverride, roomName);
    }

    /** What the holder owes for a booking, in dollars. */
    public double priceOf(long bookingId) {
        Booking booking = store.findBooking(bookingId);
        if (booking == null) {
            throw new IllegalArgumentException("unknown booking " + bookingId);
        }

        BookingTypeHandler handler = handlers.get(booking.getType());
        return handler == null ? 0.0 : handler.priceOf(booking);
    }

    /** A one-line summary for schedules and confirmation screens. */
    public String describe(long bookingId) {
        Booking booking = store.findBooking(bookingId);
        if (booking == null) {
            return "Unknown booking #" + bookingId;
        }
        Room room = store.findRoom(booking.getRoomId());
        String roomName = room == null ? booking.getRoomId() : room.getName();

        BookingTypeHandler handler = handlers.get(booking.getType());
        return handler == null
                ? "Booking #" + booking.getId() + " in " + roomName
                : handler.describe(booking, roomName);
    }

    private interface BookingTypeHandler {

        BookingOutcome submit(BookingRequest request, Room room);

        boolean cancel(Booking booking, boolean adminOverride, String roomName);

        double priceOf(Booking booking);

        String describe(Booking booking, String roomName);
    }

    private final class RegularBookingHandler implements BookingTypeHandler {

        @Override
        public BookingOutcome submit(BookingRequest request, Room room) {
            Member member = store.findMember(request.memberId());
            if (member == null) {
                return BookingOutcome.rejected("unknown member " + request.memberId());
            }
            if (request.attendees() > room.getCapacity()) {
                return BookingOutcome.rejected("room " + room.getId() + " seats "
                        + room.getCapacity() + ", request wants " + request.attendees());
            }

            TimeSlot slot = request.slot();
            for (Booking existing : store.activeInRoom(room.getId())) {
                if (existing.getStart().compareTo(slot.end()) < 0
                        && slot.start().compareTo(existing.getEnd()) < 0) {
                    return BookingOutcome.rejected("room " + room.getId()
                            + " is already booked at " + slot.start());
                }
            }

            for (Booking held : store.allBookings()) {
                if (held.isCancelled() || !member.getId().equals(held.getMemberId())) {
                    continue;
                }
                if (held.getStart().compareTo(slot.end()) < 0
                        && slot.start().compareTo(held.getEnd()) < 0) {
                    return BookingOutcome.rejected(member.getId()
                            + " already holds a booking at " + slot.start());
                }
            }

            Booking booking = new Booking(store.nextBookingId(), room.getId(), member.getId(),
                    slot, BookingType.REGULAR, null, 0);
            store.save(booking);
            hub.publish(new NotificationMessage(member.getEmail(), "Booking confirmed",
                    "Room " + room.getName() + " from " + slot.start() + " to " + slot.end(),
                    slot.start()));
            return BookingOutcome.confirmed(booking,
                    "booked " + room.getId() + " for " + member.getId());
        }

        @Override
        public boolean cancel(Booking booking, boolean adminOverride, String roomName) {
            Member member = store.findMember(booking.getMemberId());
            booking.cancel();
            hub.publish(new NotificationMessage(recipientFor(member), "Booking cancelled",
                    "Room " + roomName + " on " + booking.getStart() + " is free again",
                    booking.getStart()));
            return true;
        }

        @Override
        public double priceOf(Booking booking) {
            Member member = store.findMember(booking.getMemberId());
            return calculator.price(booking, member);
        }

        @Override
        public String describe(Booking booking, String roomName) {
            return "Regular booking #" + booking.getId() + " in " + roomName
                    + " from " + booking.getStart() + " to " + booking.getEnd();
        }
    }

    private final class RecurringBookingHandler implements BookingTypeHandler {

        @Override
        public BookingOutcome submit(BookingRequest request, Room room) {
            Member member = store.findMember(request.memberId());
            if (member == null) {
                return BookingOutcome.rejected("unknown member " + request.memberId());
            }
            if (request.attendees() > room.getCapacity()) {
                return BookingOutcome.rejected("room " + room.getId() + " seats "
                        + room.getCapacity() + ", request wants " + request.attendees());
            }
            if (request.occurrences() < 1) {
                return BookingOutcome.rejected("a series needs at least one occurrence");
            }
            if (request.occurrences() > MAX_SERIES_WEEKS) {
                return BookingOutcome.rejected("a series runs at most "
                        + MAX_SERIES_WEEKS + " weeks");
            }

            String seriesId = store.nextSeriesId();
            List<Booking> booked = new ArrayList<>();
            List<TimeSlot> skipped = new ArrayList<>();

            for (int week = 0; week < request.occurrences(); week++) {
                TimeSlot slot = request.slot().plusWeeks(week);
                boolean taken = false;
                for (Booking existing : store.activeInRoom(room.getId())) {
                    if (existing.getStart().compareTo(slot.end()) <= 0
                            && slot.start().compareTo(existing.getEnd()) <= 0) {
                        taken = true;
                        break;
                    }
                }
                if (taken) {
                    skipped.add(slot);
                    continue;
                }

                Booking occurrence = new Booking(store.nextBookingId(), room.getId(),
                        member.getId(), slot, BookingType.RECURRING, seriesId, week + 1);
                store.save(occurrence);
                booked.add(occurrence);
                hub.publish(new NotificationMessage(member.getEmail(), "Occurrence confirmed",
                        "Room " + room.getName() + " on " + slot.start().toLocalDate()
                                + " in series " + seriesId, slot.start()));
            }

            return BookingOutcome.series(booked, skipped, "series " + seriesId + ": "
                    + booked.size() + " booked, " + skipped.size() + " skipped");
        }

        @Override
        public boolean cancel(Booking booking, boolean adminOverride, String roomName) {
            Member member = store.findMember(booking.getMemberId());
            for (Booking occurrence : store.seriesOccurrences(booking.getSeriesId())) {
                if (occurrence.isCancelled()
                        || occurrence.getStart().compareTo(booking.getStart()) < 0) {
                    continue;
                }
                occurrence.cancel();
                hub.publish(new NotificationMessage(recipientFor(member),
                        "Occurrence cancelled",
                        "Room " + roomName + " on " + occurrence.getStart().toLocalDate()
                                + " in series " + occurrence.getSeriesId() + " is free again",
                        occurrence.getStart()));
            }
            return true;
        }

        @Override
        public double priceOf(Booking booking) {
            Member member = store.findMember(booking.getMemberId());
            double total = 0.0;
            for (Booking occurrence : store.seriesOccurrences(booking.getSeriesId())) {
                if (!occurrence.isCancelled()) {
                    total += calculator.price(occurrence, member);
                }
            }
            return total;
        }

        @Override
        public String describe(Booking booking, String roomName) {
            return "Recurring booking #" + booking.getId() + " in " + roomName
                    + ", occurrence " + booking.getOccurrenceIndex() + " of series "
                    + booking.getSeriesId() + ", " + booking.getStart()
                    + " to " + booking.getEnd();
        }
    }

    private final class BlockedBookingHandler implements BookingTypeHandler {

        @Override
        public BookingOutcome submit(BookingRequest request, Room room) {
            TimeSlot slot = request.slot();
            if (!slot.start().toLocalDate().equals(slot.end().toLocalDate())) {
                return BookingOutcome.rejected("a block must stay inside one day");
            }

            for (Booking existing : store.activeInRoom(room.getId())) {
                if (existing.getStart().compareTo(slot.end()) < 0
                        && slot.start().compareTo(existing.getEnd()) < 0) {
                    return BookingOutcome.rejected("room " + room.getId()
                            + " cannot be blocked at " + slot.start());
                }
            }

            Booking block = new Booking(store.nextBookingId(), room.getId(), null, slot,
                    BookingType.BLOCKED, null, 0);
            store.save(block);
            hub.publish(new NotificationMessage(FACILITIES_CONTACT, "Room blocked",
                    "Room " + room.getName() + " held from " + slot.start()
                            + " to " + slot.end(), slot.start()));
            return BookingOutcome.confirmed(block, "blocked " + room.getId());
        }

        @Override
        public boolean cancel(Booking booking, boolean adminOverride, String roomName) {
            if (!adminOverride) {
                return false;
            }
            booking.cancel();
            hub.publish(new NotificationMessage(FACILITIES_CONTACT, "Block released",
                    "Room " + roomName + " released from " + booking.getStart()
                            + " to " + booking.getEnd(), booking.getStart()));
            return true;
        }

        @Override
        public double priceOf(Booking booking) {
            return 0.0;
        }

        @Override
        public String describe(Booking booking, String roomName) {
            return "Blocked slot #" + booking.getId() + " in " + roomName
                    + " from " + booking.getStart() + " to " + booking.getEnd()
                    + ", admin hold";
        }
    }

    private static String recipientFor(Member member) {
        return member == null ? FACILITIES_CONTACT : member.getEmail();
    }
}
