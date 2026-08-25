package com.fooddelivery.advertisement.bidding.config;

import com.fooddelivery.common.constants.HeaderConstants;
import com.fooddelivery.common.security.IdentityTokenService;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class SystemFeignInterceptor implements RequestInterceptor {

    private final IdentityTokenService identityTokenService;

    public SystemFeignInterceptor(IdentityTokenService identityTokenService) {
        this.identityTokenService = identityTokenService;
    }

    @Override
    public void apply(RequestTemplate template) {
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            long issuedAt = System.currentTimeMillis();
            // Sign the system identity
            String signature = identityTokenService.sign("SYSTEM", "ROLE_SYSTEM", "", "", issuedAt);
            
            // Add required headers for internal API calls
            template.header(HeaderConstants.HEADER_USER_ID, "SYSTEM");
            template.header(HeaderConstants.HEADER_USER_ROLES, "ROLE_SYSTEM");
            template.header(HeaderConstants.HEADER_ISSUED_AT, String.valueOf(issuedAt));
            template.header(HeaderConstants.HEADER_IDENTITY_SIGNATURE, signature);
        }
    }
}
