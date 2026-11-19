package com.example.pakredirect;

/**
 * Client-side HTTP error classification for protected content manifests.
 *
 * Explicit authorization/configuration failures must stop the launch. Temporary
 * upstream outages may ONLY fall back to an already verified encrypted cache.
 */
final class ContentManifestFailurePolicy {
    private ContentManifestFailurePolicy() {}

    static boolean isPermanentAuthorizationFailure(int status) {
        return status == 401 || status == 403 || status == 404;
    }
}
