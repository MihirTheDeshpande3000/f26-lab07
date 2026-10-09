# RoomScheduler

A room reservation service. Members book rooms one slot at a time or as a
weekly series, facilities staff block rooms for maintenance, and the front desk
reports on what is booked and what it earned.

One Maven module, Java 21, package `edu.cmu.cs214.scheduling`.

## Repo map

Main code under `src/main/java/edu/cmu/cs214/scheduling/`:

- `domain/`
  - `Booking.java`, `BookingType.java`, `TimeSlot.java`: what a reservation is.
  - `Room.java`, `Member.java`, `MembershipTier.java`: who books what, and at
    which tier.
  - `BookingRequest.java`: what a caller hands in.
  - `BookingOutcome.java`: what came back, including anything the scheduler
    passed over.
  - `BookingStore.java`: the in-memory store everything else reads and writes.
- `workflow/`
  - `BookingWorkflow.java`: submit, cancel, price, and describe a booking. Every
    write to the store and every notification goes through this class.
- `notify/`
  - `NotifierFactory.java`, `NotificationStrategy.java` and its implementation:
    how a message gets turned into text.
  - `NotificationHub.java`, `NotificationSubscriber.java`,
    `OutboxSubscriber.java`, `Outbox.java`: how that text gets delivered.
- `pricing/`
  - `PriceCalculator.java`: what a slot costs.
- `reporting/`
  - `ReportService.java`, `OccupancySummary.java`, `RevenueSummary.java`: room
    occupancy, revenue, and a room's schedule for one day.

Tests mirror that layout under `src/test/java/`.

## Build and test

```
mvn -B test
```

From this directory. `SETUP.md` says what green looks like.

## Where things are

- Setup: `SETUP.md`
- `REFACTOR.md`: where your answers go, one section per milestone
- CI: `.github/workflows/ci.yml`, same command as above

See the Lab 7 handout on the course page for the three milestones you show a TA.

ChatGPT Codex 5.1 Sol Light
