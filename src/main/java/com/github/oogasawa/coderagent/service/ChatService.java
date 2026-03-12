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

package com.github.oogasawa.coderagent.service;

import com.github.oogasawa.coderagent.codex.CodexConfig;
import com.github.oogasawa.coderagent.codex.CodexProcess;
import com.github.oogasawa.coderagent.command.SlashCommandHandler;
import com.github.oogasawa.coderagent.rest.ChatEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages the Codex CLI process and chat session.
 *
 * <p>Singleton service that routes prompts to Codex CLI.</p>
 */
@ApplicationScoped
public class ChatService {

    private static final Logger logger = Logger.getLogger(ChatService.class.getName());

    private final CodexProcess codexProcess;
    private final SlashCommandHandler commandHandler;
    private volatile boolean busy;
    private volatile Thread activeThread;

    // Authentication
    private final AuthMode authMode;
    private volatile String apiKey;

    // Session persistence
    private final Path sessionFile;

    @Inject
    public ChatService(
            @ConfigProperty(name = "coder-agent.allowed-tools")
            Optional<String> allowedTools,
            @ConfigProperty(name = "coder-agent.api-key", defaultValue = "")
            String configApiKey,
            @ConfigProperty(name = "coder-agent.session-file", defaultValue = ".coder-agent-session")
            String sessionFilePath,
            @ConfigProperty(name = "quarkus.http.port", defaultValue = "8090")
            int httpPort) {

        CodexConfig config = CodexConfig.defaults();
        if (allowedTools.isPresent() && !allowedTools.get().isBlank()) {
            String[] tools = allowedTools.get().split(",");
            for (int i = 0; i < tools.length; i++) {
                tools[i] = tools[i].trim();
            }
            config = config.withAllowedTools(tools);
            logger.info("Allowed tools (auto-approved): " + allowedTools.get());
        }

        // Detect authentication mode:
        // 1. Check if 'codex' CLI is on PATH
        // 2. Check for API key (env var or config property)
        // 3. Otherwise, require Web UI input
        if (isCodexCliAvailable()) {
            this.authMode = AuthMode.CLI;
            this.apiKey = null;
            logger.info("Authentication: Codex CLI detected on PATH");
        } else {
            String envKey = System.getenv("OPENAI_API_KEY");
            if (envKey != null && !envKey.isBlank()) {
                this.authMode = AuthMode.API_KEY;
                this.apiKey = envKey;
                logger.info("Authentication: OPENAI_API_KEY environment variable");
            } else if (!configApiKey.isBlank()) {
                this.authMode = AuthMode.API_KEY;
                this.apiKey = configApiKey;
                logger.info("Authentication: API key from config property");
            } else {
                this.authMode = AuthMode.NONE;
                this.apiKey = null;
                logger.info("Authentication: none configured (will prompt via Web UI)");
            }
        }

        // Restore session from file if available.
        String pupsPath = System.getenv("PUPS_SESSION_PATH");
        if (pupsPath != null && !pupsPath.isBlank()) {
            String suffix = pupsPath.replaceAll("[^a-zA-Z0-9-]", "");
            this.sessionFile = Path.of(sessionFilePath + "-" + suffix);
        } else {
            this.sessionFile = Path.of(sessionFilePath + "-" + httpPort);
        }
        config = restoreSession(config);

        this.codexProcess = new CodexProcess(config);
        this.commandHandler = new SlashCommandHandler(codexProcess);
    }

    /** Returns the current authentication mode. */
    public AuthMode getAuthMode() {
        return authMode;
    }

    /** Returns true if authentication is configured (CLI or API key). */
    public boolean isAuthenticated() {
        return authMode == AuthMode.CLI || (authMode == AuthMode.API_KEY && apiKey != null)
                || (authMode == AuthMode.NONE && apiKey != null);
    }

    /** Sets the API key (from Web UI input). */
    public void setApiKey(String key) {
        this.apiKey = key;
        logger.info("API key set via Web UI");
    }

    /** Returns the API key for use in process environment, or null. */
    public String getApiKey() {
        return apiKey;
    }

    /** Checks if 'codex' CLI is available on PATH. */
    static boolean isCodexCliAvailable() {
        try {
            Process p = new ProcessBuilder("which", "codex")
                    .redirectErrorStream(true).start();
            int exit = p.waitFor();
            return exit == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** Returns whether a prompt is currently being processed. */
    public boolean isBusy() {
        return busy;
    }

    /** Gets the current model name. */
    public String getModel() {
        return codexProcess.getConfig().model();
    }

    /** Gets the current session ID. */
    public String getSessionId() {
        return codexProcess.getLastSessionId();
    }

    /** Checks if input is a slash command. */
    public boolean isCommand(String input) {
        return commandHandler.isCommand(input);
    }

    /** Returns the list of available Codex models. */
    public List<CodexModelSet.ModelEntry> getAvailableModels() {
        return new CodexModelSet().getAvailableModels();
    }

    /** Handles a slash command. */
    public void handleCommand(String input, Consumer<ChatEvent> sender) {
        commandHandler.handle(input, sender);

        if (input.trim().toLowerCase().startsWith("/clear")) {
            deleteSessionFile();
        }

        sender.accept(ChatEvent.status(
            codexProcess.getConfig().model(),
            codexProcess.getLastSessionId(),
            busy
        ));
    }

    /**
     * Sends a prompt to Codex CLI and streams response via callback.
     */
    public void sendPrompt(String prompt, String model, Consumer<ChatEvent> sender) {
        if (busy) {
            sender.accept(ChatEvent.error("Already processing a prompt. Please wait or cancel."));
            return;
        }

        busy = true;
        activeThread = Thread.currentThread();
        try {
            sendToCodex(prompt, model, sender);
        } finally {
            activeThread = null;
            busy = false;
            sender.accept(ChatEvent.status(
                codexProcess.getConfig().model(),
                codexProcess.getLastSessionId(),
                false
            ));
        }
    }

    /** Sends a prompt to Codex CLI. */
    private void sendToCodex(String prompt, String model, Consumer<ChatEvent> sender) {
        if (!isAuthenticated()) {
            sender.accept(ChatEvent.error(
                "No authentication configured. Please provide your OpenAI API key."));
            return;
        }

        try {
            // Pass API key to CodexProcess if using API_KEY mode
            if (apiKey != null && authMode != AuthMode.CLI) {
                codexProcess.setApiKey(apiKey);
            }

            if (!model.equals(codexProcess.getConfig().model())) {
                codexProcess.setConfig(codexProcess.getConfig().withModel(model));
            }

            if (!codexProcess.isAlive()) {
                String lastSessionId = codexProcess.getLastSessionId();
                if (lastSessionId != null) {
                    CodexConfig config = codexProcess.getConfig().withSessionId(lastSessionId);
                    codexProcess.setConfig(config);
                }
            }

            sender.accept(ChatEvent.status(
                codexProcess.getConfig().model(),
                codexProcess.getLastSessionId(),
                true
            ));

            codexProcess.sendPrompt(prompt, new CodexProcess.StreamCallback() {
                @Override
                public void onEvent(com.github.oogasawa.coderagent.codex.StreamEvent event) {
                    switch (event.type()) {
                        case "assistant" -> {
                            if (event.hasContent()) {
                                sender.accept(ChatEvent.delta(event.content()));
                            }
                        }
                        case "thinking" ->
                            sender.accept(ChatEvent.thinking("Thinking..."));
                        case "tool_activity" ->
                            sender.accept(ChatEvent.thinking(
                                "Using " + event.content() + "..."));
                        case "tool_result" ->
                            sender.accept(ChatEvent.thinking("Tool completed."));
                        case "system" -> {
                            if (event.sessionId() != null) {
                                saveSession(event.sessionId(), model);
                            }
                        }
                        case "result" -> {
                            if (event.sessionId() != null) {
                                saveSession(event.sessionId(), model);
                            }
                            sender.accept(ChatEvent.result(
                                event.sessionId(),
                                event.costUsd(),
                                event.durationMs(),
                                codexProcess.getConfig().model(),
                                false
                            ));
                        }
                        case "error" -> sender.accept(ChatEvent.error(event.content()));
                        case "prompt" -> sender.accept(ChatEvent.prompt(
                            event.promptId(),
                            event.content(),
                            event.promptType(),
                            event.options()
                        ));
                        default -> {
                            // Ignore other event types
                        }
                    }
                }

                @Override
                public void onError(String line) {
                    logger.fine(() -> "stderr: " + line);
                }

                @Override
                public void onComplete(int exitCode) {
                    if (exitCode != 0) {
                        sender.accept(ChatEvent.error("Codex CLI exited with code: " + exitCode));
                    }
                }
            });
        } catch (IOException e) {
            logger.log(Level.WARNING, "Codex CLI failed", e);
            sender.accept(ChatEvent.error("Codex CLI error: " + e.getMessage()));
        }
    }

    /**
     * Sends a user response to an interactive prompt.
     */
    public void respond(String promptId, String response) throws IOException {
        codexProcess.writeUserMessage(response);
    }

    /** Cancels the currently running request. */
    public void cancel() {
        codexProcess.cancel();
        Thread t = activeThread;
        if (t != null) {
            t.interrupt();
        }
    }

    // --- Session file persistence ---

    CodexConfig restoreSession(CodexConfig config) {
        try {
            if (!Files.exists(sessionFile)) {
                return config;
            }
            String savedSessionId = null;
            String savedModel = null;
            for (String line : Files.readAllLines(sessionFile)) {
                if (line.startsWith("sessionId=")) {
                    savedSessionId = line.substring("sessionId=".length()).trim();
                } else if (line.startsWith("model=")) {
                    savedModel = line.substring("model=".length()).trim();
                }
            }
            if (savedSessionId != null && !savedSessionId.isEmpty()) {
                config = config.withSessionId(savedSessionId);
                logger.info("Restored session from file: " + savedSessionId);
            }
            if (savedModel != null && !savedModel.isEmpty()) {
                config = config.withModel(savedModel);
                logger.info("Restored model from file: " + savedModel);
            }
            return config;
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to read session file", e);
            return config;
        }
    }

    void saveSession(String sessionId, String model) {
        try {
            String content = "sessionId=" + sessionId + "\n"
                    + "model=" + (model != null ? model : "") + "\n";
            Files.writeString(sessionFile, content);
            logger.fine("Saved session to file: " + sessionId);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to write session file", e);
        }
    }

    void deleteSessionFile() {
        try {
            Files.deleteIfExists(sessionFile);
            logger.info("Session file deleted");
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to delete session file", e);
        }
    }
}
