# REFACTOR.md

One section per milestone. Fill each one in as you go, in order.

Milestone 1 is written in two sittings, the pin before the refactor and the
rest after. A pin written afterwards is worth nothing, and a TA will ask.

Keep it short and specific. Point at methods, call sites, and test names.

---

## Milestone 1: Direct a refactor, characterization first

### The pin (write this section before you direct the refactor)

**The pin.** In `BookingWorkflowTest.java`,
`recurringCancelCancelsTheSelectedOccurrenceAndAllLaterOccurrences` pins
`BookingWorkflow.cancel`: cancelling the second occurrence of a recurring
series leaves the first occurrence active, cancels the second and every later
occurrence, and publishes one cancellation notification for each occurrence
it cancels.

**Why that one, and does a shipped test already cover it?** This behavior is worth pinning because `cancel` acts on more bookings than the booking ID passed by its caller, so a refactor could easily change the boundary of the cancellation or notify only once. Out of all the tests in `BookingWorkflowTest`, the shipped test `recurringCancelReleasesTheOccurrence` comes closest, but it cancels the last occurrence; therefore, it does not show that cancelling from the middle preserves earlier occurrences while cancelling and notifying for all later ones.

**What a regeneration would do differently here.** A regeneration from only a one-line description of a booking workflow would have to decide again whether cancelling one recurring occurrence affects only that occurrence or the rest of the series. It would probably cancel only the booking whose ID was passed, because the method is named `cancel` and receives a single `bookingId`; the current "this and all future occurrences" policy is not visible in that interface or in a one-line specification.

### The directive

**The refactor and the exact directive.**Replace the conditional with polymorphism. Alright, now via a replace conditional with polymorphism refactor, create a bookingtypehandler interface for each kind of booking handler to use. You should only need to use BookingWorkflow.java to use this. Everything else is unnecessary, as that is where everything that needs to be changed is housed. Everything the agent needs to change is in that file, so there isn't any reason any other files should be edited.

### The result

**The diff and the suite.** git diff d367f48.

[INFO] Tests run: 36, Failures: 0, Errors: 0, Skipped: 0

**What did NOT change: behavior and files.** The methods and constructor of booking workflow are the same, with all the validation, descriptions, prices, notification messages, etc. are all the same. I was able to verify that fiels outside the scope are untouched via codex's own differences printout upon the addition and also via git diff.

**One thing the agent changed that you had to look at twice.** There was nothing major I had to reread. I looked through the new git diff and it seemed pretty clear that the agent kept its changes within the scope of what I wanted it to do by using an interface instead of switch cases.

### The closing explanation

**Refactor or regenerate?** Nah, I do not think it would've been a better call. Sure the code age was very young, but it still had detailed behavior that regeneration could've removed. The sepc is spread across implementation details and tests and the reach is also broad since all booking writes and notifications pass through the workflow. Thus a controlled refactor was better than recreating everything from an incomplete description.

**What would flip your answer.** If the workflow had a complete and authoratitve spec and fully comprehensive tests for all behavior and the implementation was nice and isolated (not requiring changing a bunch of stuff like callers, data, notif behaviors, etc.), then I would choose regeneration.

---

## Milestone 2: The pattern critique

Read `notify/`. It works and the outbox tests pass.

### The patterns present

Strategy: NotificationStrategy is the strategy interface and EmailNotificationStrategy is its concrete implementation. NotificationHub stores a NotificationStrategy and calls strategy.render(message) in publish.

Factory: NotifierFactory.createStrategy() constructs the notification strategy used by NotificationHub.

Observer: NotificationHub is the publisher/subject, NotificationSubscriber is the observer interface, and OutboxSubscriber is a concrete observer. NotificationHub.publish loops through its subscribers and calls onNotification.
s
### The problem each one solves
Strategy: It is useful when the program must choose among multiple interchangeable ways of rendering a notification, such as email, SMS, or another format.

Factory: It is useful when deciding which notification implementation to construct is nontrivial or depends on configuration or runtime information that callers should not have to know.

Observer: It is useful when one notification must be sent to multiple independently changing consumers without the publisher being coupled to each consumer.

### Which of those problems exist here

Strategy: No. There is only one NotificationStrategy implementation, EmailNotificationStrategy. More importantly, NotifierFactory.createStrategy() always returns new EmailNotificationStrategy(), so there is no actual choice of rendering algorithm. NotificationHub also does not receive a strategy from its caller; its constructor always obtains this same one from the factory.

Factory: No. NotifierFactory.createStrategy() contains no creation decision; it is only:

return new EmailNotificationStrategy();

Constructing the renderer directly would currently express exactly the same requirement.

Observer: No, not with the current requirements. NotificationHub maintains a list and exposes subscribe, but its constructor always installs exactly one OutboxSubscriber. There are no production calls elsewhere in the repository that subscribe another consumer. The shipped test hubDeliversToItsOneSubscriber even expects the count to be exactly one. The actual required behavior is that a published notification reaches the outbox, not that an arbitrary set of observers receive it.

### The simpler structure

**Your proposal.** Keep NotificationMessage, Outbox, and a much smaller NotificationHub. Remove NotificationStrategy, EmailNotificationStrategy, NotifierFactory, NotificationSubscriber, and OutboxSubscriber.

The important method would effectively be: a publish method that just appends a string in the required format.

NotificationHub would simply own its Outbox, render the one required format, and append the result.

**What stays the same.** The simplified version must still preserve the behavior tested by publishedMessageLandsInTheOutboxFullyRendered: one call to publish produces exactly one outbox message formatted as To: ... | Subject: ... | .... It must also preserve aConfirmationFromTheWorkflowReachesTheOutbox and the workflow tests that rely on the number of notifications produced, such as regularSubmitStoresAndNotifies, recurringSubmitBooksEveryWeekOfAnOpenSeries, regularCancelReleasesTheSlotAndNotifies, and recurringCancelCancelsTheSelectedOccurrenceAndAllLaterOccurrences.

**What you would keep, if anything.** I would not keep either notification interface today. NotificationMessage is still useful as a data object and Outbox is still useful because the rest of the code and tests inspect sent messages, but there is currently only one renderer and one destination.

### What would bring each layer back

Strategy: I would bring it back if the next sprint required the same notification to support genuinely different selectable rendering/delivery formats, for example email and SMS, with the choice depending on the member's notification preference.

Observer: I would bring it back if one publication had to independently fan out to several consumers, for example writing to the outbox, updating an audit log, and sending a live notification, with those consumers able to be added or removed without changing NotificationHub.publish.

**Misuse or anti-pattern?** I would call this pattern misuse rather than an anti-pattern. Strategy, Observer, Factory, and Singleton are legitimate structures when their corresponding problems exist. Here, most of those problems do not exist yet, so the patterns add indirection without buying needed behavior. Calling the patterns themselves anti-patterns would incorrectly imply that those structures are inherently bad rather than simply unjustified in this codebase.

---

## Milestone 3: The missing pattern

Read `pricing/`. Not coded, one sentence.

**The pattern.** Strategy fits PriceCalculator because pricing could vary independently: if new pricing policies keep arriving, each policy can implement a common PricingPolicy interface instead of requiring edits to the existing pricing logic.

**Would you apply it today?** No, currently the pricing algorithm is fixed, adding it now would just create indirection.
