package com.pyfold.ubuntucontrol

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import com.jcraft.jsch.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Секреты (приватный SSH-ключ) хранятся зашифрованными ключом из Android Keystore. */
object SecureStore {
    private const val ALIAS = "ubuntu_control_aes"
    private lateinit var prefs: SharedPreferences

    fun init(ctx: Context) {
        prefs = ctx.applicationContext.getSharedPreferences("uc", Context.MODE_PRIVATE)
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build()
        )
        return gen.generateKey()
    }

    fun putSecret(name: String, value: String) {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val ct = c.doFinal(value.toByteArray())
        val enc = Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
        prefs.edit().putString("s_$name", enc).apply()
    }

    fun getSecret(name: String): String? = try {
        val (iv, ct) = (prefs.getString("s_$name", null) ?: return null).split(":")
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        String(c.doFinal(Base64.decode(ct, Base64.NO_WRAP)))
    } catch (e: Exception) {
        null
    }

    fun put(name: String, value: String) = prefs.edit().putString(name, value).apply()
    fun get(name: String, def: String = ""): String = prefs.getString(name, def) ?: def
    fun clearAll() = prefs.edit().clear().apply()
}

sealed interface Conn {
    data object Idle : Conn
    data object Connecting : Conn
    data object Online : Conn
    data class Failed(val msg: String) : Conn
}

data class ExecResult(val code: Int, val out: String)

/** Одно SSH-подключение с автоматическим переподключением по сохранённому ключу. */
object SshManager {
    const val DEFAULT_HOST = "100.98.165.54"

    private val _state = MutableStateFlow<Conn>(Conn.Idle)
    val state: StateFlow<Conn> = _state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    @Volatile private var session: Session? = null
    private var loopStarted = false

    val configured: Boolean get() = SecureStore.getSecret("priv") != null
    val host: String get() = SecureStore.get("host", DEFAULT_HOST)
    val user: String get() = SecureStore.get("user", "pyfold")
    private val port: Int get() = SecureStore.get("port", "22").toIntOrNull() ?: 22

    /** Цикл: пока ключ настроен — держим соединение и поднимаем его после обрывов. */
    fun startAutoConnect() {
        if (loopStarted) return
        loopStarted = true
        scope.launch {
            while (true) {
                val alive = session?.isConnected == true
                if (!alive && configured) connectWithKey()
                else if (alive && _state.value !is Conn.Online) _state.value = Conn.Online
                delay(if (alive) 5000 else 4000)
            }
        }
    }

    suspend fun connectWithKey() = lock.withLock {
        if (session?.isConnected == true) return@withLock
        _state.value = Conn.Connecting
        try {
            val priv = SecureStore.getSecret("priv") ?: error("Ключ не настроен")
            val pub = SecureStore.get("pub")
            val jsch = JSch()
            jsch.addIdentity("uc", priv.toByteArray(), pub.toByteArray(), null)
            val s = jsch.getSession(user, host, port)
            s.setConfig("StrictHostKeyChecking", "no")
            s.setConfig("PreferredAuthentications", "publickey")
            s.setServerAliveInterval(15000)
            s.connect(10000)
            checkHostKey(s)
            session = s
            _state.value = Conn.Online
        } catch (e: Exception) {
            session = null
            _state.value = Conn.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /** Доверие при первом подключении (TOFU): отпечаток ключа сервера запоминается, при смене соединение рвётся. */
    private fun checkHostKey(s: Session) {
        val fp = s.hostKey.getFingerPrint(JSch())
        val saved = SecureStore.get("hostfp")
        if (saved.isEmpty()) SecureStore.put("hostfp", fp)
        else if (saved != fp) {
            s.disconnect()
            error("Ключ сервера изменился! Возможна подмена. Сбросьте настройки, если вы переустанавливали сервер.")
        }
    }

    fun disconnect() {
        try { session?.disconnect() } catch (_: Exception) {}
        session = null
        _state.value = Conn.Idle
    }

    fun reset() {
        disconnect()
        SecureStore.clearAll()
    }

    /** Первичная настройка: вход по паролю, создание ключа, установка его на сервер. Пароль нигде не сохраняется. */
    suspend fun setup(
        host: String, port: Int, user: String, password: String, passwordlessSudo: Boolean,
        progress: (String) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            disconnect()
            progress("Генерирую ключ…")
            val jsch = JSch()
            val kp = KeyPair.genKeyPair(jsch, KeyPair.ECDSA, 256)
            val privOut = ByteArrayOutputStream().also { kp.writePrivateKey(it) }
            val pubOut = ByteArrayOutputStream().also { kp.writePublicKey(it, "ubuntu-control@android") }
            val priv = privOut.toString("UTF-8")
            val pub = pubOut.toString("UTF-8").trim()
            kp.dispose()

            progress("Подключаюсь по паролю…")
            val s = JSch().getSession(user, host, port)
            s.setPassword(password)
            s.setConfig("StrictHostKeyChecking", "no")
            s.setConfig("PreferredAuthentications", "password,keyboard-interactive")
            s.connect(15000)
            try {
                SecureStore.put("hostfp", s.hostKey.getFingerPrint(JSch()))

                progress("Устанавливаю ключ на сервер…")
                val q = pub.replace("'", "'\\''")
                val r = execOn(s, "mkdir -p ~/.ssh && chmod 700 ~/.ssh && touch ~/.ssh/authorized_keys && " +
                        "(grep -qxF '$q' ~/.ssh/authorized_keys || echo '$q' >> ~/.ssh/authorized_keys) && " +
                        "chmod 600 ~/.ssh/authorized_keys && echo OK")
                if (!r.out.contains("OK")) error("Не удалось записать ключ: ${r.out.take(200)}")

                if (passwordlessSudo) {
                    progress("Настраиваю sudo без пароля для управления…")
                    val rule = "$user ALL=(root) NOPASSWD: /usr/bin/systemctl, /usr/bin/journalctl, /usr/bin/apt-get, " +
                            "/usr/bin/apt, /usr/bin/tailscale, /usr/sbin/ufw, /usr/bin/docker, /usr/sbin/reboot, /usr/sbin/poweroff"
                    val script = "t=\$(mktemp) && echo '$rule' > \$t && visudo -cf \$t >/dev/null && " +
                            "install -m 440 -o root -g root \$t /etc/sudoers.d/ubuntu-control && rm -f \$t && echo SUDO_OK"
                    val sr = execOn(s, "sudo -S -p '' sh -c ${shq(script)}", stdin = password + "\n")
                    if (!sr.out.contains("SUDO_OK")) error("sudo не настроен: ${sr.out.take(200)}")
                }
            } finally {
                s.disconnect()
            }
            SecureStore.put("host", host)
            SecureStore.put("port", port.toString())
            SecureStore.put("user", user)
            SecureStore.put("pub", pub)
            SecureStore.putSecret("priv", priv)
            progress("Готово")
        }
    }

    fun shq(s: String) = "'" + s.replace("'", "'\\''") + "'"

    /** Выполнить команду на уже установленном соединении. */
    suspend fun exec(cmd: String, timeoutMs: Long = 60_000): ExecResult = withContext(Dispatchers.IO) {
        val s = session?.takeIf { it.isConnected } ?: return@withContext ExecResult(-1, "Нет подключения к серверу")
        try {
            execOn(s, cmd, timeoutMs = timeoutMs)
        } catch (e: Exception) {
            ExecResult(-1, "Ошибка: ${e.message}")
        }
    }

    private fun execOn(s: Session, cmd: String, stdin: String? = null, timeoutMs: Long = 60_000): ExecResult {
        val ch = s.openChannel("exec") as ChannelExec
        ch.setCommand(cmd)
        val buf = ByteArrayOutputStream()
        ch.setInputStream(stdin?.byteInputStream() ?: "".byteInputStream())
        ch.setOutputStream(buf)
        ch.setErrStream(buf)
        ch.connect(10000)
        val end = System.currentTimeMillis() + timeoutMs
        while (!ch.isClosed && System.currentTimeMillis() < end) Thread.sleep(80)
        val timedOut = !ch.isClosed
        val code = if (timedOut) -2 else ch.exitStatus
        ch.disconnect()
        val text = buf.toString("UTF-8") + if (timedOut) "\n[время ожидания вышло]" else ""
        return ExecResult(code, text.trimEnd())
    }

    fun openShell(): TermSession? {
        val s = session?.takeIf { it.isConnected } ?: return null
        return TermSession(s)
    }
}

/** Интерактивная оболочка с PTY; вывод очищается от ANSI-последовательностей. */
class TermSession(session: Session) {
    private val ch: ChannelShell = (session.openChannel("shell") as ChannelShell).apply {
        setPty(true)
        setPtyType("xterm", 100, 40, 0, 0)
    }
    val text = MutableStateFlow("")
    val closed = MutableStateFlow(false)
    private lateinit var out: java.io.OutputStream
    private val ansi = Regex("\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)|\u001B\\[[0-9;?<>=]*[ -/]*[@-~]|\u001B[()][A-Z0-9]|\u001B[=>78cDEHMNOZ]|\u001B_[^\u001B]*\u001B\\\\")

    init {
        val input = ch.inputStream
        ch.connect(10000)
        out = ch.outputStream
        Thread {
            val r = InputStreamReader(input, Charsets.UTF_8)
            val buf = CharArray(4096)
            try {
                while (true) {
                    val n = r.read(buf)
                    if (n < 0) break
                    append(String(buf, 0, n))
                }
            } catch (_: Exception) {
            }
            closed.value = true
        }.apply { isDaemon = true }.start()
    }

    private var carry = ""

    private fun append(chunk: String) {
        var raw = carry + chunk
        carry = ""
        val esc = raw.lastIndexOf('\u001B')
        // обрезанная посреди чтения управляющая последовательность — дожидаемся продолжения
        if (esc >= 0 && raw.length - esc < 600 && !ansi.matchesAt(raw, esc)) { carry = raw.substring(esc); raw = raw.substring(0, esc) }
        val clean = ansi.replace(raw, "")
        val sb = StringBuilder(text.value)
        for (c in clean) when (c) {
            '\r' -> {}
            '\b', '\u007f' -> if (sb.isNotEmpty() && sb.last() != '\n') sb.setLength(sb.length - 1)
            '\u0007' -> {}
            else -> sb.append(c)
        }
        if (sb.length > 80_000) sb.delete(0, sb.length - 60_000)
        text.value = sb.toString()
    }

    fun send(s: String) {
        Thread {
            try { out.write(s.toByteArray()); out.flush() } catch (_: Exception) { closed.value = true }
        }.start()
    }

    fun clear() { text.value = "" }
    fun close() { try { ch.disconnect() } catch (_: Exception) {} }
}
