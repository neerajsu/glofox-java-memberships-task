package com.glofox.memberships.membership.api;

import static com.glofox.memberships.common.constant.Constants.IDEMPOTENCY_KEY_HEADER;
import static com.glofox.memberships.membership.constant.Constants.PURCHASE_COLLECTION_PATH;

import com.glofox.memberships.membership.dto.PurchasePageResponse;
import com.glofox.memberships.membership.dto.PurchaseResponse;
import com.glofox.memberships.membership.service.purchase.PurchaseService;
import com.glofox.memberships.membership.service.query.PurchaseQueryService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(PURCHASE_COLLECTION_PATH)
@RequiredArgsConstructor
public class PurchaseQueryController {

    private final PurchaseQueryService purchaseQueryService;
    private final PurchaseService purchaseService;

    @PostMapping
    public ResponseEntity<PurchaseResponse> createPurchase(
            @Valid @RequestBody PurchaseRequest request,
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey) {
        PurchaseResponse response = purchaseService.purchase(request, idempotencyKey);
        return ResponseEntity.created(URI.create("/v1/memberships/purchases/" + response.id())).body(response);
    }

    @GetMapping
    public PurchasePageResponse listPurchases(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer limit) {
        return purchaseQueryService.findPage(page, limit);
    }

    @GetMapping("/{id}")
    public PurchaseResponse getPurchase(@PathVariable UUID id) {
        return purchaseQueryService.findById(id);
    }
}
