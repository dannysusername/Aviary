# @Transactional

Notes on how `@Transactional` works and where it lives in Aviary.

Reference: https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative.html

## What it is

A transaction is a group of database changes that must **all** succeed or **all** be
thrown away. There is no halfway.

`@Transactional` marks a method as one of those groups. You write the business logic;
Spring adds the begin / commit / rollback around it for you.

## The story

Picture the maintenance hangar.

- Your service method is the **mechanic**.
- The database is the **permanent aircraft logbook**.
- The proxy Spring generates is the **shop foreman**, standing at the hangar door.
- The transaction is the **work order**.

The mechanic is never allowed to write in the permanent logbook directly. Here is the flow:

1. The mechanic walks in the door. The foreman stops them and opens a **work order**.
2. The mechanic does the job. Every change they make is written on the work order,
   not the permanent logbook.
3. The mechanic walks back out the door.
   - Walks out saying **"done"** → the foreman files the work order. Everything on it
     is copied into the permanent logbook at once. This is the **commit**.
   - Walks out **throwing a wrench** (an exception) → the foreman shreds the work order.
     Nothing reaches the logbook. This is the **rollback**.

The mechanic never writes commit or rollback logic. They just do the work and walk out.
That is the whole point of the annotation.

Three consequences fall out of this story, and all three are real traps:

**The foreman only sees the door.** If the mechanic makes a mess, quietly cleans it up
themselves, and walks out *smiling*, the foreman has no idea anything went wrong and
files the work order anyway. Half-finished work becomes permanent.
→ This is what happens when you `catch` an exception **inside** a `@Transactional` method.

**A mechanic already inside never passes the door.** If mechanic A is in the hangar and
calls mechanic B directly — without either of them going back out through the door —
the foreman never sees B, so no work order is opened for B's work.
→ This is why calling a `@Transactional` method from another method **in the same class**
does nothing. It is called the self-invocation trap.

**Two mechanics, one work order.** If mechanic A is already working under a work order
and mechanic B comes in through the door, the foreman does not open a second one. B joins
A's existing work order. If B throws a wrench, the *whole shared* work order is marked
for shredding — including A's work.
→ This is the default propagation, `REQUIRED`.

## Where it is in Aviary

14 methods. The annotation lines move as files are edited; the method names are stable.

### Service layer (correct place for it)

| File | Method |
|---|---|
| `services/HoursService.java` | `updateHours` |
| `services/UserService.java` | `registerUser`, `updateUserInfo` |
| `services/TimelineService.java` | `addTimeline` |
| `services/DescriptionOptionService.java` | `saveCustomDescriptionOption`, `addOption`, `deleteOption`, `cleanupAndLoadDescriptionOptions` |
| `services/SubscriptionService.java` | `toggle` |

### Controller layer (works, but see "Traps" below)

| File | Method |
|---|---|
| `controllers/UserController.java` | `updateTimeline`, `addFlightLog`, `completeMaintenance`, `addDescriptionOption`, `deleteFlightLog` |

### The clearest example of why it matters

`UserController.deleteFlightLog` does two things that must not be separated:

```java
flightLogRepository.delete(log);                    // 1. remove the log

List<FlightLog> remainingLogs = flightLogRepository.findByUser(user);
double newHobbs = computeDisplayedHours(user, remainingLogs, true);
double newTach  = computeDisplayedHours(user, remainingLogs, false);
user.setHobbsHours(newHobbs);
...
userRepository.save(user);                          // 2. recompute the hours
```

If step 1 commits and step 2 does not, the aircraft has a deleted flight but stale hobbs
and tach hours on screen. That is the exact bug class the recent commits have been fixing.
`@Transactional` is what guarantees both or neither.

## Which import to use

Two different annotations share the name `Transactional`:

- `org.springframework.transaction.annotation.Transactional` — **use this one.**
  Supports `readOnly`, `isolation`, `timeout`, `propagation`, `noRollbackFor`.
- `jakarta.transaction.Transactional` — works, Spring honours it, but it is the weaker
  annotation with fewer options.

Both roll back on unchecked exceptions (`RuntimeException`, `Error`) and **not** on checked
exceptions.

**Current state of this repo:** all five service classes use the Spring import. `UserController.java`
still imports the Jakarta one. Worth making consistent.

## Traps in this codebase

### 1. Catching inside the transaction cancels the rollback

The foreman only sees the door. Compare these two:

**Correct — `UserController.updateHours`.** The method has *no* `@Transactional`. The
transaction lives one level down in `HoursService.updateHours`. An exception escapes the
service, the foreman sees it and rolls back, and *then* the controller's `catch` turns it
into a 400 response. Boundary inside, catch outside.

```java
public ResponseEntity<...> updateHours(...) {          // no @Transactional here
    try {
        hoursService.updateHours(...);                  // @Transactional lives in here
        ...
    } catch (Exception e) {                             // catch is OUTSIDE the boundary
        return ResponseEntity.badRequest().body(errorResponse);
    }
}
```

**Leaky — `UserController.updateTimeline` and `UserController.deleteFlightLog`.** Both
carry `@Transactional` *and* wrap their whole body in `catch (Exception e)`. The exception
never escapes, so the foreman files the work order regardless. In `deleteFlightLog` that
means the delete can commit while the hours recomputation failed — a 500 goes to the
browser but the data is already changed.

**Rule:** put `@Transactional` on the layer *below* the one that catches. Services throw,
controllers catch and format the response.

### 2. Nested transactional calls share one transaction

`UserController.updateTimeline` calls `descriptionOptionService.saveCustomDescriptionOption`,
which is itself `@Transactional`. It does not get its own transaction — it joins the
controller's.

If it throws, the inner proxy flags the shared transaction rollback-only, the outer
`catch (Exception e)` swallows the exception and returns a tidy 400, and then Spring throws
`UnexpectedRollbackException` at commit time. The user gets a confusing 500 whose stack
trace points nowhere near the real failure.

### 3. Self-invocation does nothing

Calling a `@Transactional` method from another method in the *same class* skips the proxy
entirely, so no transaction is opened, silently.

This codebase is currently safe — every call crosses a bean boundary (controller → service,
or `TimelineService` → `descriptionOptionService`). Worth remembering before moving a
transactional method's caller into the same class.

### 4. `@Transactional` on a controller holds the connection too long

The five controller-level ones keep a database transaction open for the entire HTTP request,
including JSON serialization. Harmless at current traffic, wasteful under load. Moving those
method bodies into services fixes traps 1, 2 and 4 at once.

## How to see it in the code

### A. Watch the foreman narrate (already enabled)

These two lines are in `src/main/resources/application-main.properties`:

```properties
logging.level.org.springframework.transaction.interceptor=TRACE
logging.level.org.springframework.orm.jpa.JpaTransactionManager=DEBUG
```

`TransactionInterceptor` **is** the foreman. It is the Spring class that wraps every
`@Transactional` method. Turning it to TRACE makes it announce each step.

Run the app:

```bash
./gradlew bootRun -Pdev
```

(`-Pdev` is defined in `build.gradle` and serves templates/CSS live from `src/` so frontend
edits show without a rebuild.)

Then log in and change the hobbs hours. Expect log lines like:

```
Getting transaction for [com.example.AviaryService.services.HoursService.updateHours]
Creating new transaction with name [...HoursService.updateHours]: PROPAGATION_REQUIRED,ISOLATION_DEFAULT
Completing transaction for [...HoursService.updateHours]
Initiating transaction commit
```

Two experiments worth doing once:

- **See a rollback.** Call the update-hours endpoint with all four parameters empty.
  `HoursService` throws `IllegalArgumentException`, and the log should show
  `Initiating transaction rollback` instead of commit.
- **See transactions nest.** Save a timeline row with a brand-new custom description.
  `updateTimeline` calls `saveCustomDescriptionOption`, which is also `@Transactional`.
  The log should show `Participating in existing transaction` — trap 2, visible.

To see the SQL landing between the begin and commit lines, flip
`spring.jpa.show-sql` back to `true` in the same file. Noisy, but instructive once.

### B. Print the proxy object itself

The bean in your controller is not a `HoursService`. It is a generated subclass. Add this
temporarily to `AviaryServiceApplication.java`:

```java
@Bean
CommandLineRunner inspectProxies(HoursService hoursService) {
    return args -> {
        System.out.println("actual class: " + hoursService.getClass().getName());
        System.out.println("is AOP proxy? " + org.springframework.aop.support.AopUtils.isAopProxy(hoursService));
        System.out.println("real target:  " + org.springframework.aop.framework.AopProxyUtils.ultimateTargetClass(hoursService));
    };
}
```

Expected at startup — note the `$$SpringCGLIB$$` suffix:

```
actual class: com.example.AviaryService.services.HoursService$$SpringCGLIB$$0
is AOP proxy? true
real target:  class com.example.AviaryService.services.HoursService
```

That is the foreman, named. A subclass Spring generated at startup that overrides
`updateHours`, opens the work order, then calls `super`.

To assert from *inside* a method whether a transaction is actually running:

```java
org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
```

Useful for proving the self-invocation trap when you suspect one.

### C. List every bean in the application context

Same `CommandLineRunner` shape, taking `ApplicationContext ctx`:

```java
java.util.Arrays.stream(ctx.getBeanDefinitionNames())
    .sorted()
    .forEach(n -> System.out.println(n + "  ->  " + ctx.getType(n).getName()));
```

Around 200 beans, mostly Spring's own. The proxied ones show their CGLIB type right in
the output.

Browsable alternative is Actuator, which needs three edits: add
`implementation 'org.springframework.boot:spring-boot-starter-actuator'` to `build.gradle`,
add `management.endpoints.web.exposure.include=beans,conditions` to the properties, and add
`/actuator/**` to the `permitAll()` list in `SecurityConfig.java` — otherwise the filter
chain redirects to the login page. Then `/actuator/beans` returns every bean and its
dependencies as JSON.

### D. The debugger — the most convincing view

Set a breakpoint on the first line of `updateHours` in `HoursService.java`, run in debug,
and trigger the endpoint from the browser. The call stack pane shows the frames between
the controller and your method:

```
HoursService.updateHours                              <- you are here
CglibAopProxy$DynamicAdvisedInterceptor.intercept
TransactionInterceptor.invoke                         <- the foreman
ReflectiveMethodInvocation.proceed
HoursService$$SpringCGLIB$$0.updateHours              <- the proxy
UserController.updateHours
```

The controller called the proxy, the proxy called the interceptor, the interceptor called
your code. Reading that stack top to bottom is the clearest explanation of the whole
mechanism.

## Quick grep commands

Find every transaction boundary:

```bash
grep -rn "@Transactional" --include="*.java" src/main
```

Check which import each file uses:

```bash
grep -rn "import .*Transactional" --include="*.java" src/main
```

Find catch blocks that might be swallowing rollbacks — cross-reference these line numbers
against the `@Transactional` ones above; any catch inside a transactional method is trap 1:

```bash
grep -rn "catch (Exception e)" --include="*.java" src/main
```
