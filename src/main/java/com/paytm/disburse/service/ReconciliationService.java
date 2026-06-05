package com.paytm.disburse.service;

import com.paytm.disburse.domain.AttemptStatus;
import com.paytm.disburse.domain.DisbursementAttempt;
import com.paytm.disburse.observability.DisbursementMetrics;
import com.paytm.disburse.repository.RowMappers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;

@Service
public class ReconciliationService {

    public record BankRow(String referenceId, LocalDate date, long amountPaise,
                          String account, String ifsc, String status) {}
    public record Break(String type, String detail, String referenceId) {}
    public record Report(int internalCount, int bankCount, int matched, List<Break> breaks) {}

    private final JdbcTemplate jdbc;
    private final DisbursementMetrics metrics;

    public ReconciliationService(JdbcTemplate jdbc, DisbursementMetrics metrics) {
        this.jdbc = jdbc;
        this.metrics = metrics;
    }

    public Report reconcile(InputStream csv) throws Exception {
        List<BankRow> bank = parseCsv(csv);

        Map<String, DisbursementAttempt> internalByRef = new HashMap<>();
        for (var a : jdbc.query("""
                SELECT a.* FROM disbursement_attempt a
                WHERE a.status IN ('SUCCESS', 'UNCERTAIN')
                """, RowMappers.ATTEMPT)) {
            internalByRef.put(a.id().toString(), a);
        }

        List<Break> breaks = new ArrayList<>();
        Set<String> bankRefsSeen = new HashSet<>();
        int matched = 0;

        for (BankRow row : bank) {
            bankRefsSeen.add(row.referenceId);
            DisbursementAttempt internal = internalByRef.get(row.referenceId);
            if (internal == null) {
                breaks.add(new Break("BANK_ONLY",
                    "Bank recorded txn but no internal record. amount=" + row.amountPaise + " account=" + row.account,
                    row.referenceId));
                continue;
            }
            if (!equalsLong(internal, row)) {
                breaks.add(new Break("AMOUNT_MISMATCH",
                    "Bank amount differs from internal record", row.referenceId));
                continue;
            }
            if ("FAILED".equalsIgnoreCase(row.status) && internal.status() == AttemptStatus.SUCCESS) {
                breaks.add(new Break("STATUS_MISMATCH",
                    "We show SUCCESS, bank shows FAILED", row.referenceId));
                continue;
            }
            matched++;
        }
        for (var e : internalByRef.entrySet()) {
            if (!bankRefsSeen.contains(e.getKey())
                && e.getValue().status() == AttemptStatus.SUCCESS) {
                breaks.add(new Break("INTERNAL_ONLY",
                    "We show SUCCESS but bank statement has no row", e.getKey()));
            }
        }
        breaks.forEach(b -> metrics.reconcileBreak(b.type()));
        return new Report(internalByRef.size(), bank.size(), matched, breaks);
    }

    private boolean equalsLong(DisbursementAttempt a, BankRow row) {
        Long internalAmount = jdbc.queryForObject(
            "SELECT amount_paise FROM disbursement WHERE id = ?",
            Long.class, a.disbursementId().toString());
        return internalAmount != null && internalAmount == row.amountPaise;
    }

    private List<BankRow> parseCsv(InputStream in) throws Exception {
        List<BankRow> out = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String header = br.readLine();
            if (header == null) return out;
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] cols = line.split(",", -1);
                out.add(new BankRow(cols[0].trim(), LocalDate.parse(cols[1].trim()),
                    Long.parseLong(cols[2].trim()), cols[3].trim(), cols[4].trim(), cols[5].trim()));
            }
        }
        return out;
    }
}
