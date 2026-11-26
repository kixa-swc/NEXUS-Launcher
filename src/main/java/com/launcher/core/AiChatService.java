package com.launcher.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public final class AiChatService {
    public static final String DEFAULT_ENDPOINT = "https://text.pollinations.ai/openai";
    public static final String DEFAULT_MODEL = "openai";

    public record Message(String role, String text) { }

    public static final class AiUnavailableException extends Exception {
        public AiUnavailableException(String message) { super(message); }
    }

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static final int HISTORY = 12;

    private AiChatService() { }

    public static String endpoint() { return clean(SettingsManager.getSetting("ai_endpoint", DEFAULT_ENDPOINT), DEFAULT_ENDPOINT); }
    public static String model() { return clean(SettingsManager.getSetting("ai_model", DEFAULT_MODEL), DEFAULT_MODEL); }
    public static String key() { return SettingsManager.getSetting("ai_key", "").trim(); }

    private static String clean(String v, String fallback) { return v == null || v.isBlank() ? fallback : v.trim(); }

    static String systemPrompt(HardwareInfo.Info hw, LocalAdvisor.Context ctx, String live) {
        StringBuilder sb = new StringBuilder();
        sb.append("Ты — ИИ-помощник лаунчера NEXUS для Minecraft Java Edition. Отвечай по-русски, коротко и по делу (до 200 слов), без воды. ")
          .append("Подбирай моды (называй точные названия с Modrinth), сборки модов, шейдеры, версию игры, объём выделяемой памяти и улучшения для ПК, ")
          .append("опираясь на железо пользователя ниже. ")
          .append("Учитывай версию игры и загрузчик (Fabric/Forge/NeoForge). Мод ставится во вкладке «Моды» лаунчера. ")
          .append("Если вопрос не про Minecraft или ПК — вежливо верни разговор к теме. Пиши простым текстом, списки через «•», без Markdown-заголовков.\n\n");
        sb.append("ПРАВИЛА АКТУАЛЬНОСТИ (важнее всего остального):\n")
          .append("• Твои собственные знания устарели: после их обновления вышли новые версии игры, моды и сборки. Блок «АКТУАЛЬНЫЕ ДАННЫЕ» ниже получен из интернета только что — ему верь больше, чем памяти.\n")
          .append("• Какая версия Minecraft последняя, что нового в игре — отвечай только по этому блоку. Не называй версию последней, если в блоке указано другое.\n")
          .append("• Ты следишь за новостями Mojang: умеешь отвечать про последний релиз, пре-релизы (например 26.4-pre1), релиз-кандидаты, снапшоты и свежие патчноуты — бери это из блока.\n")
          .append("• Выбор версии (ностальгия, сборки): опирайся на «ГАЙД ПО ВЫБОРУ ВЕРСИИ» в блоке, назови 1–2 версии, объясни почему, и добавь, что их можно выбрать в лаунчере.\n")
          .append("• Моды, сборки, шейдеры и ресурспаки рекомендуй только из списков в блоке и только если они поддерживают нужную версию игры и загрузчик. ")
          .append("Рядом с названием указывай версию игры и дату обновления, если они есть.\n")
          .append("• Если нужного нет в блоке или данных нет — прямо скажи, что не можешь подтвердить актуальность, и предложи проверить на modrinth.com. Не выдумывай и не вспоминай моды и сборки по памяти.\n")
          .append("• Если для новой версии подходящих проектов ещё мало или нет — скажи это и предложи ближайшую предыдущую стабильную версию.\n\n");
        sb.append("АКТУАЛЬНЫЕ ДАННЫЕ:\n").append(live == null || live.isBlank()
                ? "получить данные из интернета не удалось — не утверждай ничего о последних версиях, новостях, модах и сборках.\n" : live).append('\n');
        sb.append("ЖЕЛЕЗО ПОЛЬЗОВАТЕЛЯ:\n").append(hw == null ? "не определено" : hw.summary()).append('\n');
        if (hw != null) sb.append("Оценка ПК для Minecraft (0 слабый … 4 очень мощный): ").append(LocalAdvisor.overall(hw)).append('\n');
        if (ctx != null) {
            sb.append("\nВ ЛАУНЧЕРЕ СЕЙЧАС: версия игры ").append(ctx.mcVersion() == null ? "не выбрана" : ctx.mcVersion())
              .append(", загрузчик ").append(ctx.loader() == null ? "Vanilla" : ctx.loader())
              .append(", выделено памяти ").append(ctx.ramGb()).append(" ГБ\n");
        }
        return sb.toString();
    }

    private static String lastUserText(List<Message> history) {
        for (int i = history.size() - 1; i >= 0; i--) if ("user".equals(history.get(i).role())) return history.get(i).text();
        return "";
    }

    public static String ask(List<Message> history, String question, HardwareInfo.Info hw, LocalAdvisor.Context ctx) throws AiUnavailableException {
        JsonArray messages = new JsonArray();
        String live = LiveKnowledge.build(question + " " + lastUserText(history),
                ctx == null ? null : ctx.mcVersion(), ctx == null ? null : ctx.loader())
                + LiveAdvisor.versionGuideBlock(question);
        messages.add(msg("system", systemPrompt(hw, ctx, live)));
        List<Message> tail = new ArrayList<>(history.subList(Math.max(0, history.size() - HISTORY), history.size()));
        for (Message m : tail) messages.add(msg(m.role(), m.text()));
        messages.add(msg("user", question));

        List<String> models = new ArrayList<>();
        for (String m : new String[]{model(), DEFAULT_MODEL, "mistral", "llama"}) if (!models.contains(m)) models.add(m);
        AiUnavailableException last = null;
        for (String m : models) {
            try {
                return askModel(m, messages);
            } catch (AiUnavailableException e) {
                last = e;
            }
        }
        throw last != null ? last : new AiUnavailableException("Сервис недоступен");
    }

    private static String askModel(String model, JsonArray messages) throws AiUnavailableException {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.add("messages", messages);
        body.addProperty("temperature", 0.4);
        String payload = body.toString();

        AiUnavailableException last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(endpoint()))
                        .timeout(Duration.ofSeconds(45))
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));
                if (!key().isEmpty()) req.header("Authorization", "Bearer " + key());
                HttpResponse<String> resp = HTTP.send(req.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                int code = resp.statusCode();
                if (code == 429 || code == 502 || code == 503) {
                    last = new AiUnavailableException("Сервис занят (код " + code + ")");
                    Thread.sleep(2500L);
                    continue;
                }
                if (code / 100 != 2) throw new AiUnavailableException("Сервис ответил кодом " + code);
                String text = extract(resp.body());
                if (text == null || text.isBlank()) throw new AiUnavailableException("Пустой ответ сервиса");
                return text.trim();
            } catch (AiUnavailableException e) {
                throw e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AiUnavailableException("Прервано");
            } catch (Exception e) {
                throw new AiUnavailableException("Нет связи с сервисом: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            }
        }
        throw last != null ? last : new AiUnavailableException("Сервис недоступен");
    }

    private static JsonObject msg(String role, String text) {
        JsonObject o = new JsonObject();
        o.addProperty("role", role);
        o.addProperty("content", text);
        return o;
    }

    static String extract(String body) {
        if (body == null) return null;
        String trimmed = body.trim();
        if (!trimmed.startsWith("{")) return trimmed;
        try {
            JsonObject json = JsonParser.parseString(trimmed).getAsJsonObject();
            JsonArray choices = json.has("choices") ? json.getAsJsonArray("choices") : null;
            if (choices != null && choices.size() > 0) {
                JsonObject first = choices.get(0).getAsJsonObject();
                if (first.has("message")) {
                    JsonElement content = first.getAsJsonObject("message").get("content");
                    if (content != null && !content.isJsonNull()) return content.getAsString();
                }
                if (first.has("text") && !first.get("text").isJsonNull()) return first.get("text").getAsString();
            }
            if (json.has("error")) return null;
        } catch (RuntimeException ignored) { }
        return null;
    }

    public static String plain(String text) {
        if (text == null) return "";
        String t = text.replace("\r", "");
        t = t.replaceAll("(?m)^\\s*#{1,6}\\s*", "");
        t = t.replaceAll("\\*\\*(.+?)\\*\\*", "$1").replaceAll("__(.+?)__", "$1");
        t = t.replaceAll("(?m)^\\s*[-*]\\s+", "• ");
        t = t.replace("```", "").replace("`", "");
        return t.trim();
    }
}
