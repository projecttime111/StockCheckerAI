package com.stockpilot.ai.service;

import java.util.*;
import java.util.function.Function;

/** Providers receive only already-authorized history and invoke the server's allowlisted tools. */
public interface AiProvider {
    record Turn(String role, String content) { }
    record Tool(String name, String description, Map<String,Object> parameters) { }
    record Call(String name, Map<String,Object> arguments) { }
    record Completion(String answer, String provider, String model, int inputTokens, int outputTokens) { }
    String name();
    Completion complete(List<Turn> history, String prompt, List<Tool> tools,
        Function<Call,Map<String,Object>> execute);
}
