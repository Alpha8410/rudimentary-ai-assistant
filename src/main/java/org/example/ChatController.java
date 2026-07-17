package org.example;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Scanner;

import javafx.fxml.FXML;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;


public class ChatController {
    //Pulls apikey from config file obtained during first application start
    private String apiKey = "";
    private static final java.io.File CONFIG_FILE = new java.io.File("groq_config.txt");
    private final java.util.List<String[]> chatHistory = new java.util.ArrayList<>();


    //declaration of window elements

    @FXML
    private TextArea chatArea;

    @FXML
    private TextField inputField;
    //send handler : formats input, reads console commands
    @FXML
    private void handleSend() {
        String prompt = inputField.getText();

        if (prompt != null && !prompt.trim().isEmpty()) {
            prompt = prompt.trim();

            // 1.CONSOLE COMMANDS
            if (prompt.startsWith("/")) {
                String[] parts = prompt.split(" ", 2);
                String command = parts[0].toLowerCase();

                if (command.equals("/setkey")) {
                    if (parts.length > 1 && !parts[1].trim().isEmpty()) {
                        this.apiKey = parts[1].trim();
                        try (java.io.PrintWriter writer = new java.io.PrintWriter(CONFIG_FILE)) {
                            writer.println(this.apiKey);
                            chatArea.appendText("System: API Key updated successfully!\n\n");
                        } catch (Exception e) {
                            chatArea.appendText("System Error: Could not save key: " + e.getMessage() + "\n\n");
                        }
                    } else {
                        chatArea.appendText("System: Usage is /setkey <your_api_key>\n\n");
                    }
                } else if (command.equals("/clearfile")) {
                    if (CONFIG_FILE.delete()) {
                        this.apiKey = "";
                        chatArea.appendText("System: Config file deleted.\n\n");
                    } else {
                        chatArea.appendText("System: No config file found to delete.\n\n");
                    }
                } else if (command.equals("/clear")) {
                    chatHistory.clear();
                    chatArea.appendText("System: Chat history and context cleared!\n\n");
                } else {
                    chatArea.appendText("System Error: Unknown command '" + command + "'\n\n");
                }

                inputField.clear();
                return;
            }

            // 2.REGULAR CHAT HANDLING
            chatArea.appendText("You: " + prompt + "\n");
            chatArea.appendText("AI: *Thinking...*\n");

            // Pin the prompt string to a final variable so the background thread can safely use it
            final String finalPrompt = prompt;
            inputField.clear();

            // Run the network task on a separate thread so your UI doesn't freeze!
            new Thread(() -> {
                try {
                    String responseBody = ApikeyAnswer(finalPrompt);

                    // Extract raw string content
                    String startMarker = "\"content\":\"";
                    int startIdx = responseBody.indexOf(startMarker);

                    if (startIdx != -1) {
                        startIdx += startMarker.length();
                        int endIdx = responseBody.indexOf("\",\"logprobs\"", startIdx);
                        if (endIdx == -1) endIdx = responseBody.indexOf("\"},\"logprobs\"", startIdx);
                        if (endIdx == -1) endIdx = responseBody.indexOf("\"", startIdx);

                        if (endIdx != -1 && endIdx > startIdx) {
                            String cleanResponse = responseBody.substring(startIdx, endIdx);
                            cleanResponse = cleanResponse.replace("\\n", "\n")
                                    .replace("\\\"", "\"")
                                    .replace("\\\\", "\\");

                            // Save clean response to history
                            chatHistory.add(new String[]{"assistant", cleanResponse});

                            // Update the UI smoothly
                            final String finalResponse = cleanResponse;
                            javafx.application.Platform.runLater(() -> {
                                String currentText = chatArea.getText();
                                if (currentText.contains("AI: *Thinking...*")) {
                                    String baseText = currentText.substring(0, currentText.lastIndexOf("AI: *Thinking...*"));
                                    chatArea.setText(baseText);
                                }
                                chatArea.appendText("AI: " + finalResponse + "\n\n");
                            });
                        } else {
                            javafx.application.Platform.runLater(() -> chatArea.appendText("\nSystem Error: Parse boundaries lost.\n\n"));
                        }
                    } else {
                        //DYNAMIC ERROR FALLBACK
                        // Instead of failing silently, print exactly what Groq returned
                        javafx.application.Platform.runLater(() -> {
                            String currentText = chatArea.getText();
                            if (currentText.contains("AI: *Thinking...*")) {
                                String baseText = currentText.substring(0, currentText.lastIndexOf("AI: *Thinking...*"));
                                chatArea.setText(baseText);
                            }
                            chatArea.appendText("System Error (Raw Response from Groq):\n" + responseBody + "\n\n");
                        });
                    }

                } catch (Exception e) {
                    javafx.application.Platform.runLater(() -> chatArea.appendText("\nSystem Error: " + e.getMessage() + "\n\n"));
                }
            }).start();
        }
    }


    public String ApikeyAnswer(String AIreadyPrompt) throws Exception {
        if (chatHistory.isEmpty() || !chatHistory.get(chatHistory.size() - 1)[1].equals(AIreadyPrompt)) {
            chatHistory.add(new String[]{"user", AIreadyPrompt});
        }

        var client = java.net.http.HttpClient.newHttpClient();

        while (true) {
            StringBuilder messagesJson = new StringBuilder();

            // wip:workspace access is still wip line:207
            messagesJson.append("{\"role\":\"system\",\"content\":\"You have workspace access. To read or write files, use this exact syntax on a separate line: [TOOL_CALL:action=read,path=filename] or [TOOL_CALL:action=write,path=filename,content=file_contents]. Use relative paths. When writing code, provide the full, unescaped text directly inside the content attribute.\"},");

            for (int i = 0; i < chatHistory.size(); i++) {
                String[] msg = chatHistory.get(i);
                String safeContent = msg[1].replace("\\", "\\\\").replace("\"", "\\\"");
                messagesJson.append(String.format("{\"role\":\"%s\",\"content\":\"%s\"}", msg[0], safeContent));
                if (i < chatHistory.size() - 1) messagesJson.append(",");
            }

            String jsonPayload = String.format("""
        {
          "model": "llama-3.1-8b-instant",
          "messages": [%s],
          "temperature": 0.1
        }
        """, messagesJson.toString());

            var request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create("https://api.groq.com/openai/v1/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + this.apiKey)
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(jsonPayload))
                    .build();

            var response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            String responseBody = response.body();

            //REGEX
            if (responseBody.contains("[TOOL_CALL:")) {
                // Regex patterns to clean groq output
                java.util.regex.Pattern actionPat = java.util.regex.Pattern.compile("action=(read|write)");
                java.util.regex.Pattern pathPat = java.util.regex.Pattern.compile("path=([^\\],\\s]+)");
                java.util.regex.Pattern contentPat = java.util.regex.Pattern.compile("content=(.+?)(?=\\]|$)");

                java.util.regex.Matcher actMatch = actionPat.matcher(responseBody);
                java.util.regex.Matcher pathMatch = pathPat.matcher(responseBody);
                java.util.regex.Matcher contentMatch = contentPat.matcher(responseBody);

                String action = actMatch.find() ? actMatch.group(1) : "";
                String path = pathMatch.find() ? pathMatch.group(1) : "";
                String content = contentMatch.find() ? contentMatch.group(1) : "";

                if (!action.isEmpty() && !path.isEmpty()) {
                    // Run the disk action
                    String result = executeFileAction(action, path, content);

                    // Force feed the real disk string straight into the conversation history
                    chatHistory.add(new String[]{"assistant", "[TOOL_CALL: action=" + action + ", path=" + path + "]"});
                    chatHistory.add(new String[]{"user", "System Workspace Result for " + path + ":\n" + result});

                    // Instantly loop back up to send the genuine source code string right back to the model!
                    continue;
                }
            }

            return responseBody;
        }
    }
    //wip:currently ai hallucinates random files big waste of time prolly
    private String executeFileAction(String action, String path, String content) {
        java.io.File file = new java.io.File(path);
        try {
            if ("read".equals(action)) {
                if (!file.exists()) return "Error: File does not exist at " + path;
                return java.nio.file.Files.readString(file.toPath());
            } else if ("write".equals(action)) {
                // Ensure parent directories exist
                if (file.getParentFile() != null) file.getParentFile().mkdirs();
                java.nio.file.Files.writeString(file.toPath(), content);
                return "Success: File written to " + path;
            }
        } catch (Exception e) {
            return "Error executing file operation: " + e.getMessage();
        }
        return "Error: Unknown action";
    }
}