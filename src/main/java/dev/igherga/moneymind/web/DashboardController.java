package dev.igherga.moneymind.web;

import dev.igherga.moneymind.reporting.DashboardService;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The dashboard's single read.
 *
 * <p>One request rather than eight: every figure on the screen then comes from
 * the same transaction, so the balance and the transactions that explain it can
 * never disagree, and the page has one loading state instead of a cascade of
 * independently arriving panels.
 */
@RestController
@RequestMapping("/api")
public class DashboardController {

    private final DashboardService dashboard;

    DashboardController(DashboardService dashboard) {
        this.dashboard = dashboard;
    }

    @GetMapping("/dashboard")
    public DashboardResponse dashboard() {
        return DashboardResponse.from(dashboard.snapshot());
    }
}
