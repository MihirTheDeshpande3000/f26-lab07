package edu.cmu.cs214.scheduling.workflow;

import edu.cmu.cs214.scheduling.domain.Booking;
import edu.cmu.cs214.scheduling.domain.BookingOutcome;
import edu.cmu.cs214.scheduling.domain.BookingRequest;
import edu.cmu.cs214.scheduling.domain.BookingStore;
import edu.cmu.cs214.scheduling.domain.BookingType;
import edu.cmu.cs214.scheduling.domain.Member;
import edu.cmu.cs214.scheduling.domain.MembershipTier;
import edu.cmu.cs214.scheduling.domain.Room;
import edu.cmu.cs214.scheduling.notify.NotificationHub;
import edu.cmu.cs214.scheduling.pricing.PriceCalculator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BookingWorkflowTest {

    private static final LocalDateTime MON_9AM = LocalDateTime.of(2026, 10, 5, 9, 0);
    private static final LocalDateTime MON_10AM = LocalDateTime.of(2026, 10, 5, 10, 0);
    private static final LocalDateTime MON_11AM = LocalDateTime.of(2026, 10, 5, 11, 0);

    private BookingStore store;
    private PriceCalculator calculator;
    private NotificationHub hub;
    private BookingWorkflow workflow;

    @BeforeEach
    void setUp() {
        store = new BookingStore();
        store.addRoom(new Room("W-101", "Willow Room", 8));
        store.addRoom(new Room("C-200", "Cedar Hall", 20));
        store.addMember(new Member("m-1", "Ada", "ada@rooms.example.edu", MembershipTier.BASIC));
        store.addMember(new Member("m-2", "Grace", "grace@rooms.example.edu",
                MembershipTier.PREMIER));
        calculator = new PriceCalculator();
        hub = new NotificationHub();
        workflow = new BookingWorkflow(store, calculator, hub);
    }

    @Test
    void regularSubmitStoresAndNotifies() {
        BookingOutcome outcome = workflow.submit(
                BookingRequest.regular("W-101", "m-1", MON_9AM, MON_10AM, 4));

        assertTrue(outcome.isAccepted());
        assertEquals(1, outcome.getBooked().size());
        assertEquals(BookingType.REGULAR, outcome.getBooking().getType());
        assertEquals(1, store.activeInRoom("W-101").size());
        assertEquals(1, hub.getOutbox().size());
    }

    @Test
    void regularSubmitRejectsAnOverlappingSlot() {
        workflow.submit(BookingRequest.regular("W-101", "m-1", MON_9AM, MON_10AM, 4));

        BookingOutcome outcome = workflow.submit(BookingRequest.regular("W-101", "m-2",
                LocalDateTime.of(2026, 10, 5, 9, 30), MON_11AM, 2));

        assertFalse(outcome.isAccepted());
        assertEquals(1, store.activeInRoom("W-101").size());
        assertEquals(1, hub.getOutbox().size());
    }

    @Test
    void regularSubmitAcceptsASlotThatStartsWhenAnotherEnds() {
        workflow.submit(BookingRequest.regular("W-101", "m-1", MON_9AM, MON_10AM, 4));

        BookingOutcome outcome = workflow.submit(
                BookingRequest.regular("W-101", "m-2", MON_10AM, MON_11AM, 2));

        assertTrue(outcome.isAccepted());
        assertEquals(2, store.activeInRoom("W-101").size());
    }

    @Test
    void regularSubmitRejectsAPartyLargerThanTheRoom() {
        BookingOutcome outcome = workflow.submit(
                BookingRequest.regular("W-101", "m-1", MON_9AM, MON_10AM, 25));

        assertFalse(outcome.isAccepted());
        assertTrue(store.activeInRoom("W-101").isEmpty());
    }

    @Test
    void regularSubmitRejectsAMemberWhoIsBookedElsewhere() {
        workflow.submit(BookingRequest.regular("W-101", "m-1", MON_9AM, MON_10AM, 4));

        BookingOutcome outcome = workflow.submit(
                BookingRequest.regular("C-200", "m-1", MON_9AM, MON_11AM, 4));

        assertFalse(outcome.isAccepted());
        assertTrue(store.activeInRoom("C-200").isEmpty());
    }

    @Test
    void recurringSubmitBooksEveryWeekOfAnOpenSeries() {
        BookingOutcome outcome = workflow.submit(
                BookingRequest.recurring("C-200", "m-1", MON_9AM, MON_10AM, 4, 6));

        assertTrue(outcome.isAccepted());
        assertEquals(4, outcome.getBooked().size());
        assertEquals(4, store.activeInRoom("C-200").size());
        assertEquals(4, hub.getOutbox().size());
        assertEquals("S-1", outcome.getBooking().getSeriesId());
    }

    @Test
    void blockedSubmitHoldsTheRoom() {
        BookingOutcome outcome = workflow.submit(
                BookingRequest.blocked("W-101", MON_9AM, MON_11AM));

        assertTrue(outcome.isAccepted());
        assertEquals(BookingType.BLOCKED, outcome.getBooking().getType());
        assertEquals(1, hub.getOutbox().size());
    }

    @Test
    void blockedSubmitIsRejectedWhenTheRoomIsTaken() {
        workflow.submit(BookingRequest.regular("W-101", "m-1", MON_9AM, MON_10AM, 4));

        BookingOutcome outcome = workflow.submit(
                BookingRequest.blocked("W-101", MON_9AM, MON_11AM));

        assertFalse(outcome.isAccepted());
        assertEquals(1, store.activeInRoom("W-101").size());
    }

    @Test
    void regularCancelReleasesTheSlotAndNotifies() {
        long id = workflow.submit(BookingRequest.regular("W-101", "m-1", MON_9AM, MON_10AM, 4))
                .getBooking().getId();

        assertTrue(workflow.cancel(id, false));
        assertTrue(store.findBooking(id).isCancelled());
        assertEquals(2, hub.getOutbox().size());
    }

    @Test
    void recurringCancelReleasesTheOccurrence() {
        List<Booking> series = workflow.submit(
                BookingRequest.recurring("C-200", "m-1", MON_9AM, MON_10AM, 3, 6)).getBooked();
        Booking last = series.get(series.size() - 1);

        assertTrue(workflow.cancel(last.getId(), false));
        assertTrue(store.findBooking(last.getId()).isCancelled());
    }

    @Test
    void recurringCancelCancelsTheSelectedOccurrenceAndAllLaterOccurrences() {
        List<Booking> series = workflow.submit(
                BookingRequest.recurring("C-200", "m-1", MON_9AM, MON_10AM, 4, 6)).getBooked();

        assertTrue(workflow.cancel(series.get(1).getId(), false));

        assertFalse(series.get(0).isCancelled());
        assertTrue(series.get(1).isCancelled());
        assertTrue(series.get(2).isCancelled());
        assertTrue(series.get(3).isCancelled());
        assertEquals(7, hub.getOutbox().size());
    }

    @Test
    void blockedCancelNeedsTheAdminFlag() {
        long id = workflow.submit(BookingRequest.blocked("W-101", MON_9AM, MON_11AM))
                .getBooking().getId();

        assertFalse(workflow.cancel(id, false));
        assertFalse(store.findBooking(id).isCancelled());

        assertTrue(workflow.cancel(id, true));
        assertTrue(store.findBooking(id).isCancelled());
    }

    @Test
    void priceOfRegularMatchesTheCalculator() {
        Booking booking = workflow.submit(
                BookingRequest.regular("W-101", "m-2", MON_9AM, MON_10AM, 4)).getBooking();

        double expected = calculator.price(booking, store.findMember("m-2"));

        assertEquals(expected, workflow.priceOf(booking.getId()), 0.001);
    }

    @Test
    void priceOfRecurringSumsTheOccurrences() {
        BookingOutcome outcome = workflow.submit(
                BookingRequest.recurring("C-200", "m-1", MON_9AM, MON_10AM, 3, 6));
        Booking first = outcome.getBooking();

        double one = calculator.price(first, store.findMember("m-1"));

        assertEquals(3 * one, workflow.priceOf(first.getId()), 0.001);
    }

    @Test
    void priceOfBlockedIsZero() {
        long id = workflow.submit(BookingRequest.blocked("W-101", MON_9AM, MON_11AM))
                .getBooking().getId();

        assertEquals(0.0, workflow.priceOf(id), 0.001);
    }

    @Test
    void describeNamesARegularBooking() {
        long id = workflow.submit(BookingRequest.regular("W-101", "m-1", MON_9AM, MON_10AM, 4))
                .getBooking().getId();

        assertEquals("Regular booking #1 in Willow Room from 2026-10-05T09:00 to 2026-10-05T10:00",
                workflow.describe(id));
    }

    @Test
    void describeNamesARecurringOccurrence() {
        long id = workflow.submit(
                BookingRequest.recurring("C-200", "m-1", MON_9AM, MON_10AM, 2, 6))
                .getBooking().getId();

        assertEquals("Recurring booking #1 in Cedar Hall, occurrence 1 of series S-1, "
                + "2026-10-05T09:00 to 2026-10-05T10:00", workflow.describe(id));
    }

    @Test
    void describeNamesABlockedSlot() {
        long id = workflow.submit(BookingRequest.blocked("W-101", MON_9AM, MON_11AM))
                .getBooking().getId();

        assertEquals("Blocked slot #1 in Willow Room from 2026-10-05T09:00 to 2026-10-05T11:00,"
                + " admin hold", workflow.describe(id));
    }

    @Test
    void submitRejectsAnUnknownRoom() {
        BookingOutcome outcome = workflow.submit(
                BookingRequest.regular("X-999", "m-1", MON_9AM, MON_10AM, 2));

        assertFalse(outcome.isAccepted());
        assertEquals(0, hub.getOutbox().size());
    }
}
