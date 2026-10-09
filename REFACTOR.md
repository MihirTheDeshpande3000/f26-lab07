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

List every design pattern you can name in that package. For each one, the class
or classes that carry it.

### The problem each one solves

For each pattern you listed, what would have to be true about the requirements
for that pattern to be the right call? One sentence each, not in terms of
"flexibility".

### Which of those problems exist here

For each pattern, does the problem it solves exist in this codebase? Point at
the code that settles it.

### The simpler structure

**Your proposal.** What replaces `notify/`. Sketch the classes and the one
method that matters.

**What stays the same.** The tested behavior it must still produce, named
precisely enough that a reader can check it against the shipped tests.

**What you would keep, if anything.** If you would keep one interface, say
which and why. "None of it" is a fine answer if you can defend it.

### What would bring each layer back

For at least two of the layers you would remove, what requirement, if it
arrived next sprint, would make that layer the right structure? Be specific
about the requirement, not about the pattern.

**Misuse or anti-pattern?** Say which this is and why the distinction matters.

---

## Milestone 3: The missing pattern

Read `pricing/`. Not coded, one sentence.

**The pattern.** Which one fits `PriceCalculator`, and the problem that makes
it fit. Name the problem.

**Would you apply it today?** Yes or no, one line, with the reason.
