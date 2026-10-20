package com.example.AviaryService.services.imports;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.AviaryService.entity.ImportProposal;
import com.example.AviaryService.entity.OAuthAuthorizationEntity;
import com.example.AviaryService.entity.ServiceTimeline;
import com.example.AviaryService.entity.TimelineImportRecord;
import com.example.AviaryService.entity.User;
import com.example.AviaryService.repositories.ImportProposalRepository;
import com.example.AviaryService.repositories.OAuthAuthorizationRepository;
import com.example.AviaryService.repositories.ServiceTimelineRepository;
import com.example.AviaryService.repositories.TimelineImportRecordRepository;
import com.example.AviaryService.util.DueDates;
import com.example.AviaryService.util.Formatting;
import com.example.AviaryService.util.SlidingWindowLimiter;
import com.example.AviaryService.util.TimelineImportRules;
import com.example.AviaryService.util.TimelineImportRules.ImportFlag;
import com.example.AviaryService.util.TimelineImportRules.InputItem;
import com.example.AviaryService.util.TimelineImportRules.ItemResult;
import com.example.AviaryService.util.TimelineImportRules.ProposedItem;
import com.example.AviaryService.util.TimelineImportRules.RejectedRow;
import com.example.AviaryService.util.TimelineImportRules.SubmissionOutcome;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

// Service timeline import through an AI assistant: propose (preview only),
// continue a poor fit, confirm (saves exactly the proposal, all or nothing).
// Imports only ever add rows -- existing items are never changed or deleted.
// Every lookup is by owner, so one owner can't see or confirm another's
// proposal.
@Service
public class TimelineImportService {

    private static final Logger log = LoggerFactory.getLogger(TimelineImportService.class);

    static final Duration PROPOSAL_TTL = Duration.ofHours(24);
    static final int PROPOSALS_PER_HOUR = 30;
    static final int CONFIRMS_PER_HOUR = 10;
    private static final List<String> LIVE = List.of(ImportProposal.OPEN, ImportProposal.NEEDS_FIT_CONFIRMATION);

    // What the MCP tools return. Fields that don't apply to a status are left
    // out of the JSON.
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ImportResult(String status, Long proposalId, String expiresAt,
                               List<ProposedItem> items, List<RejectedRow> rejected,
                               String summary, String message, String example,
                               Integer addedCount, List<String> skippedDuplicates, List<String> newDuplicates,
                               Long retryAfterSeconds) {

        static ImportResult status(String status, String message) {
            return new ImportResult(status, null, null, null, null, null, message, null, null, null, null, null);
        }
    }

    private final ImportProposalRepository proposalRepository;
    private final ServiceTimelineRepository timelineRepository;
    private final TimelineImportRecordRepository recordRepository;
    private final OAuthAuthorizationRepository authorizationRepository;
    private final TransactionTemplate transaction;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Clock clock;
    private final SlidingWindowLimiter proposeLimiter;
    private final SlidingWindowLimiter confirmLimiter;

    @Autowired
    public TimelineImportService(ImportProposalRepository proposalRepository,
                                 ServiceTimelineRepository timelineRepository,
                                 TimelineImportRecordRepository recordRepository,
                                 OAuthAuthorizationRepository authorizationRepository,
                                 PlatformTransactionManager transactionManager) {
        this(proposalRepository, timelineRepository, recordRepository, authorizationRepository,
            transactionManager, Clock.systemUTC());
    }

    TimelineImportService(ImportProposalRepository proposalRepository,
                          ServiceTimelineRepository timelineRepository,
                          TimelineImportRecordRepository recordRepository,
                          OAuthAuthorizationRepository authorizationRepository,
                          PlatformTransactionManager transactionManager,
                          Clock clock) {
        this.proposalRepository = proposalRepository;
        this.timelineRepository = timelineRepository;
        this.recordRepository = recordRepository;
        this.authorizationRepository = authorizationRepository;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.proposeLimiter = new SlidingWindowLimiter(PROPOSALS_PER_HOUR, Duration.ofHours(1), clock);
        this.confirmLimiter = new SlidingWindowLimiter(CONFIRMS_PER_HOUR, Duration.ofHours(1), clock);
    }

    // ---- propose ----

    // Builds and stores a proposal. Changes nothing on the timeline. Any
    // earlier open proposal of this owner is replaced.
    public ImportResult propose(User user, List<InputItem> items, List<RejectedRow> rejectedBySource, String clientId) {
        List<InputItem> input = items == null ? List.of() : items;
        List<RejectedRow> fromSource = cleanSourceRejects(rejectedBySource);

        if (input.size() > TimelineImportRules.MAX_ITEMS) {
            return ImportResult.status("TOO_LARGE", "That's " + input.size() + " items; the most one proposal can hold is "
                + TimelineImportRules.MAX_ITEMS + ". Split the records and propose each part separately.");
        }
        if (!proposeLimiter.tryAcquire(user.getUsername())) {
            return rateLimited("Too many proposals in the last hour. Try again later.");
        }

        return transaction.execute(tx -> {
            LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
            ItemResult result = TimelineImportRules.evaluate(input, existingNames(user), today);
            List<RejectedRow> rejected = new ArrayList<>(result.rejected());
            rejected.addAll(fromSource);
            int submitted = input.size() + fromSource.size();

            SubmissionOutcome outcome = TimelineImportRules.classify(input.size(), submitted, rejected.size(), result);
            if (outcome == SubmissionOutcome.REJECTED) {
                return new ImportResult("REJECTED", null, null, null, rejected, null,
                    "No maintenance items were found, so nothing was proposed. Every row was either missing an item "
                        + "name or set aside as not being a maintenance item (reasons below). See the example for the format.",
                    TimelineImportRules.FORMAT_EXAMPLE, null, null, null, null);
            }

            for (ImportProposal old : proposalRepository.findByUserAndStatusIn(user, LIVE)) {
                old.setStatus(ImportProposal.REPLACED);
                proposalRepository.save(old);
            }

            Instant now = clock.instant();
            ImportProposal proposal = new ImportProposal();
            proposal.setUser(user);
            proposal.setStatus(outcome == SubmissionOutcome.POOR_FIT ? ImportProposal.NEEDS_FIT_CONFIRMATION : ImportProposal.OPEN);
            proposal.setItemsJson(write(result.items()));
            proposal.setRejectedJson(write(rejected));
            proposal.setSubmittedCount(submitted);
            proposal.setCreatedAt(now);
            proposal.setExpiresAt(now.plus(PROPOSAL_TTL));
            proposal.setConnectionClientId(clientId);
            proposalRepository.save(proposal);

            String summary = summarize(result.items(), rejected);
            if (outcome == SubmissionOutcome.POOR_FIT) {
                return new ImportResult("POOR_FIT", proposal.getId(), proposal.getExpiresAt().toString(), result.items(), rejected,
                    summary,
                    rejected.size() + " of " + submitted + " rows didn't fit Aviary's format (reasons below). "
                        + "Ask the owner whether to continue with only the rows that fit; if yes, call continue_poor_fit.",
                    null, null, null, null, null);
            }
            return new ImportResult("PROPOSED", proposal.getId(), proposal.getExpiresAt().toString(), result.items(), rejected,
                summary, "Nothing has been added yet. Show the owner this proposal and confirm only when they say so.",
                null, null, null, null, null);
        });
    }

    // ---- continue a poor fit ----

    public ImportResult continuePoorFit(User user, Long proposalId) {
        return transaction.execute(tx -> {
            ImportProposal proposal = proposalRepository.findByIdAndUser(proposalId, user).orElse(null);
            ImportResult problem = checkUsable(proposal, true);
            if (problem != null) return problem;
            if (ImportProposal.NEEDS_FIT_CONFIRMATION.equals(proposal.getStatus())) {
                proposal.setStatus(ImportProposal.OPEN);
                proposalRepository.save(proposal);
            }
            List<ProposedItem> items = readItems(proposal);
            return new ImportResult("PROPOSED", proposal.getId(), proposal.getExpiresAt().toString(), items, readRejected(proposal),
                summarize(items, readRejected(proposal)),
                "Continuing with only the rows that fit. Confirm when the owner says so.",
                null, null, null, null, null);
        });
    }

    // ---- confirm ----

    // Adds exactly the proposed items after the owner's existing rows, in one
    // transaction: if any row fails, none are saved. Items flagged as matching
    // an existing item are skipped unless their index is in addDuplicatesAnyway;
    // a match that appeared since the proposal is skipped and reported too.
    public ImportResult confirm(User user, Long proposalId, Set<Integer> addDuplicatesAnyway) {
        if (!confirmLimiter.tryAcquire(user.getUsername())) {
            return rateLimited("Too many imports in the last hour. Try again later.");
        }
        Set<Integer> optIn = addDuplicatesAnyway == null ? Set.of() : addDuplicatesAnyway;
        try {
            return transaction.execute(tx -> {
                ImportProposal proposal = proposalRepository.findByIdAndUser(proposalId, user).orElse(null);
                ImportResult problem = checkUsable(proposal, false);
                if (problem != null) return problem;

                List<ProposedItem> items = readItems(proposal);
                Set<String> existing = existingNames(user);
                List<String> skipped = new ArrayList<>();
                List<String> newDuplicates = new ArrayList<>();

                LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
                Double currentTis = user.getTimeInServiceHours();
                Integer max = timelineRepository.findMaxTimelineOrderByUser(user);
                int order = max == null ? 0 : max + 1;
                int added = 0;

                for (ProposedItem item : items) {
                    boolean matches = existing.contains(TimelineImportRules.normalizeName(item.item()));
                    if (matches && !optIn.contains(item.index())) {
                        if (item.flags().contains(ImportFlag.DUPLICATE_EXISTING)) {
                            skipped.add(item.item());
                        } else {
                            newDuplicates.add(item.item());
                        }
                        continue;
                    }
                    timelineRepository.save(toRow(user, item, order++, today, currentTis));
                    added++;
                }

                Instant now = clock.instant();
                proposal.setStatus(ImportProposal.CONFIRMED);
                proposal.setConfirmedAt(now);
                proposalRepository.save(proposal);

                TimelineImportRecord record = new TimelineImportRecord();
                record.setUser(user);
                record.setProposalId(proposal.getId());
                record.setClientId(proposal.getConnectionClientId());
                record.setClientName(clientName(user, proposal.getConnectionClientId()));
                record.setItemCount(added);
                record.setImportedAt(now);
                recordRepository.save(record);

                String message = "Added " + added + (added == 1 ? " item" : " items") + " to the service timeline.";
                if (!skipped.isEmpty() || !newDuplicates.isEmpty()) {
                    message += " Skipped " + (skipped.size() + newDuplicates.size())
                        + " that match items already on the timeline.";
                }
                return new ImportResult("IMPORTED", proposal.getId(), null, null, null, null, message, null,
                    added, skipped, newDuplicates, null);
            });
        } catch (RuntimeException e) {
            // The transaction rolled back, so nothing from this proposal was saved.
            log.warn("Timeline import {} failed for {}", proposalId, user.getUsername(), e);
            return ImportResult.status("FAILED",
                "The import failed and nothing was added. The proposal is still open; try confirming again.");
        }
    }

    // null when the proposal can be acted on; otherwise the status to return.
    private ImportResult checkUsable(ImportProposal proposal, boolean continuing) {
        if (proposal == null) {
            return ImportResult.status("NOT_FOUND", "No such proposal for this account.");
        }
        expireIfDue(proposal);
        return switch (proposal.getStatus()) {
            case ImportProposal.CONFIRMED -> ImportResult.status("ALREADY_CONFIRMED", "This proposal was already imported.");
            case ImportProposal.REPLACED -> ImportResult.status("REPLACED",
                "A newer proposal replaced this one. Confirm the newer proposal instead.");
            case ImportProposal.EXPIRED -> ImportResult.status("EXPIRED",
                "This proposal expired (proposals last 24 hours). Propose the records again.");
            case ImportProposal.NEEDS_FIT_CONFIRMATION -> continuing ? null : ImportResult.status("NEEDS_FIT_CONFIRMATION",
                "Most rows didn't fit. Ask the owner whether to continue with only the rows that fit, then call continue_poor_fit.");
            default -> null;
        };
    }

    private void expireIfDue(ImportProposal proposal) {
        if (LIVE.contains(proposal.getStatus()) && !proposal.getExpiresAt().isAfter(clock.instant())) {
            proposal.setStatus(ImportProposal.EXPIRED);
            proposalRepository.save(proposal);
        }
    }

    private ServiceTimeline toRow(User user, ProposedItem item, int order, LocalDate today, Double currentTis) {
        ServiceTimeline row = new ServiceTimeline();
        row.setUser(user);
        row.setItem(item.item());
        row.setIsTitle(item.isTitle());
        row.setTimelineOrder(order);
        if (item.isTitle()) {
            return row;
        }
        row.setDescription(item.flags().contains(ImportFlag.ON_CONDITION)
            ? onConditionDescription(item.description()) : item.description());
        row.setLastDoneDate(item.lastDoneDate());
        row.setLastDoneHours(item.lastDoneHours() == null ? null : Formatting.formatHours(item.lastDoneHours()));
        row.setDueDateDate(item.dueDateDate());
        row.setDueDateHours(item.dueDateHours() == null ? null : Formatting.formatHours(item.dueDateHours()));
        row.setCycleCalendarValue(item.cycleCalendarValue());
        row.setCycleCalendarUnit(item.cycleCalendarUnit());
        row.setCycleHours(item.cycleHours());
        row.setTimeLeft(DueDates.formatTimeLeft(row.getDueDateDate(), row.getDueDateHours(), today, currentTis));
        return row;
    }

    // The timeline has no on-condition column, so the description says it --
    // otherwise the row shows up with every date blank and no explanation.
    static String onConditionDescription(String description) {
        if (description == null || description.isBlank()) return "On condition";
        if (description.toLowerCase().contains("on condition")) return description;
        String withNote = description + " (on condition)";
        return withNote.length() <= com.example.AviaryService.util.Validation.MAX_TEXT ? withNote : description;
    }

    private Set<String> existingNames(User user) {
        Set<String> names = new HashSet<>();
        for (ServiceTimeline row : timelineRepository.findByUserOrderByTimelineOrderAsc(user)) {
            names.add(TimelineImportRules.normalizeName(row.getItem()));
        }
        return names;
    }

    private String clientName(User user, String clientId) {
        if (clientId == null) return "Unknown assistant";
        for (OAuthAuthorizationEntity grant : authorizationRepository.findByPrincipalName(user.getUsername())) {
            if (clientId.equals(grant.getRegisteredClientId()) && grant.getClientName() != null) {
                return grant.getClientName();
            }
        }
        return ConnectionService.hostOf(clientId);
    }

    private static String summarize(List<ProposedItem> items, List<RejectedRow> rejected) {
        long real = items.stream().filter(i -> !i.isTitle()).count();
        long titles = items.size() - real;
        long flagged = items.stream().filter(i -> !i.flags().isEmpty()).count();
        long duplicates = items.stream().filter(i -> i.flags().contains(ImportFlag.DUPLICATE_EXISTING)).count();
        long noSchedule = items.stream().filter(i -> i.flags().contains(ImportFlag.NO_SCHEDULE)).count();
        long onCondition = items.stream().filter(i -> i.flags().contains(ImportFlag.ON_CONDITION)).count();
        long dropped = items.stream().filter(i -> i.flags().contains(ImportFlag.VALUE_DROPPED)).count();
        StringBuilder sb = new StringBuilder();
        sb.append(real).append(real == 1 ? " item" : " items");
        if (titles > 0) sb.append(" and ").append(titles).append(titles == 1 ? " title row" : " title rows");
        sb.append(" to add");
        if (flagged > 0) sb.append(", ").append(flagged).append(" flagged for the owner to check");
        if (duplicates > 0) sb.append(" (").append(duplicates).append(" already on the timeline and skipped unless the owner opts in)");
        if (onCondition > 0) sb.append("; ").append(onCondition).append(" on condition");
        if (noSchedule > 0) sb.append("; ").append(noSchedule).append(" with no due date or interval (Time Left shows N/A)");
        if (dropped > 0) sb.append("; ").append(dropped).append(" with a value left blank (see notes)");
        sb.append("; ").append(rejected.size()).append(rejected.size() == 1 ? " row" : " rows").append(" can't be imported.");
        return sb.toString();
    }

    private static List<RejectedRow> cleanSourceRejects(List<RejectedRow> rows) {
        if (rows == null) return List.of();
        List<RejectedRow> out = new ArrayList<>();
        for (RejectedRow r : rows) {
            if (r == null) continue;
            out.add(new RejectedRow(truncate(r.sourceRow()), truncate(r.reason() == null ? "Could not be read." : r.reason())));
        }
        return out;
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > TimelineImportRules.MAX_SOURCE_ROW ? s.substring(0, TimelineImportRules.MAX_SOURCE_ROW) : s;
    }

    private static ImportResult rateLimited(String message) {
        return new ImportResult("RATE_LIMITED", null, null, null, null, null, message, null, null, null, null, 3600L);
    }

    private List<ProposedItem> readItems(ImportProposal p) {
        return read(p.getItemsJson(), new TypeReference<List<ProposedItem>>() {});
    }

    private List<RejectedRow> readRejected(ImportProposal p) {
        return read(p.getRejectedJson(), new TypeReference<List<RejectedRow>>() {});
    }

    private <T> T read(String json, TypeReference<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("Stored proposal is unreadable", e);
        }
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
