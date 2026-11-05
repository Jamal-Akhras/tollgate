package io.github.jamalakhras.tollgate.benchmarks;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

public final class Workload {

    public static final int SMALL = 2 * 1024;
    public static final int LARGE = 32 * 1024;
    public static final int TERMS = 1_000;

    private static final String SYSTEM = "You are a support agent for a payments company. Answer briefly and politely,"
            + " and never repeat card numbers back to the customer.";
    private static final List<String> TURNS = List.of(
            "Hi, I was charged twice for order 88213 on my card 4111 1111 1111 1111. Can you refund one of them?",
            "I am sorry about that. I can see two charges for order 88213. I will refund the second one now.",
            "Thanks. Please send the confirmation to jane.doe@example.com, or call me on +44 20 7946 0958.",
            "Done. The refund will reach your account in three to five working days, and the receipt is on its way.",
            "One more thing: our account manager at Northwind Traders said the invoice for Client 417 was wrong.",
            "I have opened a ticket for the Northwind Traders invoice and copied in accounts@example.org.");
    private static final List<String> CLEAN_TURNS = List.of(
            "Hi, I was charged twice for an order last week. Can you refund one of them?",
            "I am sorry about that. I can see two charges for that order. I will refund the second one now.",
            "Thanks. Please send the confirmation by email when it is done.",
            "Done. The refund will reach your account in three to five working days, and the receipt is on its way.",
            "One more thing: our account manager said the last invoice was wrong.",
            "I have opened a ticket for the invoice and asked the accounts team to check it.");
    private static final String ANSWER = ("Thanks for the details. I have drafted a reply to [EMAIL_1] that confirms"
                    + " the refund, explains the timeline and lists the next steps. ")
            .repeat(8);
    private static final int STREAM_EVENTS = 20;

    private Workload() {}

    public static String chatRequest(int bytes, boolean stream) {
        return chat(bytes, stream, TURNS);
    }

    public static String cleanChatRequest(int bytes) {
        return chat(bytes, false, CLEAN_TURNS);
    }

    public static String completion() {
        return "{\"id\":\"chatcmpl-stub\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"stub\","
                + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"" + ANSWER
                + "\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":512,\"completion_tokens\":200,"
                + "\"total_tokens\":712}}";
    }

    public static List<String> streamEvents() {
        String[] words = ANSWER.split(" ", -1);
        int perEvent = Math.ceilDiv(words.length, STREAM_EVENTS);
        List<String> events = new ArrayList<>();
        for (int event = 0; event < STREAM_EVENTS; event++) {
            int from = Math.min(words.length, event * perEvent);
            int to = Math.min(words.length, from + perEvent);
            String text = String.join(" ", Arrays.copyOfRange(words, from, to)) + " ";
            events.add("data: {\"id\":\"chatcmpl-stub\",\"object\":\"chat.completion.chunk\",\"created\":1,"
                    + "\"model\":\"stub\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"" + text
                    + "\"},\"finish_reason\":" + (event == STREAM_EVENTS - 1 ? "\"stop\"" : "null") + "}]}");
        }
        events.add("data: {\"id\":\"chatcmpl-stub\",\"object\":\"chat.completion.chunk\",\"created\":1,"
                + "\"model\":\"stub\",\"choices\":[],\"usage\":{\"prompt_tokens\":512,\"completion_tokens\":200,"
                + "\"total_tokens\":712}}");
        events.add("data: [DONE]");
        return events;
    }

    private static String chat(int bytes, boolean stream, List<String> turns) {
        StringBuilder messages = new StringBuilder("{\"role\":\"system\",\"content\":\"" + SYSTEM + "\"}");
        int turn = 0;
        while (messages.length() < bytes - 200) {
            String role = turn % 2 == 0 ? "user" : "assistant";
            messages.append(",{\"role\":\"")
                    .append(role)
                    .append("\",\"content\":\"")
                    .append(turns.get(turn % turns.size()))
                    .append("\"}");
            turn++;
        }
        if (turn % 2 == 1) {
            messages.append(",{\"role\":\"assistant\",\"content\":\"How else can I help?\"}");
        }
        messages.append(",{\"role\":\"user\",\"content\":\"Can you summarise what happens next?\"}");
        return "{\"model\":\"stub\",\"max_tokens\":256," + (stream ? "\"stream\":true," : "") + "\"messages\":["
                + messages + "]}";
    }

    public static List<String> terms() {
        return IntStream.range(0, TERMS)
                .mapToObj(i -> i == 0 ? "Northwind Traders" : "Client " + i)
                .toList();
    }

    public static void main(String[] args) throws IOException {
        Path directory = Path.of(args[0]);
        Files.createDirectories(directory);
        write(directory.resolve("request-2kb.json"), chatRequest(SMALL, false));
        write(directory.resolve("request-32kb.json"), chatRequest(LARGE, false));
        write(directory.resolve("request-2kb-stream.json"), chatRequest(SMALL, true));
        write(directory.resolve("terms.txt"), String.join("\n", terms()) + "\n");
    }

    private static void write(Path file, String content) throws IOException {
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
