package com.portalskylanders.app

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.MifareClassic
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.OutputStream
import java.net.Socket
import java.security.MessageDigest

@Suppress("SetTextI18n", "DEPRECATION")
class MainActivity : AppCompatActivity() {

    private var nfcAdapter: NfcAdapter? = null
    private lateinit var tvName: TextView
    private lateinit var tvElement: TextView
    private lateinit var tvLog: TextView
    private lateinit var viewGlow: View
    private lateinit var etIpAddress: EditText
    private lateinit var btnConnect: Button
    private lateinit var blackOverlay: View // Tela preta

    private var glowAnimator: ObjectAnimator? = null
    private val handler = Handler(Looper.getMainLooper())
    private var activeTag: Tag? = null

    // GERENCIAMENTO DE TELA E INATIVIDADE
    private val inactivityHandler = Handler(Looper.getMainLooper())
    private val inactivityRunnable = Runnable { ativarTelaPreta() }

    // GERENCIAMENTO DO SOCKET PERSISTENTE
    private var tcpSocket: Socket? = null
    private var outputStream: OutputStream? = null
    @Volatile private var isConnected = false
    @Volatile private var isReading = false

    private val presenceChecker = object : Runnable {
        override fun run() {
            try {
                if (activeTag != null && !isReading) {
                    Thread {
                        try {
                            val mifare = MifareClassic.get(activeTag)
                            if (mifare != null) {
                                mifare.connect()
                                mifare.close()
                            } else {
                                throw Exception("Tag removida")
                            }
                        } catch (_: Exception) {
                            activeTag = null
                            runOnUiThread {
                                if (!isFinishing && !isDestroyed) {
                                    resetarUI()
                                    enviarSinalRemocao()
                                    resetarTimerInatividade() // Acorda a tela se tirou o boneco
                                }
                            }
                        }
                    }.start()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Impede que a tela do dispositivo apague enquanto o app estiver aberto
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_main)

        tvName = findViewById(R.id.tvName)
        tvElement = findViewById(R.id.tvElement)
        tvLog = findViewById(R.id.tvLog)
        viewGlow = findViewById(R.id.viewGlow)
        etIpAddress = findViewById(R.id.etIpAddress)
        btnConnect = findViewById(R.id.btnConnect)
        blackOverlay = findViewById(R.id.blackOverlay)

        val sharedPref = getPreferences(Context.MODE_PRIVATE)
        etIpAddress.setText(sharedPref.getString("pc_ip", ""))

        btnConnect.setOnClickListener {
            if (!isConnected) {
                conectarAoPC()
            } else {
                desconectarDoPC()
            }
        }

        try {
            nfcAdapter = NfcAdapter.getDefaultAdapter(this)
            if (nfcAdapter == null) {
                tvName.text = "NFC não suportado"
                tvName.setTextColor(Color.RED)
            } else if (!nfcAdapter!!.isEnabled) {
                tvName.text = "Ative o NFC"
                tvName.setTextColor(Color.YELLOW)
            }
        } catch (e: Exception) {
            tvName.text = "Erro no NFC"
        }

        resetarTimerInatividade() // Inicia o contador de 15 segundos
        extrairTagEProcessar(intent)
    }

    // ==== LÓGICA DE TELA PRETA (ECONOMIA DE BATERIA) ====
    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        resetarTimerInatividade() // Qualquer toque na tela reinicia os 15 segundos
        return super.dispatchTouchEvent(ev)
    }

    private fun resetarTimerInatividade() {
        if (blackOverlay.visibility == View.VISIBLE) {
            desativarTelaPreta()
        }
        inactivityHandler.removeCallbacks(inactivityRunnable)
        inactivityHandler.postDelayed(inactivityRunnable, 15000) // 15.000 ms = 15 segundos
    }

    private fun ativarTelaPreta() {
        blackOverlay.visibility = View.VISIBLE
        // Baixa o brilho da tela ao mínimo para economizar bateria
        val params = window.attributes
        params.screenBrightness = 0.01f
        window.attributes = params
    }

    private fun desativarTelaPreta() {
        blackOverlay.visibility = View.GONE
        // Restaura o brilho normal do sistema
        val params = window.attributes
        params.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = params
    }
    // ====================================================

    private fun conectarAoPC() {
        val ip = etIpAddress.text.toString().trim()
        if (ip.isEmpty()) {
            tvLog.text = "⚠️ Digite o IP do PC!"
            return
        }

        getPreferences(Context.MODE_PRIVATE).edit().putString("pc_ip", ip).apply()
        tvLog.text = "Conectando a $ip..."

        Thread {
            try {
                tcpSocket = Socket(ip, 8888)
                outputStream = tcpSocket?.getOutputStream()
                isConnected = true

                runOnUiThread {
                    btnConnect.text = "Desconectar"
                    btnConnect.setBackgroundColor(Color.RED)
                    btnConnect.setTextColor(Color.WHITE)
                    tvLog.text = "✅ Conectado ao PC! Aproxime um Skylander."
                }
            } catch (e: Exception) {
                desconectarDoPC()
                runOnUiThread {
                    tvLog.text = "❌ Não foi possível conectar ao PC."
                }
            }
        }.start()
    }

    private fun desconectarDoPC() {
        isConnected = false
        Thread {
            try {
                outputStream?.close()
                tcpSocket?.close()
            } catch (_: Exception) {}
            outputStream = null
            tcpSocket = null
        }.start()

        runOnUiThread {
            btnConnect.text = "Conectar"
            btnConnect.setBackgroundColor(Color.parseColor("#00FF88"))
            btnConnect.setTextColor(Color.parseColor("#121212"))
            tvLog.text = "Desconectado do PC."
        }
    }

    private fun enviarDadosSkylander(dados: ByteArray) {
        if (!isConnected || outputStream == null) return

        Thread {
            try {
                outputStream?.write(dados)
                outputStream?.flush()

                runOnUiThread {
                    tvLog.text = tvLog.text.toString() + "\n🎮 Enviado pro Jogo!"
                }
            } catch (e: Exception) {
                runOnUiThread {
                    tvLog.text = "❌ Conexão perdida com o PC."
                }
                desconectarDoPC()
            }
        }.start()
    }

    private fun enviarSinalRemocao() {
        if (!isConnected || outputStream == null) return

        Thread {
            try {
                val emptyPacket = ByteArray(1024)
                outputStream?.write(emptyPacket)
                outputStream?.flush()
            } catch (_: Exception) {}
        }.start()
    }

    override fun onResume() {
        super.onResume()
        resetarTimerInatividade()
        try {
            nfcAdapter?.let { adapter ->
                if (adapter.isEnabled) {
                    val intent = Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                    } else {
                        PendingIntent.FLAG_UPDATE_CURRENT
                    }
                    val pendingIntent = PendingIntent.getActivity(this, 0, intent, flags)
                    val filter = IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED)
                    val techList = arrayOf(arrayOf(MifareClassic::class.java.name))
                    adapter.enableForegroundDispatch(this, pendingIntent, arrayOf(filter), techList)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        handler.postDelayed(presenceChecker, 500)
    }

    override fun onPause() {
        super.onPause()
        try {
            nfcAdapter?.disableForegroundDispatch(this)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        handler.removeCallbacks(presenceChecker)
    }

    override fun onDestroy() {
        super.onDestroy()
        desconectarDoPC()
        inactivityHandler.removeCallbacks(inactivityRunnable)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        resetarTimerInatividade() // Acorda a tela ao encostar NFC
        extrairTagEProcessar(intent)
    }

    private fun extrairTagEProcessar(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        if (NfcAdapter.ACTION_TECH_DISCOVERED == action || NfcAdapter.ACTION_TAG_DISCOVERED == action) {
            val tag: Tag? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
            } else {
                intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
            }

            if (tag != null) {
                activeTag = tag
                iniciarMonitoramento(tag)
            }
        }
    }

    private fun calcularChaveSetor(uid: ByteArray, setor: Int): ByteArray {
        return try {
            val md = MessageDigest.getInstance("MD5")
            val magic = byteArrayOf(
                0x20, 0x43, 0x6f, 0x70, 0x79, 0x72, 0x69, 0x67, 0x68, 0x74, 0x20, 0x28, 0x43, 0x29, 0x20,
                0x32, 0x30, 0x31, 0x30, 0x20, 0x41, 0x63, 0x74, 0x69, 0x76, 0x69, 0x73, 0x69, 0x6f, 0x6e,
                0x2e, 0x20, 0x41, 0x6c, 0x6c, 0x20, 0x52, 0x69, 0x67, 0x68, 0x74, 0x73, 0x20, 0x52, 0x65,
                0x73, 0x65, 0x72, 0x76, 0x65, 0x64, 0x2e
            )
            val validUid = if (uid.size >= 4) uid.copyOfRange(0, 4) else uid
            val buffer = ByteArray(validUid.size + 1 + magic.size)
            System.arraycopy(validUid, 0, buffer, 0, validUid.size)
            buffer[validUid.size] = setor.toByte()
            System.arraycopy(magic, 0, buffer, validUid.size + 1, magic.size)
            val hash = md.digest(buffer)
            hash.copyOfRange(0, 6)
        } catch (e: Exception) {
            MifareClassic.KEY_DEFAULT
        }
    }

    private fun iniciarMonitoramento(tag: Tag) {
        isReading = true
        val mifare = MifareClassic.get(tag)
        val uid = try { tag.id.joinToString(":") { "%02x".format(it) } } catch (e: Exception) { "Desconhecido" }

        if (mifare == null) {
            isReading = false
            runOnUiThread {
                tvName.text = "Tag Inválida"
                tvName.setTextColor(Color.RED)
                tvElement.text = "⚠️"
                tvLog.text = "Não é Mifare Classic\nUID: $uid"
                apagarPortal()
            }
            return
        }

        Thread {
            try {
                mifare.connect()
                mifare.timeout = 2000

                var toyId = -1
                val dumpData = ByteArray(1024)
                var blocosLidos = 0

                for (sector in 0 until mifare.sectorCount) {
                    val key = if (sector == 0) {
                        byteArrayOf(0x4B.toByte(), 0x0B.toByte(), 0x20.toByte(), 0x10.toByte(), 0x7C.toByte(), 0xCB.toByte())
                    } else {
                        calcularChaveSetor(tag.id, sector)
                    }

                    try {
                        if (mifare.authenticateSectorWithKeyA(sector, key)) {
                            val startBlock = mifare.sectorToBlock(sector)
                            val blockCount = mifare.getBlockCountInSector(sector)

                            for (i in 0 until blockCount) {
                                val block = startBlock + i
                                val data = mifare.readBlock(block)
                                if (data != null && data.size == 16) {
                                    System.arraycopy(data, 0, dumpData, block * 16, 16)
                                    blocosLidos++

                                    if (block == 1) {
                                        val idLe = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
                                        val idBe = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
                                        if (ehIdValido(idLe)) toyId = idLe
                                        else if (ehIdValido(idBe)) toyId = idBe
                                    }
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }

                val (nome, elemento) = obterInfoSkylander(toyId)

                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        if (toyId >= 0 && !nome.contains("Desconhecido")) {
                            tvName.text = nome
                            tvName.setTextColor(Color.parseColor("#00FF88"))
                            tvElement.text = elemento
                            animarPortal()
                        } else {
                            tvName.text = "Skylander Desconhecido"
                            tvName.setTextColor(Color.YELLOW)
                            tvElement.text = "❓"
                            animarPortal()
                        }
                        tvLog.text = "UID: $uid | ID: $toyId | Bytes: ${blocosLidos * 16}/1024"
                    }
                }

                try { mifare.close() } catch (_: Exception) {}

                if (blocosLidos > 0) {
                    enviarDadosSkylander(dumpData)
                }

            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        resetarUI()
                    }
                }
            } finally {
                isReading = false
            }
        }.start()
    }

    private fun animarPortal() {
        if (glowAnimator?.isRunning == true) return

        glowAnimator = ObjectAnimator.ofPropertyValuesHolder(
            viewGlow,
            PropertyValuesHolder.ofFloat(View.ALPHA, 0.4f, 1.0f),
            PropertyValuesHolder.ofFloat(View.SCALE_X, 0.95f, 1.15f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.95f, 1.15f)
        ).apply {
            duration = 1200
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            start()
        }
    }

    private fun apagarPortal() {
        glowAnimator?.cancel()
        viewGlow.animate()
            .alpha(0.2f)
            .scaleX(1.0f)
            .scaleY(1.0f)
            .setDuration(500)
            .start()
    }

    private fun resetarUI() {
        tvName.text = "Portal Vazio"
        tvName.setTextColor(Color.parseColor("#555566"))
        tvElement.text = "✖️"
        tvLog.text = if (isConnected) "Aproxime um Skylander..." else "Conecte ao PC para começar..."
        apagarPortal()
    }

    private fun ehIdValido(id: Int): Boolean {
        val (nome, _) = obterInfoSkylander(id)
        return (id in 0..300 || id in 100..115) && !nome.contains("Desconhecido")
    }

    private fun obterInfoSkylander(toyId: Int): Pair<String, String> {
        return when (toyId) {
            0 -> Pair("Whirlwind", "🌪️")
            1 -> Pair("Sonic Boom", "🌪️")
            2 -> Pair("Warnado", "🌪️")
            3 -> Pair("Lightning Rod", "🌪️")
            4 -> Pair("Bash", "🪨")
            5 -> Pair("Terrafin", "🪨")
            6 -> Pair("Dino-Rang", "🪨")
            7 -> Pair("Prism Break", "🪨")
            8 -> Pair("Sunburn", "🔥")
            9 -> Pair("Eruptor", "🔥")
            10 -> Pair("Ignitor", "🔥")
            11 -> Pair("Flameslinger", "🔥")
            12 -> Pair("Zap", "🌊")
            13 -> Pair("Wham-Shell", "🌊")
            14 -> Pair("Gill Grunt", "🌊")
            15 -> Pair("Slam Bam", "🌊")
            16 -> Pair("Spyro", "🔮")
            17 -> Pair("Voodood", "🔮")
            18 -> Pair("Double Trouble", "🔮")
            19 -> Pair("Trigger Happy", "⚙️")
            20 -> Pair("Drobot", "⚙️")
            21 -> Pair("Drill Sergeant", "⚙️")
            22 -> Pair("Boomer", "⚙️")
            23 -> Pair("Wrecking Ball", "🔮")
            24 -> Pair("Camo", "🍃")
            25 -> Pair("Zook", "🍃")
            26 -> Pair("Stealth Elf", "🍃")
            27 -> Pair("Stump Smash", "🍃")
            28 -> Pair("Dark Spyro", "🔮")
            29 -> Pair("Hex", "💀")
            30 -> Pair("Chop Chop", "💀")
            31 -> Pair("Ghost Roaster", "💀")
            32 -> Pair("Cynder", "💀")

            100 -> Pair("Jet-Vac", "🌪️")
            101 -> Pair("Swarm", "🌪️")
            102 -> Pair("Crusher", "🪨")
            103 -> Pair("Flashwing", "🪨")
            104 -> Pair("Hot Head", "🔥")
            105 -> Pair("Hot Dog", "🔥")
            106 -> Pair("Chill", "🌊")
            107 -> Pair("Thumpback", "🌊")
            108 -> Pair("Pop Fizz", "🔮")
            109 -> Pair("Ninjini", "🔮")
            110 -> Pair("Bouncer", "⚙️")
            111 -> Pair("Sprocket", "⚙️")
            112 -> Pair("Tree Rex", "🍃")
            113 -> Pair("Shroomboom", "🍃")
            114 -> Pair("Eye-Brawl", "💀")
            115 -> Pair("Fright Rider", "💀")

            in 117..145 -> Pair("SWAP Force", "✨")
            in 150..200 -> Pair("Trap Team", "🤼")
            in 201..250 -> Pair("SuperChargers", "🏎️")
            in 251..300 -> Pair("Imaginators", "🥋")
            else -> Pair("Detectado", "❓")
        }
    }
}