package com.glofox.memberships.membership.service.query;

import static com.glofox.memberships.membership.constant.Constants.INVALID_LIMIT_CODE;
import static com.glofox.memberships.membership.constant.Constants.INVALID_PAGE_CODE;
import static com.glofox.memberships.membership.constant.Constants.PURCHASE_QUERY_DEFAULT_LIMIT;
import static com.glofox.memberships.membership.constant.Constants.PURCHASE_QUERY_DEFAULT_PAGE;
import static com.glofox.memberships.membership.constant.Constants.PURCHASE_QUERY_MAX_LIMIT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.glofox.memberships.membership.exceptions.MembershipException;
import com.glofox.memberships.membership.repository.MembershipPurchaseRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;

class PurchaseQueryServiceTests {

    private final MembershipPurchaseRepository repository = mock(MembershipPurchaseRepository.class);
    private final PurchaseQueryService service = new PurchaseQueryService(repository);

    @Test
    void appliesDefaultPaginationAndReturnsEmptyPageMetadata() {
        when(repository.findAll(any(PageRequest.class))).thenReturn(new PageImpl<>(List.of()));

        var page = service.findPage(null, null);

        assertThat(page.page()).isEqualTo(PURCHASE_QUERY_DEFAULT_PAGE);
        assertThat(page.limit()).isEqualTo(PURCHASE_QUERY_DEFAULT_LIMIT);
        assertThat(page.totalElements()).isZero();
        assertThat(page.totalPages()).isEqualTo(1);
        assertThat(page.hasMore()).isFalse();
    }

    @Test
    void rejectsNegativePageBeforeRepositoryQuery() {
        assertThatThrownBy(() -> service.findPage(-1, PURCHASE_QUERY_DEFAULT_LIMIT))
                .isInstanceOf(MembershipException.class)
                .satisfies(failure -> {
                    MembershipException exception = (MembershipException) failure;
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.getCode()).isEqualTo(INVALID_PAGE_CODE);
                });
        verify(repository, org.mockito.Mockito.never()).findAll(any(PageRequest.class));
    }

    @Test
    void rejectsLimitOutsideSupportedRange() {
        for (int limit : List.of(0, PURCHASE_QUERY_MAX_LIMIT + 1)) {
            assertThatThrownBy(() -> service.findPage(0, limit))
                    .isInstanceOf(MembershipException.class)
                    .satisfies(failure -> assertThat(((MembershipException) failure).getCode())
                            .isEqualTo(INVALID_LIMIT_CODE));
        }
    }
}
