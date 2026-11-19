package com.example.pakredirect;

import org.junit.Test;
import static org.junit.Assert.*;

public class ContentManifestFailurePolicyTest {
    @Test public void authorizationAndMissingManifestMustNotFallbackToCache() {
        assertTrue(ContentManifestFailurePolicy.isPermanentAuthorizationFailure(401));
        assertTrue(ContentManifestFailurePolicy.isPermanentAuthorizationFailure(403));
        assertTrue(ContentManifestFailurePolicy.isPermanentAuthorizationFailure(404));
    }

    @Test public void upstreamOutagesCanUseExistingVerifiedCache() {
        assertFalse(ContentManifestFailurePolicy.isPermanentAuthorizationFailure(408));
        assertFalse(ContentManifestFailurePolicy.isPermanentAuthorizationFailure(429));
        assertFalse(ContentManifestFailurePolicy.isPermanentAuthorizationFailure(500));
        assertFalse(ContentManifestFailurePolicy.isPermanentAuthorizationFailure(502));
        assertFalse(ContentManifestFailurePolicy.isPermanentAuthorizationFailure(503));
        assertFalse(ContentManifestFailurePolicy.isPermanentAuthorizationFailure(504));
    }

    @Test public void cacheNeverReplacesIntegrityOrAuthorizationVerification() {
        // Classification alone cannot authorize any content. The caller must
        // validate the installed manifest HMAC, device-wrapped AES key and PAK
        // sizes/headers before starting the local server.
        assertTrue(ContentManifestFailurePolicy.isPermanentAuthorizationFailure(403));
        assertFalse(ContentManifestFailurePolicy.isPermanentAuthorizationFailure(503));
    }
}
