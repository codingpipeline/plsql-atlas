package dev.sandeep.plsqlparser.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.*;

/** Observable effects beyond return values: transactions, shared state, sequences, DDL, locks, external I/O. */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class SideEffects {
    public final List<Integer> commits = new ArrayList<>();
    public final List<Integer> rollbacks = new ArrayList<>();
    public final List<Integer> savepoints = new ArrayList<>();
    public boolean setTransaction;
    public boolean autonomousTransaction;
    public final Set<String> packageStateReads = new LinkedHashSet<>();
    public final Set<String> packageStateWrites = new LinkedHashSet<>();
    public final Set<String> sequences = new LinkedHashSet<>();
    /** DDL executed from PL/SQL (implicitly commits): e.g. "TRUNCATE (dynamic, line 12)". */
    public final List<String> ddl = new ArrayList<>();
    public final List<Integer> dynamicSqlLines = new ArrayList<>();
    public final List<String> locks = new ArrayList<>();
    public final Set<String> dbLinks = new LinkedHashSet<>();
    /** Oracle-supplied package use by category: FILE_IO, NETWORK, SCHEDULER, IPC, LOCKING, CONSOLE, SESSION, WEB, ... */
    public final Map<String, Set<String>> externalIo = new TreeMap<>();

    public boolean endsTransaction() {
        return !commits.isEmpty() || !rollbacks.isEmpty();
    }
}
