# Service Layer Plan

Status: **Planned** · Created 2026-08-30

Splitting `UserController` (965 lines, 30 methods) into services.
`SubscriptionService` is already done; the rest is the checklist below.

## The rule

> If a controller method is more than 10–15 lines, or contains database logic,
> conditionals, and exception handling, it is a fat controller.

Only 4 of the current 30 methods are under 15 lines.

| Layer | Job |
|---|---|
| **Controller** | HTTP only — read the request, call one method, return a status |
| **Service** | the real work — rules, calculations, transactions |
| **Repository** | database access, nothing else |

### What stays vs. moves

| Stays in controller | Moves to service |
|---|---|
| `@PostMapping`, `@GetMapping` | repository calls |
| reading `@RequestBody` / `@RequestParam` | calculations |
| `authentication.getName()` | business rules |
| returning `ResponseEntity` / status codes | `@Transactional` |

**A service must never return `ResponseEntity`.** That is the single most important
rule here. Services return plain objects; the controller turns them into HTTP.

Current worst offender: `parseGarminCsv` — 158 lines of Hobbs/airborne math that
returns `ResponseEntity<Map<String,Object>>`. Because of that it cannot be called
from a scheduled job or tested without web types.

### How to find the groups

Look at which repository a method touches. Everything hitting
`flightLogRepository` belongs to one service; everything hitting
`serviceTimelineRepository` to another. That is usually the natural seam.

## The five services

Line numbers are from `UserController.java` as of 2026-08-30 and will shift as
work proceeds.

### 1. UserService  (~45 lines)

| Method | Line | Size |
|---|---|---|
| `updateUserInfo` | 90 | 30 |
| `registerUser` | 73 | 11 |

Uses `userRepository`, `passwordEncoder`.

### 2. DescriptionOptionService  (~90 lines)

| Method | Line | Size |
|---|---|---|
| `addDescriptionOption` | 830 | 30 |
| `deleteOption` | 379 | 27 |
| `cleanupAndLoadDescriptionOptions` | 932 | 22 |
| `saveCustomDescriptionOption` | 954 | 11 |

Uses `descriptionOptionRepository`.

### 3. HoursService  (~155 lines)

| Method | Line | Size |
|---|---|---|
| `updateHours` | 224 | 82 |
| `computeDisplayedHours` | 902 | 30 |
| `computeTimeLeftString` | 806 | 24 |
| `buildDateHoursString` | 793 | 13 |
| `formatHours` | 787 | 6 |

Uses `userRepository`. Holds the floor rule — displayed hours may rise, never fall.

### 4. TimelineService  (~220 lines)

| Method | Line | Size |
|---|---|---|
| `completeMaintenance` | 698 | 69 |
| `updateTimeline` | 306 | 59 |
| `addTimeline` | 143 | 57 |
| `updateOrder` | 406 | 19 |
| `deleteTimeline` | 365 | 14 |

Uses `serviceTimelineRepository`.

### 5. FlightLogService  (~290 lines)

| Method | Line | Size |
|---|---|---|
| `parseGarminCsv` | 516 | 158 |
| `addFlightLog` | 434 | 71 |
| `deleteFlightLog` | 860 | 42 |
| `getFlightLogs` | 425 | 9 |
| `csvValues` | 674 | 11 |

Uses `flightLogRepository`. Biggest payoff — this is where the ADS-B sync work
will also land.

### 6. SubscriptionService — ✅ done

| Method | Size |
|---|---|
| `toggle` | ~15 |

Uses `subscriptionRepository`.

### Leftover helpers

`parseIntOrNull` (767), `parseDoubleOrNull` (772), `normalizeCalendarUnit` (777),
`errorBody` (685).

Put each with whichever service uses it. If two services need the same one, make a
shared `util` class. `errorBody` builds an HTTP response body, so it stays in the
controller.

## What a service looks like

```java
@Service
public class FlightLogService {
    private final FlightLogRepository flightLogRepository;

    public FlightLogService(FlightLogRepository flightLogRepository) {
        this.flightLogRepository = flightLogRepository;
    }

    @Transactional
    public FlightLog addFlightLog(User user, FlightLog newLog) { ... }
}
```

`@Service` instead of `@Controller`, constructor injection, no HTTP anywhere.

The controller then shrinks to:

```java
@PostMapping("/addflightlog")
@ResponseBody
public ResponseEntity<?> addFlightLog(@RequestBody FlightLog newLog, Authentication auth) {
    User user = userRepository.findByUsername(auth.getName());
    return ResponseEntity.ok(flightLogService.addFlightLog(user, newLog));
}
```

## Order to do it in

Smallest first, so the pattern is second nature before the hard ones.

1. `UserService` — 2 methods, practice run
2. `DescriptionOptionService` — 4 small methods
3. `HoursService` — self-contained math
4. `TimelineService` — bigger, more entangled
5. `FlightLogService` — biggest payoff, do it last

**One service at a time, compile after each.** A 965-line rewrite in one pass with
no tests is how working features break.

## Two things not to do

**Don't create a service per method.** `FlightLogService` owns everything about
flight logs — not `AddFlightLogService` plus `DeleteFlightLogService`.

**Skip interfaces for now.** Common advice is `FlightLogService` +
`FlightLogServiceImpl`. That pays off when you need to swap implementations or mock
heavily. With one implementation it is two files doing one file's job. Add it later
if it earns its place.

## Package note

Existing packages are plural (`repositories`, `controllers`) but the new one is
singular (`service`). Harmless, but worth renaming to `services` for consistency
while there is only one class in it.

## Sources

- [Stop Writing Fat Controllers: Follow the Controller-Service-Repository Pattern](https://rameshfadatare.medium.com/stop-writing-fat-controllers-follow-the-controller-service-repository-pattern-fcfb2152f0a1)
- [Service Layer Pattern in Java With Spring Boot](https://foojay.io/today/service-layer-pattern-in-java-with-spring-boot/)
- [Spring Boot Architecture: Controller, Service, Repository](https://rameshfadatare.medium.com/spring-boot-architecture-controller-service-repository-database-architecture-flow-9144084818b0)
- [Spring Boot Layered Architecture](https://www.compilemymind.com/posts/spring-boot-layered-architecture/)
