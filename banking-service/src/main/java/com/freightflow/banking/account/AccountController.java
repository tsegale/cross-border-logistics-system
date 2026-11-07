package com.freightflow.banking.account;

import com.freightflow.banking.account.dto.AccountResponse;
import com.freightflow.banking.account.dto.CreateAccountRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping("/api/v1/accounts")
@Tag(name = "Accounts", description = "Customer and system ledger accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping
    @Operation(
            summary = "Open a customer ledger account",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = CreateAccountRequest.class),
                            examples = @ExampleObject(
                                    name = "Namibian exporter account",
                                    value = """
                                            {
                                              "holderReference": "CUST-NAM-WALVIS-001",
                                              "currency": "NAD"
                                            }
                                            """
                            )
                    )
            ),
            responses = {
                    @ApiResponse(
                            responseCode = "201",
                            description = "Customer account created",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                                    schema = @Schema(implementation = AccountResponse.class),
                                    examples = @ExampleObject(
                                            value = """
                                                    {
                                                      "id": "01930000-0000-7000-8000-000000001000",
                                                      "accountNumber": "FF83NAD000001000",
                                                      "holderReference": "CUST-NAM-WALVIS-001",
                                                      "type": "CUSTOMER",
                                                      "currency": "NAD",
                                                      "status": "ACTIVE",
                                                      "balance": 0.00,
                                                      "createdAt": "2026-11-07T18:00:00Z",
                                                      "updatedAt": "2026-11-07T18:00:00Z"
                                                    }
                                                    """
                                    )
                            )
                    ),
                    @ApiResponse(responseCode = "400", description = "Validation error or unsupported currency",
                            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
            }
    )
    public ResponseEntity<AccountResponse> createAccount(@Valid @RequestBody CreateAccountRequest request) {
        AccountResponse created = accountService.createCustomerAccount(request);
        return ResponseEntity.created(URI.create("/api/v1/accounts/" + created.id())).body(created);
    }

    @GetMapping("/{id}")
    @Operation(
            summary = "Get a ledger account by UUIDv7 id or check-digit account number",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Account details",
                            content = @Content(schema = @Schema(implementation = AccountResponse.class))),
                    @ApiResponse(responseCode = "400", description = "Mistyped account number (MOD 97-10 check failed)",
                            content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
                    @ApiResponse(responseCode = "404", description = "Account not found",
                            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
            }
    )
    public AccountResponse getAccount(@PathVariable("id") String id) {
        return accountService.getAccount(id);
    }
}
