package com.criit.patchwallactions;

import android.content.Context;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.Cipher;

/**
 * Минимальный клиент adb к самому TV (127.0.0.1:5555). Так приложение выполняет то, что иначе
 * делается только с компьютера: выдаёт себе права, удаляет и возвращает PatchWall.
 * При первом подключении TV спрашивает "Разрешить отладку", ключ хранится в файлах приложения.
 */
final class LocalAdb implements AutoCloseable {

    static final String HOST = "127.0.0.1";
    static final int PORT = 5555;

    private static final int CNXN = 0x4e584e43;
    private static final int AUTH = 0x48545541;
    private static final int OPEN = 0x4e45504f;
    private static final int OKAY = 0x59414b4f;
    private static final int CLSE = 0x45534c43;
    private static final int WRTE = 0x45545257;

    private static final int VERSION = 0x01000000;
    private static final int MAX_DATA = 256 * 1024;
    private static final int AUTH_TOKEN = 1;
    private static final int AUTH_SIGNATURE = 2;
    private static final int AUTH_PUBLIC_KEY = 3;

    // префикс DigestInfo для SHA-1: adbd подписывает токен как готовый хэш
    private static final byte[] SHA1_PREFIX = {
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};

    private final Socket socket;
    private final DataInputStream in;
    private final OutputStream out;
    private int nextLocalId = 1;

    private LocalAdb(Socket socket) throws IOException {
        this.socket = socket;
        this.in = new DataInputStream(socket.getInputStream());
        this.out = socket.getOutputStream();
    }

    // слушает ли adb по сети, без авторизации
    static boolean reachable() {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(HOST, PORT), 700);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    // подключение; если ключ ещё не разрешён, ждём, пока на TV нажмут "Разрешить"
    static LocalAdb connect(Context ctx, int waitAuthMs) throws Exception {
        KeyPair keys = keys(ctx);
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(HOST, PORT), 2000);
        socket.setSoTimeout(waitAuthMs);
        LocalAdb adb = new LocalAdb(socket);
        try {
            adb.send(CNXN, VERSION, MAX_DATA, "host::\0".getBytes(StandardCharsets.UTF_8));
            Message m = adb.read();
            if (m.command == AUTH && m.arg0 == AUTH_TOKEN) {
                adb.send(AUTH, AUTH_SIGNATURE, 0, sign(keys.getPrivate(), m.data));
                m = adb.read();
                if (m.command == AUTH) {
                    // ключ новый: TV покажет запрос, ответ придёт после нажатия "Разрешить"
                    adb.send(AUTH, AUTH_PUBLIC_KEY, 0, publicKey((RSAPublicKey) keys.getPublic()));
                    m = adb.read();
                }
            }
            if (m.command != CNXN) {
                throw new IOException("adb не принял подключение");
            }
            socket.setSoTimeout(30000);
            return adb;
        } catch (Exception e) {
            adb.close();
            throw e;
        }
    }

    String shell(String command) throws IOException {
        int localId = nextLocalId++;
        send(OPEN, localId, 0, ("shell:" + command + "\0").getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        int remoteId = 0;
        while (true) {
            Message m = read();
            if (m.arg1 != localId) {
                continue;
            }
            if (m.command == OKAY) {
                remoteId = m.arg0;
            } else if (m.command == WRTE) {
                result.write(m.data);
                send(OKAY, localId, m.arg0, new byte[0]);
            } else if (m.command == CLSE) {
                if (remoteId != 0) {
                    send(CLSE, localId, remoteId, new byte[0]);
                }
                return result.toString("UTF-8").trim();
            }
        }
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
            // закрываем как можем
        }
    }

    // --- протокол ---

    private static final class Message {
        int command;
        int arg0;
        int arg1;
        byte[] data;
    }

    private void send(int command, int arg0, int arg1, byte[] data) throws IOException {
        int checksum = 0;
        for (byte b : data) {
            checksum += b & 0xff;
        }
        ByteBuffer header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(command).putInt(arg0).putInt(arg1).putInt(data.length).putInt(checksum).putInt(~command);
        out.write(header.array());
        out.write(data);
        out.flush();
    }

    private Message read() throws IOException {
        byte[] raw = new byte[24];
        in.readFully(raw);
        ByteBuffer header = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        Message m = new Message();
        m.command = header.getInt();
        m.arg0 = header.getInt();
        m.arg1 = header.getInt();
        m.data = new byte[header.getInt()];
        in.readFully(m.data);
        return m;
    }

    // --- ключи ---

    private static KeyPair keys(Context ctx) throws Exception {
        File priv = new File(ctx.getFilesDir(), "adbkey");
        File pub = new File(ctx.getFilesDir(), "adbkey.pub");
        KeyFactory factory = KeyFactory.getInstance("RSA");
        if (priv.exists() && pub.exists()) {
            return new KeyPair(
                    factory.generatePublic(new X509EncodedKeySpec(readAll(pub))),
                    factory.generatePrivate(new PKCS8EncodedKeySpec(readAll(priv))));
        }
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        writeAll(priv, pair.getPrivate().getEncoded());
        writeAll(pub, pair.getPublic().getEncoded());
        return pair;
    }

    private static byte[] sign(PrivateKey key, byte[] token) throws Exception {
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        cipher.update(SHA1_PREFIX);
        return cipher.doFinal(token);
    }

    // формат открытого ключа adb: длина в словах, n0inv, модуль и R^2 mod n в little-endian, экспонента
    private static byte[] publicKey(RSAPublicKey key) {
        int words = 2048 / 32;
        BigInteger n = key.getModulus();
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = n.mod(r32).modInverse(r32).negate().mod(r32);
        BigInteger rr = BigInteger.ONE.shiftLeft(2048).modPow(BigInteger.valueOf(2), n);

        ByteBuffer buf = ByteBuffer.allocate(4 + 4 + words * 4 + words * 4 + 4).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(words);
        buf.putInt(n0inv.intValue());
        putWords(buf, n, words);
        putWords(buf, rr, words);
        buf.putInt(key.getPublicExponent().intValue());

        String text = Base64.encodeToString(buf.array(), Base64.NO_WRAP) + " patchwall-actions@tv\0";
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static void putWords(ByteBuffer buf, BigInteger value, int words) {
        BigInteger mask = BigInteger.ONE.shiftLeft(32).subtract(BigInteger.ONE);
        for (int i = 0; i < words; i++) {
            buf.putInt(value.shiftRight(32 * i).and(mask).intValue());
        }
    }

    private static byte[] readAll(File f) throws IOException {
        byte[] data = new byte[(int) f.length()];
        try (DataInputStream s = new DataInputStream(new FileInputStream(f))) {
            s.readFully(data);
        }
        return data;
    }

    private static void writeAll(File f, byte[] data) throws IOException {
        try (FileOutputStream s = new FileOutputStream(f)) {
            s.write(data);
        }
    }
}
