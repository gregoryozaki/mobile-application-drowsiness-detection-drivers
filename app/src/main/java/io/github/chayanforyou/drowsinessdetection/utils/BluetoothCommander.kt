package io.github.chayanforyou.drowsinessdetection.utils // Ou o seu pacote apropriado

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.util.UUID

class BluetoothCommander(
    private val context: Context,
    private val coroutineScope: CoroutineScope
) {
    companion object {
        private const val TAG = "BluetoothCommander"
        // !!! SUBSTITUA PELO ENDEREÇO MAC REAL DA SUA RASPBERRY PI !!!
        private const val HC05_MAC_ADDRESS = "00:23:10:01:38:63" // EXEMPLO DE MAC ADDRESS
        // UUID do serviço SPP (Serial Port Profile) - DEVE SER O MESMO DEFINIDO NO SCRIPT DA PI
        private val HC05_SERVICE_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        const val STATE_DISCONNECTED = 0
        const val STATE_CONNECTING = 1
        const val STATE_CONNECTED = 2
    }

    private val bluetoothManager: BluetoothManager? = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
    private var bluetoothSocket: BluetoothSocket? = null
    private var printWriter: PrintWriter? = null
    private var bufferedReader: BufferedReader? = null

    private var connectionJob: Job? = null
    private var listenerJob: Job? = null

    var connectionState = STATE_DISCONNECTED
        private set(value) {
            if (field != value) {
                field = value
                // Notifica o novo estado na thread principal, se houver um callback
                coroutineScope.launch(Dispatchers.Main) {
                    val message = when (value) {
                        STATE_CONNECTING -> "Conectando..."
                        STATE_CONNECTED -> "Conectado"
                        STATE_DISCONNECTED -> "Desconectado"
                        else -> "Estado desconhecido"
                    }
                    onConnectionStateChanged?.invoke(value, message)
                    Log.d(TAG, "Estado da conexão alterado para: $message (code: $value)")
                }
            }
        }

    // Callbacks para notificar a UI ou lógica de negócios
    var onConnectionStateChanged: ((state: Int, message: String) -> Unit)? = null
    var onMessageReceived: ((message: String) -> Unit)? = null

    fun hasBluetoothPermissions(): Boolean {
        val connectPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Manifest.permission.BLUETOOTH_CONNECT
        } else {
            Manifest.permission.BLUETOOTH
        }
        // BLUETOOTH_SCAN (ou ACCESS_FINE_LOCATION para versões mais antigas) é para descoberta,
        // mas a conexão direta a um MAC pareado pode precisar apenas de BLUETOOTH_CONNECT / BLUETOOTH.
        // No entanto, ter as permissões de scan/location é mais seguro para cobrir todos os cenários.
        return ActivityCompat.checkSelfPermission(context, connectPermission) == PackageManager.PERMISSION_GRANTED
        // Adicione outras verificações de permissão aqui se sua lógica de conexão/descoberta mudar
    }

    fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled == true

    @SuppressLint("MissingPermission") // As permissões devem ser verificadas antes de chamar connect()
    fun connect() {
        if (!hasBluetoothPermissions()) {
            val msg = "Permissões Bluetooth não concedidas."
            Log.e(TAG, msg)
            // Atualiza o estado e notifica via callback
            connectionState = STATE_DISCONNECTED
            coroutineScope.launch(Dispatchers.Main) { onConnectionStateChanged?.invoke(STATE_DISCONNECTED, msg) }
            return
        }
        if (!isBluetoothEnabled()) {
            val msg = "Bluetooth não está habilitado."
            Log.e(TAG, msg)
            connectionState = STATE_DISCONNECTED
            coroutineScope.launch(Dispatchers.Main) { onConnectionStateChanged?.invoke(STATE_DISCONNECTED, msg) }
            // A Activity deve lidar com a solicitação para habilitar o Bluetooth
            return
        }
        if (connectionState == STATE_CONNECTING || connectionState == STATE_CONNECTED) {
            Log.d(TAG, "Já conectado ou conectando.")
            return
        }

        connectionJob?.cancel() // Cancela qualquer tentativa de conexão anterior
        connectionJob = coroutineScope.launch(Dispatchers.IO) {
            try {
                connectionState = STATE_CONNECTING
                val device: BluetoothDevice? = bluetoothAdapter?.getRemoteDevice(HC05_MAC_ADDRESS)

                if (device == null) {
                    Log.e(TAG, "Dispositivo $HC05_MAC_ADDRESS não encontrado.")
                    connectionState = STATE_DISCONNECTED
                    return@launch
                }

                Log.d(TAG, "Tentando criar socket para ${device.name ?: device.address} com UUID $HC05_SERVICE_UUID")
                bluetoothSocket = device.createRfcommSocketToServiceRecord(HC05_SERVICE_UUID)

                bluetoothAdapter?.cancelDiscovery() // Importante: cancele a descoberta antes de conectar

                Log.d(TAG, "Tentando conectar socket...")
                bluetoothSocket?.connect() // Chamada bloqueante

                printWriter = PrintWriter(OutputStreamWriter(bluetoothSocket?.outputStream, Charsets.UTF_8), true)
                bufferedReader = BufferedReader(InputStreamReader(bluetoothSocket?.inputStream, Charsets.UTF_8))

                connectionState = STATE_CONNECTED
                Log.i(TAG, "Conectado com sucesso a ${device.name ?: HC05_MAC_ADDRESS}")

                startListeningForMessages() // Inicia a escuta por mensagens da Pi

            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException ao conectar: ${e.message}")
                connectionState = STATE_DISCONNECTED // Atualiza o estado antes de chamar disconnect
                disconnect("Erro de permissão ao conectar")
            } catch (e: IOException) {
                Log.e(TAG, "IOException ao conectar: ${e.message}")
                connectionState = STATE_DISCONNECTED
                disconnect("Falha na conexão: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Exceção desconhecida ao conectar: ${e.message}", e)
                connectionState = STATE_DISCONNECTED
                disconnect("Erro desconhecido: ${e.message}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startListeningForMessages() {
        listenerJob?.cancel() // Cancela listener anterior se houver
        listenerJob = coroutineScope.launch(Dispatchers.IO) {
            Log.d(TAG, "Iniciando escuta por mensagens da Pi...")
            while (isActive && bluetoothSocket?.isConnected == true) {
                try {
                    val message = bufferedReader?.readLine()
                    if (message != null) {
                        Log.d(TAG, "Mensagem recebida da Pi: $message")
                        withContext(Dispatchers.Main) { // Notifica na thread principal
                            onMessageReceived?.invoke(message)
                        }
                    } else {
                        Log.d(TAG, "Stream de entrada fechado ou nulo (readLine retornou null). Provável desconexão.")
                        if (connectionState == STATE_CONNECTED) { // Só se desconecta se estava conectado
                            connectionState = STATE_DISCONNECTED
                            disconnect("Desconectado (stream fechado)")
                        }
                        break // Sai do loop de escuta
                    }
                } catch (e: IOException) {
                    if (isActive) { // Só loga erro se a coroutine ainda estiver ativa e não cancelada
                        Log.e(TAG, "IOException ao ler da Pi: ${e.message}")
                        if (connectionState == STATE_CONNECTED) {
                            connectionState = STATE_DISCONNECTED
                            disconnect("Desconectado (erro de leitura)")
                        }
                    }
                    break // Sai do loop de escuta
                } catch (e: Exception) {
                    if (isActive) {
                        Log.e(TAG, "Exceção desconhecida ao ler da Pi: ${e.message}")
                        if (connectionState == STATE_CONNECTED) {
                            connectionState = STATE_DISCONNECTED
                            disconnect("Desconectado (erro desconhecido na leitura)")
                        }
                    }
                    break
                }
            }
            Log.d(TAG, "Rotina de escuta de mensagens finalizada.")
        }
    }

    fun sendCommand(command: String): Boolean {
        if (connectionState != STATE_CONNECTED || printWriter == null) {
            Log.w(TAG, "Não conectado, impossível enviar comando '$command'.")
            // Opcional: Tentar reconectar automaticamente
            // if (connectionState == STATE_DISCONNECTED) {
            //     Log.d(TAG, "Tentando reconectar para enviar comando...")
            //     connect()
            // }
            return false
        }
        // Lançar em uma coroutine separada para não bloquear a chamada de sendCommand
        // se a thread chamadora for a principal. O printWriter em si é bloqueante.
        coroutineScope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "Enviando comando: $command")
                printWriter?.println(command) // println adiciona \n, o que é bom para readLine() no servidor
                // O flush é feito pelo `true` no construtor do PrintWriter ou chame printWriter?.flush() se não.
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao enviar comando '$command': ${e.message}")
                // Considerar tratar o erro de forma mais robusta, talvez desconectando
                // connectionState = STATE_DISCONNECTED // Atualiza o estado antes de chamar disconnect
                // disconnect("Erro ao enviar dados")
            }
        }
        return true // Retorna true se a tentativa de envio foi iniciada (não garante entrega)
    }

    fun disconnect(reason: String = "Desconectado pelo usuário") {
        Log.d(TAG, "Tentando desconectar... Razão: $reason")
        connectionJob?.cancel()
        listenerJob?.cancel()
        try {
            printWriter?.close()
            bufferedReader?.close()
            bluetoothSocket?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Erro ao fechar streams/socket: ${e.message}")
        } finally {
            bluetoothSocket = null
            printWriter = null
            bufferedReader = null
            // Atualiza o estado e notifica via callback, apenas se não já estiver desconectado
            if(connectionState != STATE_DISCONNECTED) {
                connectionState = STATE_DISCONNECTED
                // A notificação já é feita pelo setter de connectionState
            } else {
                // Se já estava desconectado, ainda pode chamar o callback para garantir que a UI saiba da intenção
                coroutineScope.launch(Dispatchers.Main) {
                    onConnectionStateChanged?.invoke(STATE_DISCONNECTED, reason)
                }
            }
            Log.i(TAG, "Conexão Bluetooth finalizada. Razão: $reason")
        }
    }
}