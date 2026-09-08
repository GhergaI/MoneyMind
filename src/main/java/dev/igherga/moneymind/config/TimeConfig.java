package dev.igherga.moneymind.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Supplies the clock the reporting layer reads "today" from.
 *
 * <p>Existing as a bean rather than a call to {@code LocalDate.now()} is what
 * makes month-boundary behaviour testable: a report that is right on the 15th
 * and wrong on the 1st is the normal way this kind of bug ships.
 */
@Configuration
class TimeConfig {

    @Bean
    Clock clock() {
        return Clock.systemDefaultZone();
    }
}
