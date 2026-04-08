package io.github.chayanforyou.drowsinessdetection.utils

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager // Novo import
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

class BluetoothDeviceSender(private val context: Context) {

    private val bluetoothManager: BluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private var bluetoothSocket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null

    // UUID padrão para SPP (Serial Port Profile), o mesmo do seu script
    private val sppUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")

    companion object {
        private const val TAG = "BluetoothDeviceSender"
        // MUDE ESTA LINHA para o nome Bluetooth da sua Raspberry Pi
        const val DEVICE_NAME = "raspberrypi" // Exemplo, verifique o nome real da sua Pi
    }

    @SuppressLint("MissingPermission") // As permissões serão verificadas antes de chamar
    suspend fun connectToDevice(deviceName: String = DEVICE_NAME): Boolean {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            Log.e(TAG, "Bluetooth não está habilitado ou não é suportado.")
            return false
        }

        // Verificar permissões (exemplo básico, idealmente verificar antes de chamar connectToDevice)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "Permissão BLUETOOTH_CONNECT não concedida.")
                return false
            }
        } else {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "Permissão BLUETOOTH não concedida.")
                return false
            }
        }


        val pairedDevices: Set<BluetoothDevice>? = bluetoothAdapter.bondedDevices
        val targetDevice: BluetoothDevice? = pairedDevices?.find { it.name == deviceName }

        if (targetDevice == null) {
            Log.e(TAG, "Dispositivo '$deviceName' não encontrado nos dispositivos pareados.")
            return false
        }

        return withContext(Dispatchers.IO) { // Operações de rede/Bluetooth em background thread
            try {
                bluetoothSocket = targetDevice.createRfcommSocketToServiceRecord(sppUuid)
                bluetoothSocket?.connect() // Bloqueante
                outputStream = bluetoothSocket?.outputStream
                Log.i(TAG, "Conectado a $deviceName")
                true
            } catch (e: IOException) {
                Log.e(TAG, "Erro ao conectar ou obter streams: ${e.message}")
                closeConnection()
                false
            } catch (e: SecurityException) {
                Log.e(TAG, "Erro de segurança ao conectar: ${e.message}")
                closeConnection()
                false
            }
        }
    }

    suspend fun sendData(data: String): Boolean {
        if (outputStream == null) {
            Log.e(TAG, "Não conectado. Chame connectToDevice() primeiro.")
            return false
        }
        return withContext(Dispatchers.IO) {
            try {
                outputStream?.write(data.toByteArray())
                outputStream?.flush()
                Log.i(TAG, "Dados enviados: $data")
                true
            } catch (e: IOException) {
                Log.e(TAG, "Erro ao enviar dados: ${e.message}")
                false
            }
        }
    }

    fun closeConnection() {
        try {
            outputStream?.close()
            bluetoothSocket?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Erro ao fechar conexão: ${e.message}")
        } finally {
            outputStream = null
            bluetoothSocket = null
            Log.i(TAG, "Conexão Bluetooth fechada.")
        }
    }
}