package dev.igherga.moneymind.web;

import dev.igherga.moneymind.common.CurrencyMismatchException;
import dev.igherga.moneymind.transaction.TransactionValidationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.format.DateTimeParseException;

/**
 * Turns rejected writes into messages a person can act on.
 *
 * <p>Without this a broken write surfaces as a 500 and a stack trace, and the
 * UI can only say "something went wrong". The ledger rules are things the user
 * can actually fix — a wrong category, a missing date — so they are reported as
 * 400s carrying the explanation.
 */
@RestControllerAdvice
class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler({
            TransactionValidationException.class,
            IllegalArgumentException.class,
            DateTimeParseException.class,
    })
    ResponseEntity<ApiError> badRequest(RuntimeException failure) {
        return ResponseEntity.badRequest().body(new ApiError(failure.getMessage()));
    }

    @ExceptionHandler(CurrencyMismatchException.class)
    ResponseEntity<ApiError> currencyMismatch(CurrencyMismatchException failure) {
        return ResponseEntity.badRequest().body(new ApiError(failure.getMessage()));
    }

    /**
     * The database's own guards — the currency trigger and the transfer CHECK
     * constraints. Reaching one means a rule slipped past the service layer, so
     * it is logged as well as reported: the message is for the user, the log
     * entry is the bug report.
     */
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ApiError> databaseRejected(DataAccessException failure) {
        log.warn("The database rejected a write; a service-layer check is missing", failure);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("That change would break a ledger rule and was not saved."));
    }

    record ApiError(String message) {
    }
}
