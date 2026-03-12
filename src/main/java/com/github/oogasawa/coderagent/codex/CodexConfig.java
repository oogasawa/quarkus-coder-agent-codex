/*
 * Copyright 2025 Osamu Ogasawara
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.github.oogasawa.coderagent.codex;

/**
 * Configuration record for Codex CLI subprocess invocation.
 *
 * @param model the model to use (e.g., "o4-mini", "o3", "gpt-4.1")
 * @param systemPrompt optional system prompt
 * @param maxTurns maximum agentic turns (0 = unlimited)
 * @param workingDir working directory for the subprocess
 * @param sessionId session ID for continuing a conversation (null = new session)
 * @param continueSession if true, continue the most recent session
 * @param allowedTools list of allowed tools (null = default)
 * @author Osamu Ogasawara
 */
public record CodexConfig(
    String model,
    String systemPrompt,
    int maxTurns,
    String workingDir,
    String sessionId,
    boolean continueSession,
    String[] allowedTools
) {

    /**
     * Creates a default configuration.
     *
     * @return default CodexConfig with o4-mini model and no special options
     */
    public static CodexConfig defaults() {
        return new CodexConfig("o4-mini", null, 0, null, null, false, null);
    }

    /**
     * Returns a new config with the specified model.
     *
     * @param newModel the model name
     * @return new CodexConfig with the model changed
     */
    public CodexConfig withModel(String newModel) {
        return new CodexConfig(newModel, systemPrompt, maxTurns, workingDir, sessionId, continueSession, allowedTools);
    }

    /**
     * Returns a new config with the specified system prompt.
     *
     * @param newSystemPrompt the system prompt
     * @return new CodexConfig with the system prompt changed
     */
    public CodexConfig withSystemPrompt(String newSystemPrompt) {
        return new CodexConfig(model, newSystemPrompt, maxTurns, workingDir, sessionId, continueSession, allowedTools);
    }

    /**
     * Returns a new config with the specified session ID.
     *
     * @param newSessionId the session ID
     * @return new CodexConfig with the session ID changed
     */
    public CodexConfig withSessionId(String newSessionId) {
        return new CodexConfig(model, systemPrompt, maxTurns, workingDir, newSessionId, continueSession, allowedTools);
    }

    /**
     * Returns a new config with continue session enabled.
     *
     * @return new CodexConfig with continueSession set to true
     */
    public CodexConfig withContinueSession() {
        return new CodexConfig(model, systemPrompt, maxTurns, workingDir, sessionId, true, allowedTools);
    }

    /**
     * Returns a new config with the specified max turns.
     *
     * @param newMaxTurns the max turns
     * @return new CodexConfig with maxTurns changed
     */
    public CodexConfig withMaxTurns(int newMaxTurns) {
        return new CodexConfig(model, systemPrompt, newMaxTurns, workingDir, sessionId, continueSession, allowedTools);
    }

    /**
     * Returns a new config with the specified working directory.
     *
     * @param newWorkingDir the working directory
     * @return new CodexConfig with workingDir changed
     */
    public CodexConfig withWorkingDir(String newWorkingDir) {
        return new CodexConfig(model, systemPrompt, maxTurns, newWorkingDir, sessionId, continueSession, allowedTools);
    }

    /**
     * Returns a new config with the specified allowed tools.
     *
     * @param newAllowedTools the allowed tools
     * @return new CodexConfig with allowedTools changed
     */
    public CodexConfig withAllowedTools(String... newAllowedTools) {
        return new CodexConfig(model, systemPrompt, maxTurns, workingDir, sessionId, continueSession, newAllowedTools);
    }
}
