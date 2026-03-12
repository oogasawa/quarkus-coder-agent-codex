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

package com.github.oogasawa.coderagent.command;

import com.github.oogasawa.coderagent.codex.CodexConfig;
import com.github.oogasawa.coderagent.codex.CodexProcess;
import com.github.oogasawa.coderagent.rest.ChatEvent;

import java.util.function.Consumer;

/**
 * Handles slash commands from the Web UI (adapted from ReplCommandHandler).
 *
 * <p>Supported commands: /model, /session, /clear, /help</p>
 *
 * @author Osamu Ogasawara
 */
public class SlashCommandHandler {

    private final CodexProcess codexProcess;

    /**
     * Constructs a SlashCommandHandler.
     *
     * @param codexProcess the CodexProcess to configure
     */
    public SlashCommandHandler(CodexProcess codexProcess) {
        this.codexProcess = codexProcess;
    }

    /**
     * Checks if the input is a slash command.
     *
     * @param input user input
     * @return true if the input starts with /
     */
    public boolean isCommand(String input) {
        return input != null && input.startsWith("/");
    }

    /**
     * Handles a slash command and sends response messages via the callback.
     *
     * @param input the command input (e.g., "/model opus")
     * @param sender callback for sending ChatEvent responses
     */
    public void handle(String input, Consumer<ChatEvent> sender) {
        String[] parts = input.trim().split("\\s+", 2);
        String command = parts[0].toLowerCase();
        String args = parts.length > 1 ? parts[1].trim() : "";

        switch (command) {
            case "/model" -> handleModel(args, sender);
            case "/clear" -> handleClear(sender);
            case "/session" -> handleSession(args, sender);
            case "/help", "/?" -> handleHelp(sender);
            default -> sender.accept(ChatEvent.error("Unknown command: " + command + " (type /help for available commands)"));
        }
    }

    private void handleModel(String args, Consumer<ChatEvent> sender) {
        if (args.isEmpty()) {
            sender.accept(ChatEvent.info("Current model: " + codexProcess.getConfig().model()));
        } else {
            CodexConfig newConfig = codexProcess.getConfig().withModel(args);
            codexProcess.setConfig(newConfig);
            sender.accept(ChatEvent.info("Model changed to: " + args));
        }
    }

    private void handleClear(Consumer<ChatEvent> sender) {
        CodexConfig config = codexProcess.getConfig();
        CodexConfig newConfig = new CodexConfig(
            config.model(), config.systemPrompt(), config.maxTurns(),
            config.workingDir(), null, false, config.allowedTools()
        );
        codexProcess.setConfig(newConfig);
        sender.accept(ChatEvent.info("Session cleared. Starting fresh conversation."));
    }

    private void handleSession(String args, Consumer<ChatEvent> sender) {
        if (args.isEmpty()) {
            String sessionId = codexProcess.getLastSessionId();
            if (sessionId != null) {
                sender.accept(ChatEvent.info("Current session: " + sessionId));
            } else {
                sender.accept(ChatEvent.info("No active session."));
            }
        } else {
            CodexConfig newConfig = codexProcess.getConfig().withSessionId(args);
            codexProcess.setConfig(newConfig);
            sender.accept(ChatEvent.info("Session set to: " + args));
        }
    }

    private void handleHelp(Consumer<ChatEvent> sender) {
        String help = """
            Available commands:
              /help, /?          Show this help
              /model [name]      Show or change the model
              /session [id]      Show or set session ID
              /clear             Clear session (start fresh)""";
        sender.accept(ChatEvent.info(help));
    }
}
