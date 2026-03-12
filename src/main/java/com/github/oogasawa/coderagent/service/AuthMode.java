package com.github.oogasawa.coderagent.service;

/**
 * Authentication mode for Codex access.
 */
public enum AuthMode {
    /** Codex CLI is installed locally. */
    CLI,
    /** API key provided via environment variable or config property. */
    API_KEY,
    /** No authentication configured; must be provided via Web UI. */
    NONE
}
