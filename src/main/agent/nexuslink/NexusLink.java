package nexuslink;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.security.CodeSource;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

public final class NexusLink {
    private static Instrumentation inst;
    private static String token;
    private static String hintJar;

    private NexusLink() { }

    public static void premain(String args, Instrumentation instrumentation) {
        try {
            if (args == null) return;
            String[] parts = args.split(":", 3);
            if (parts.length > 2 && parts[2].length() > 0) hintJar = parts[2];
            final int port = Integer.parseInt(parts[0]);
            token = parts[1];
            inst = instrumentation;
            final ServerSocket server = new ServerSocket(port, 4, InetAddress.getByName("127.0.0.1"));
            Thread t = new Thread(new Runnable() {
                public void run() { serve(server); }
            }, "nexus-link");
            t.setDaemon(true);
            t.start();
        } catch (Throwable ignored) {
        }
    }

    private static void serve(ServerSocket server) {
        while (true) {
            try {
                Socket s = server.accept();
                s.setSoTimeout(5000);
                handle(s);
            } catch (Throwable e) {
                if (server.isClosed()) return;
            }
        }
    }

    private static void handle(Socket s) {
        String reply;
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            String[] req = String.valueOf(in.readLine()).split(" ");
            if (req.length < 4 || !"CONNECT".equals(req[0]) || !token.equals(req[1])) reply = "ERR bad request";
            else reply = connect(req[2], Integer.parseInt(req[3]));
        } catch (Throwable e) {
            reply = "ERR " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        try {
            OutputStream out = s.getOutputStream();
            out.write((reply + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            s.close();
        } catch (Throwable ignored) { }
    }

    private static Class<?> mcClass;
    private static Method getInstance, startConnecting;

    private static boolean isGameClass(Class<?> c) {
        String n = c.getName();
        return !c.isArray() && !c.isPrimitive() && !c.isInterface() && (n.indexOf('.') < 0 || n.startsWith("net.minecraft."));
    }

    private static synchronized void discover() throws Exception {
        if (startConnecting != null) return;
        Class<?>[] all = inst.getAllLoadedClasses();
        List<Class<?>> game = new ArrayList<Class<?>>();
        for (Class<?> c : all) if (isGameClass(c)) game.add(c);
        for (Class<?> c : game) {
            try {
                if (Modifier.isAbstract(c.getModifiers()) || !Executor.class.isAssignableFrom(c)) continue;
                for (Method m : c.getDeclaredMethods()) {
                    if (Modifier.isStatic(m.getModifiers()) && m.getParameterTypes().length == 0 && m.getReturnType() == c) {
                        mcClass = c; getInstance = m; m.setAccessible(true); break;
                    }
                }
                if (mcClass != null) break;
            } catch (Throwable ignored) { }
        }
        if (mcClass == null) throw new IllegalStateException("игровой класс не найден");
        for (Class<?> c : game) if (tryClass(c)) break;
        int scanned = -1;
        String jarUsed = null;
        if (startConnecting == null) {
            List<String> names = new ArrayList<String>();
            jarUsed = scanJars(names);
            scanned = names.size();
            ClassLoader loader = mcClass.getClassLoader();
            for (String name : names) {
                try { if (tryClass(Class.forName(name, false, loader))) break; } catch (Throwable ignored) { }
            }
        }
        if (startConnecting == null)
            throw new IllegalStateException("экран подключения не найден (игра " + mcClass.getName() + ", файлов проверено " + scanned + ", jar " + jarUsed + ")");
    }

    private static boolean tryClass(Class<?> c) {
        try {
            for (Method m : c.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (!Modifier.isStatic(m.getModifiers()) || p.length < 4 || p[1] != mcClass) continue;
                if (findCtor(p[2], String.class, int.class) == null || stringStringCtor(p[3]) == null) continue;
                startConnecting = m; m.setAccessible(true); return true;
            }
        } catch (Throwable ignored) { }
        return false;
    }

    private static String scanJars(List<String> out) {
        List<File> jars = new ArrayList<File>();
        try {
            CodeSource cs = mcClass.getProtectionDomain().getCodeSource();
            if (cs != null && cs.getLocation() != null) jars.add(new File(cs.getLocation().toURI()));
        } catch (Throwable ignored) { }
        if (hintJar != null) jars.add(new File(hintJar));
        byte[] needle = ("L" + mcClass.getName().replace('.', '/') + ";").getBytes(StandardCharsets.ISO_8859_1);
        String used = null;
        for (File f : jars) {
            if (!f.isFile()) continue;
            JarFile jf = null;
            try {
                jf = new JarFile(f);
                java.util.Enumeration<JarEntry> en = jf.entries();
                while (en.hasMoreElements()) {
                    JarEntry e = en.nextElement();
                    String n = e.getName();
                    if (!n.endsWith(".class") || e.getSize() > 400000 || n.startsWith("META-INF")) continue;
                    InputStream in = jf.getInputStream(e);
                    try {
                        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
                        byte[] chunk = new byte[8192];
                        int r;
                        while ((r = in.read(chunk)) > 0) buf.write(chunk, 0, r);
                        if (indexOf(buf.toByteArray(), needle) >= 0) out.add(n.substring(0, n.length() - 6).replace('/', '.'));
                    } finally { in.close(); }
                }
                used = f.getName();
                if (!out.isEmpty()) break;
            } catch (Throwable ignored) {
            } finally {
                if (jf != null) try { jf.close(); } catch (Throwable ignored) { }
            }
        }
        return used;
    }

    private static int indexOf(byte[] data, byte[] needle) {
        outer:
        for (int i = 0; i <= data.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) if (data[i + j] != needle[j]) continue outer;
            return i;
        }
        return -1;
    }

    private static Constructor<?> findCtor(Class<?> c, Class<?>... types) {
        try { Constructor<?> k = c.getDeclaredConstructor(types); k.setAccessible(true); return k; }
        catch (Throwable e) { return null; }
    }

    private static Constructor<?> stringStringCtor(Class<?> c) {
        try {
            for (Constructor<?> k : c.getDeclaredConstructors()) {
                Class<?>[] p = k.getParameterTypes();
                if (p.length >= 2 && p[0] == String.class && p[1] == String.class) { k.setAccessible(true); return k; }
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private static Object defaultFor(Class<?> t) {
        if (t == boolean.class) return Boolean.FALSE;
        if (t == int.class) return Integer.valueOf(0);
        if (t == long.class) return Long.valueOf(0);
        if (t.isEnum()) {
            Object[] values = t.getEnumConstants();
            for (Object v : values) if ("OTHER".equals(((Enum<?>) v).name())) return v;
            return values.length > 0 ? values[0] : null;
        }
        return null;
    }

    private static Object newServerData(Class<?> type, String name, String address) throws Exception {
        Constructor<?> k = stringStringCtor(type);
        Class<?>[] p = k.getParameterTypes();
        Object[] args = new Object[p.length];
        args[0] = name;
        args[1] = address;
        for (int i = 2; i < p.length; i++) args[i] = defaultFor(p[i]);
        return k.newInstance(args);
    }

    private static Field fieldOfType(Class<?> owner, Class<?> type) {
        for (Field f : owner.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers()) && f.getType() == type) { f.setAccessible(true); return f; }
        }
        return null;
    }

    private static Field levelField() {
        Field found = null;
        for (Field f : mcClass.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
            Class<?> sup = f.getType().getSuperclass();
            if (sup != null && Modifier.isAbstract(sup.getModifiers()) && AutoCloseable.class.isAssignableFrom(sup)
                    && !Modifier.isAbstract(f.getType().getModifiers())) {
                if (found != null) return null;
                found = f;
            }
        }
        if (found != null) found.setAccessible(true);
        return found;
    }

    private static String connect(final String host, final int port) throws Exception {
        discover();
        final Object mc = getInstance.invoke(null);
        final Class<?>[] p = startConnecting.getParameterTypes();
        final Field screenField = fieldOfType(mcClass, p[0]);
        if (mc == null || screenField == null) return "ERR игра ещё не готова";
        final Object address = findCtor(p[2], String.class, int.class).newInstance(host, Integer.valueOf(port));
        final Object data = newServerData(p[3], host, host + ":" + port);
        final String[] result = {"ERR не выполнено"};
        final CountDownLatch done = new CountDownLatch(1);
        ((Executor) mc).execute(new Runnable() {
            public void run() {
                try {
                    Field level = levelField();
                    boolean inWorld = level != null && level.get(mc) != null;
                    Object parent = screenField.get(mc);
                    if (inWorld) {
                        parent = leaveWorld(mc, p[0], screenField, parent);
                    } else if (level == null && parent == null) {
                        result[0] = "ERR не понятно, идёт ли игра в мире — не рискую";
                        return;
                    }
                    if (parent == null) { result[0] = "ERR нет экрана для возврата"; return; }
                    Object[] args = new Object[p.length];
                    args[0] = parent; args[1] = mc; args[2] = address; args[3] = data;
                    for (int i = 4; i < p.length; i++) args[i] = defaultFor(p[i]);
                    startConnecting.invoke(null, args);
                    result[0] = "OK";
                } catch (Throwable e) {
                    Throwable c = e.getCause() != null ? e.getCause() : e;
                    result[0] = "ERR " + c.getClass().getSimpleName() + ": " + c.getMessage();
                } finally {
                    done.countDown();
                }
            }
        });
        if (!done.await(40, TimeUnit.SECONDS)) return "ERR игра не ответила вовремя";
        return result[0];
    }

    private static Object leaveWorld(Object mc, Class<?> screenType, Field screenField, Object current) throws Exception {
        Method best = null;
        for (Method m : mcClass.getDeclaredMethods()) {
            if (!"disconnect".equals(m.getName()) || Modifier.isStatic(m.getModifiers())) continue;
            if (best == null || m.getParameterTypes().length < best.getParameterTypes().length) best = m;
        }
        if (best == null) throw new IllegalStateException("выход из мира для этой версии неизвестен");
        best.setAccessible(true);
        Object title = null;
        try {
            Class<?> titleClass = Class.forName("net.minecraft.client.gui.screens.TitleScreen", true, mc.getClass().getClassLoader());
            title = titleClass.getDeclaredConstructor().newInstance();
        } catch (Throwable ignored) { }
        Class<?>[] bp = best.getParameterTypes();
        Object[] args = new Object[bp.length];
        for (int i = 0; i < bp.length; i++) args[i] = bp[i] == screenType ? (title != null ? title : current) : defaultFor(bp[i]);
        best.invoke(mc, args);
        Object now = screenField.get(mc);
        return now != null ? now : (title != null ? title : current);
    }
}
