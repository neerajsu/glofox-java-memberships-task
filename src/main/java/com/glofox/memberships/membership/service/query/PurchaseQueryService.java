package com.glofox.memberships.membership.service.query;

import static com.glofox.memberships.membership.constant.Constants.INVALID_LIMIT_CODE;
import static com.glofox.memberships.membership.constant.Constants.INVALID_PAGE_CODE;
import static com.glofox.memberships.membership.constant.Constants.PURCHASE_NOT_FOUND_CODE;
import static com.glofox.memberships.membership.constant.Constants.PURCHASE_QUERY_DEFAULT_LIMIT;
import static com.glofox.memberships.membership.constant.Constants.PURCHASE_QUERY_DEFAULT_PAGE;
import static com.glofox.memberships.membership.constant.Constants.PURCHASE_QUERY_MAX_LIMIT;

import com.glofox.memberships.membership.dto.PurchasePageResponse;
import com.glofox.memberships.membership.dto.PurchaseResponse;
import com.glofox.memberships.membership.exceptions.MembershipException;
import com.glofox.memberships.membership.repository.MembershipPurchaseRepository;
import com.glofox.memberships.membership.repository.entity.MembershipPurchase;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PurchaseQueryService {

    private final MembershipPurchaseRepository purchaseRepository;

    @Transactional(readOnly = true)
    public PurchaseResponse findById(UUID id) {
        MembershipPurchase purchase = purchaseRepository.findById(id)
                .orElseThrow(() -> new MembershipException(HttpStatus.NOT_FOUND, PURCHASE_NOT_FOUND_CODE, "Purchase was not found"));
        return PurchaseResponse.from(purchase);
    }

    @Transactional(readOnly = true)
    public PurchasePageResponse findPage(Integer page, Integer limit) {
        int actualPage = page == null ? PURCHASE_QUERY_DEFAULT_PAGE : page;
        int actualLimit = limit == null ? PURCHASE_QUERY_DEFAULT_LIMIT : limit;
        if (actualPage < 0) {
            throw new MembershipException(HttpStatus.BAD_REQUEST, INVALID_PAGE_CODE, "page must be zero or greater");
        }
        if (actualLimit < 1 || actualLimit > PURCHASE_QUERY_MAX_LIMIT) {
            throw new MembershipException(HttpStatus.BAD_REQUEST, INVALID_LIMIT_CODE, "limit must be between 1 and " + PURCHASE_QUERY_MAX_LIMIT);
        }

        Sort sort = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
        Page<MembershipPurchase> result = purchaseRepository.findAll(PageRequest.of(actualPage, actualLimit, sort));
        List<PurchaseResponse> content = result.getContent().stream().map(PurchaseResponse::from).toList();
        return new PurchasePageResponse(
                content,
                actualPage,
                actualLimit,
                result.getTotalElements(),
                result.getTotalPages(),
                result.hasNext());
    }
}
