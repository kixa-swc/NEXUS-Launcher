package com.launcher.chat;

import com.launcher.chat.ChatService.ChatException;
import com.launcher.chat.ChatService.Conn;
import com.launcher.chat.ChatService.Message;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

public final class ChatSelfTest {
    static int passed, failed;
    static final List<String> failures = new ArrayList<>();

    static void check(String name, boolean ok) {
        if (ok) { passed++; System.out.println("  ✔ " + name); }
        else { failed++; failures.add(name); System.out.println("  ✘ ПРОВАЛ: " + name); }
    }

    static boolean await(BooleanSupplier c, long ms) {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            try { if (c.getAsBoolean()) return true; } catch (RuntimeException ignored) { }
            try { Thread.sleep(20); } catch (InterruptedException e) { return false; }
        }
        try { return c.getAsBoolean(); } catch (RuntimeException e) { return false; }
    }

    interface Thrower { void run() throws Exception; }

    static String err(Thrower r) {
        try { r.run(); return "NO_ERROR"; } catch (ChatException e) { return e.code(); } catch (Exception e) { return "EXC:" + e; }
    }

    static final class Rec implements ChatService.Listener {
        final List<Message> got = new CopyOnWriteArrayList<>();
        final List<String> notices = new CopyOnWriteArrayList<>();
        final List<String> reads = new CopyOnWriteArrayList<>();
        @Override public void onMessage(Message m, boolean mine) { if (!mine) got.add(m); }
        @Override public void onNotice(String t) { notices.add(t); }
        @Override public void onRead(String id, long upTo) { reads.add(id + ":" + upTo); }
    }

    static ChatService client(MemoryRelay relay, File dir, String file) {
        return new ChatService(new File(dir, file), url -> relay);
    }

    public static void main(String[] args) throws Exception {
        File dir = Files.createTempDirectory("nexus-chat-test").toFile();
        MemoryRelay relay = new MemoryRelay();

        System.out.println("— Подключение —");
        ChatService a = client(relay, dir, "a.json"), b = client(relay, dir, "b.json"), c = client(relay, dir, "c.json");
        Rec ra = new Rec(), rb = new Rec(), rc = new Rec();
        a.addListener(ra); b.addListener(rb); c.addListener(rc);
        check("до настройки чат не настроен", !a.isConfigured() && a.connection() == Conn.NOT_CONFIGURED);
        check("слишком короткий ник отклоняется", "bad_name".equals(err(() -> a.setup("", "x"))));
        a.setup("", "Alice"); b.setup("", "Bob"); c.setup("", "Carol");
        check("выдан ID нужного вида", a.myId().matches("NX-[A-Z0-9]{4}-[A-Z0-9]{4}") && ChatServer.normalizeId(a.myId()) != null);
        check("у всех разные ID", !a.myId().equals(b.myId()) && !b.myId().equals(c.myId()));
        check("ник сохранён", "Alice".equals(a.myName()));
        check("подключились к relay", await(() -> a.connection() == Conn.ONLINE && b.connection() == Conn.ONLINE, 3000));

        System.out.println("— Заявки в друзья —");
        check("неверный ID", "bad_id".equals(err(() -> a.addFriend("абв"))));
        check("нельзя добавить себя", "self".equals(err(() -> a.addFriend(a.myId()))));
        check("заявка отправлена", "requested".equals(a.addFriend(b.myId().toLowerCase())));
        check("у Боба входящая заявка с ником Alice", await(() -> b.incoming().size() == 1 && b.incoming().get(0).name().equals("Alice"), 3000));
        check("Боб получил уведомление", await(() -> !rb.notices.isEmpty(), 2000));
        check("у Алисы исходящая заявка", a.outgoing().size() == 1);
        check("Боб принимает", err(() -> b.respond(a.myId(), true)).equals("NO_ERROR"));
        check("Алиса и Боб друзья", await(() -> a.friends().size() == 1 && b.friends().size() == 1 && a.outgoing().isEmpty() && b.incoming().isEmpty(), 3000));
        check("Алиса видит имя друга Bob", "Bob".equals(a.friends().get(0).name()));
        check("повторное добавление отклоняется", "already".equals(err(() -> a.addFriend(b.myId()))));
        check("старая заявка уже не актуальна", "no_request".equals(err(() -> b.respond(a.myId(), true))));

        System.out.println("— Сообщения —");
        check("отправка не друзьям запрещена", "not_friend".equals(err(() -> a.send(c.myId(), "привет"))));
        a.setActiveChat(null);
        Message m1 = a.send(b.myId(), "Привет, Боб! 👋");
        check("Боб получил сообщение", await(() -> rb.got.size() == 1 && rb.got.get(0).text().equals("Привет, Боб! 👋"), 3000));
        check("у Боба 1 непрочитанное", b.friend(a.myId()).unread() == 1 && b.totalUnread() == 1);
        check("номера сообщений совпадают у обоих", rb.got.get(0).seq() == m1.seq());
        check("у Алисы сообщение в истории", a.conversation(b.myId()).size() == 1);
        b.markRead(a.myId());
        check("непрочитанных нет", b.totalUnread() == 0);
        check("Алиса получила «прочитано»", await(() -> a.friend(b.myId()).peerRead() >= m1.seq() && !ra.reads.isEmpty(), 3000));
        b.setActiveChat(a.myId());
        a.send(b.myId(), "ещё одно");
        check("при открытом диалоге не копятся непрочитанные", await(() -> b.conversation(a.myId()).size() == 2, 3000) && b.totalUnread() == 0);
        check("и прочтение доходит автоматически", await(() -> a.friend(b.myId()).peerRead() >= a.conversation(b.myId()).get(1).seq(), 3000));
        b.send(a.myId(), "Привет, Алиса");
        check("ответ дошёл", await(() -> ra.got.size() == 1, 3000));
        check("пустое сообщение отклоняется", "empty".equals(err(() -> a.send(b.myId(), "   "))));

        System.out.println("— Длинные сообщения —");
        String longText = "Привет! ".repeat(250);
        a.send(b.myId(), longText.strip());
        check("длинное сообщение разбито и собрано целиком",
                await(() -> b.conversation(a.myId()).stream().filter(m -> m.from().equals(a.myId())).mapToInt(m -> m.text().length()).sum() >= 2000 - 3 + 12, 4000));
        check("больше 2000 символов нельзя", "too_long".equals(err(() -> a.send(b.myId(), "я".repeat(2001)))));

        System.out.println("— Присутствие —");
        check("Алиса видит, что Боб в сети", await(() -> a.friend(b.myId()).online(), 3000));
        b.stop();
        check("после выхода Боб офлайн", await(() -> !a.friend(b.myId()).online(), 3000));

        System.out.println("— Офлайн-доставка и перезапуск —");
        a.send(b.myId(), "пока тебя не было");
        ChatService b2 = client(relay, dir, "b.json");
        Rec rb2 = new Rec();
        b2.addListener(rb2);
        check("состояние Боба восстановлено с диска", b2.isConfigured() && b2.friends().size() == 1 && b2.conversation(a.myId()).size() >= 4);
        int before = b2.conversation(a.myId()).size();
        b2.start();
        check("пропущенное сообщение дошло", await(() -> b2.conversation(a.myId()).stream().anyMatch(m -> m.text().equals("пока тебя не было")), 3000));
        check("повторов нет после перезапуска", b2.conversation(a.myId()).size() == before + 1);
        b2.stop(); b2.start();
        check("и после ещё одного перезапуска", await(() -> b2.connection() == Conn.ONLINE, 3000) && b2.conversation(a.myId()).size() == before + 1);

        System.out.println("— Ник —");
        a.rename("Alicia");
        check("новый ник виден у друга", await(() -> "Alicia".equals(b2.friend(a.myId()).name()), 3000));

        System.out.println("— Безопасность —");
        String foreign = ChatService.seal(c.myId(), "{\"v\":1,\"t\":\"msg\",\"id\":\"x1\",\"from\":\"" + a.myId() + "\",\"name\":\"Alicia\",\"ts\":1,\"text\":\"секрет\"}");
        check("содержимое на relay не читается без ID", relay.published.stream().noneMatch(p -> p.contains("Привет") || p.contains("Alice")));
        boolean cantOpen;
        try { ChatService.open(b.myId(), foreign); cantOpen = false; } catch (IllegalArgumentException e) { cantOpen = true; }
        check("чужое сообщение не расшифровывается чужим ID", cantOpen);
        relay.publish(ChatService.topicOf(b.myId()), ChatService.seal(b.myId(),
                "{\"v\":1,\"t\":\"msg\",\"id\":\"spam1\",\"from\":\"" + c.myId() + "\",\"name\":\"Спам\",\"ts\":" + System.currentTimeMillis() + ",\"text\":\"купи\"}"), true);
        Thread.sleep(300);
        check("сообщения от не-друзей игнорируются", b2.conversation(c.myId()).isEmpty() && b2.friend(c.myId()) == null);
        relay.publish(ChatService.topicOf(b.myId()), "мусор-не-шифр", true);
        check("мусор в ящике не ломает чат", await(() -> b2.connection() == Conn.ONLINE, 1000));

        System.out.println("— Отклонение, встречные заявки, удаление —");
        c.addFriend(a.myId());
        check("Алиса видит заявку Кэрол", await(() -> a.incoming().size() == 1, 3000));
        a.respond(c.myId(), false);
        check("отклонённая заявка исчезает у Кэрол", await(() -> c.outgoing().isEmpty() && a.incoming().isEmpty() && a.friends().size() == 1, 3000));
        a.addFriend(c.myId());
        c.addFriend(a.myId());
        check("встречные заявки сразу делают друзьями", await(() -> a.friend(c.myId()) != null && c.friend(a.myId()) != null, 3000));
        c.send(a.myId(), "мы друзья");
        check("после этого переписка работает", await(() -> ra.got.stream().anyMatch(m -> m.text().equals("мы друзья")), 3000));
        a.remove(c.myId());
        check("друг удалён у обоих", await(() -> a.friend(c.myId()) == null && c.friend(a.myId()) == null, 3000));
        check("Кэрол получила уведомление об удалении", await(() -> rc.notices.stream().anyMatch(n -> n.contains("удалил")), 3000));
        check("переписка удалена", a.conversation(c.myId()).isEmpty());
        check("после удаления сообщение не пройдёт", "not_friend".equals(err(() -> c.send(a.myId(), "эй"))));

        System.out.println("— Удаление чатов и сообщений без подтверждения —");
        b2.setActiveChat(null);
        a.addFriend(c.myId()); c.addFriend(a.myId());
        check("Алиса и Кэрол снова друзья", await(() -> a.friend(c.myId()) != null && c.friend(a.myId()) != null, 3000));
        a.send(c.myId(), "один"); a.send(c.myId(), "два"); c.send(a.myId(), "три");
        check("переписка A–C на месте у обоих", await(() -> a.conversation(c.myId()).size() == 3 && c.conversation(a.myId()).size() == 3, 3000));
        Message toKill = a.conversation(c.myId()).get(0);
        a.deleteMessage(c.myId(), toKill.seq(), false);
        check("«удалить у меня»: у Алисы исчезло, у Кэрол осталось", a.conversation(c.myId()).size() == 2 && c.conversation(a.myId()).size() == 3);
        Message mine2 = a.conversation(c.myId()).stream().filter(m -> m.from().equals(a.myId())).findFirst().get();
        a.deleteMessage(c.myId(), mine2.seq(), true);
        check("«удалить у обоих»: своё сообщение исчезло у Кэрол", await(() -> c.conversation(a.myId()).stream().noneMatch(m -> m.seq() == mine2.seq()), 3000));
        Message theirs = a.conversation(c.myId()).stream().filter(m -> m.from().equals(c.myId())).findFirst().get();
        check("чужое сообщение у обоих удалить нельзя", "not_yours".equals(err(() -> a.deleteMessage(c.myId(), theirs.seq(), true))));
        a.clearChat(c.myId(), false);
        check("чат удалён только у меня", a.conversation(c.myId()).isEmpty() && !c.conversation(a.myId()).isEmpty() && a.friend(c.myId()) != null);
        a.send(c.myId(), "после очистки");
        check("после очистки чат работает", await(() -> a.conversation(c.myId()).size() == 1 && c.conversation(a.myId()).stream().anyMatch(m -> m.text().equals("после очистки")), 3000));
        c.clearChat(a.myId(), true);
        check("чат удалён у обоих", await(() -> a.conversation(c.myId()).isEmpty() && c.conversation(a.myId()).isEmpty(), 3000));
        check("друзья остались друзьями", a.friend(c.myId()) != null && c.friend(a.myId()) != null && a.friend(c.myId()).unread() == 0);

        System.out.println("— Переписка переживает закрытие лаунчера —");
        a.send(c.myId(), "сохрани меня");
        check("сообщение дошло", await(() -> c.conversation(a.myId()).size() == 1, 3000));
        c.send(a.myId(), "и меня");
        check("ответ дошёл", await(() -> a.conversation(c.myId()).size() == 2, 3000));
        a.flush();
        ChatService a2 = client(relay, dir, "a.json");
        check("после перезапуска переписка на месте", a2.conversation(c.myId()).size() == 2 && a2.friends().size() == 2);
        a2.start();
        Thread.sleep(300);
        check("после подключения нет дублей", a2.conversation(c.myId()).size() == 2);
        a2.clearChat(c.myId(), false);
        a2.flush();
        ChatService a3 = client(relay, dir, "a.json");
        check("удаление тоже сохраняется на диск", a3.conversation(c.myId()).isEmpty());
        a2.stop();
        Files.writeString(new File(dir, "a.json").toPath(), "{битый файл");
        ChatService a4 = client(relay, dir, "a.json");
        check("битый файл не теряет чат: восстановление из резервной копии", a4.isConfigured() && a4.friends().size() == 2);
        a.stop(); a.start();
        a.remove(c.myId());
        check("друг удалён после теста", await(() -> a.friend(c.myId()) == null && c.friend(a.myId()) == null, 3000));

        System.out.println("— Нет интернета —");
        relay.down = true;
        check("отправка без связи — понятная ошибка", "network".equals(err(() -> a.send(b.myId(), "офлайн"))));
        check("неудачное сообщение не попало в историю", a.conversation(b.myId()).stream().noneMatch(m -> m.text().equals("офлайн")));
        check("заявка без связи откатывается", "network".equals(err(() -> a.addFriend(c.myId()))) && a.outgoing().isEmpty());
        relay.down = false;

        System.out.println("— Выход —");
        a.logout();
        check("после выхода чат не настроен", !a.isConfigured() && a.friends().isEmpty());
        check("файл состояния удалён", !new File(dir, "a.json").exists());

        b2.stop(); c.stop();
        System.out.println();
        System.out.println("Итого: пройдено " + passed + ", провалено " + failed);
        if (failed > 0) { System.out.println("Провалены: " + failures); System.exit(1); }
        System.exit(0);
    }
}
