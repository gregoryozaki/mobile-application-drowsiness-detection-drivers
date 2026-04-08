package io.github.chayanforyou.drowsinessdetection.processors

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import io.github.chayanforyou.drowsinessdetection.FaceGraphic
import io.github.chayanforyou.drowsinessdetection.overlay.GraphicOverlay
import io.github.chayanforyou.drowsinessdetection.utils.BluetoothCommander // Usando BluetoothCommander
import io.github.chayanforyou.drowsinessdetection.utils.SoundPoolManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

class FaceDetectorProcessor(
    context: Context, // Context pode ser usado por VisionProcessorBase ou SoundPoolManager
    detectorOptions: FaceDetectorOptions?,
    private val bluetoothCommander: BluetoothCommander // Recebe a instância via construtor
) : VisionProcessorBase<List<Face>>(context) {

    private val detector: FaceDetector
    private val soundManager: SoundPoolManager = SoundPoolManager.getInstance(context)
    private var eyesClosedFrameCount = 0

    private var piSignalStateIsStop = false

    private val processorScope = CoroutineScope(Dispatchers.Default)

    companion object {
        private const val TAG = "FaceDetectorProcessor"
        private const val EYES_CLOSED_THRESHOLD = 0.50f
        private const val ALARM_COUNT_THRESHOLD = 5 // Mantido baixo para facilitar testes
    }

    init {
        val options = detectorOptions
            ?: FaceDetectorOptions.Builder()
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .enableTracking()
                .build()

        detector = FaceDetection.getClient(options)
        Log.v(MANUAL_TESTING_LOG, "Face detector options: $options. BluetoothCOmmander injetado.")

        bluetoothCommander.onMessageReceived = { message ->
            if (message.startsWith("CMD_PARAR")) piSignalStateIsStop = true
            else if (message.startsWith("CMD_LIBERAR")) piSignalStateIsStop = false
        }
        bluetoothCommander.onConnectionStateChanged = { state, _ ->
            if (state != BluetoothCommander.STATE_CONNECTED) piSignalStateIsStop = false
        }
    }

    override fun stop() {
        super.stop()
        detector.close()
        soundManager.stop()
        // Enviar "liberar" se estava em "parar" e a conexão ainda existe
        if (piSignalStateIsStop && bluetoothCommander.connectionState == BluetoothCommander.STATE_CONNECTED) {
            Log.d(TAG, "Processador parando, enviando 'liberar' para a Pi.")
            bluetoothCommander.sendCommand("N") // Adicionar \n se o servidor Pi espera
            piSignalStateIsStop = false
        }
        // A desconexão geral do Bluetooth é gerenciada pela MainActivity em onDestroy
        Log.d(TAG, "FaceDetectorProcessor stopped.")
    }

    override fun detectInImage(image: InputImage): Task<List<Face>> {
        return detector.process(image)
    }

    override fun onSuccess(faces: List<Face>, graphicOverlay: GraphicOverlay) {
        if (faces.isEmpty()) {
            // Nenhum rosto detectado
            if (piSignalStateIsStop && bluetoothCommander.connectionState == BluetoothCommander.STATE_CONNECTED) {
                Log.d(TAG, "Nenhum rosto detectado, enviando 'liberar' para a Pi.")
                bluetoothCommander.sendCommand("N")
                piSignalStateIsStop = false
            }
            // Limpar informações antigas se necessário
            // graphicOverlay.clear() // A classe base já limpa
            // graphicOverlay.add(InferenceInfoGraphic(...)) // A classe base já adiciona
            return
        }

        var isAnyFaceDrowsyThisFrame = false

        for (face in faces) {
            graphicOverlay.add(FaceGraphic(graphicOverlay, face)) // Desenha o rosto

            val leftEyeOpen = face.leftEyeOpenProbability
            val rightEyeOpen = face.rightEyeOpenProbability

            if (leftEyeOpen != null && rightEyeOpen != null) {
                if (leftEyeOpen < EYES_CLOSED_THRESHOLD && rightEyeOpen < EYES_CLOSED_THRESHOLD) {
                    // Olhos fechados
                    isAnyFaceDrowsyThisFrame = true // Pelo menos um rosto está com olhos fechados neste frame
                    eyesClosedFrameCount++

                    if (eyesClosedFrameCount >= framesPerSecond / 2) { // Usa framesPerSecond da classe base
                        eyesClosedCount++ // Usa eyesClosedCount da classe base
                        eyesClosedFrameCount = 0 // Reseta para a próxima contagem de frames de olhos fechados

                        if (eyesClosedCount >= ALARM_COUNT_THRESHOLD) {
                            alarmCount++ // Usa alarmCount da classe base
                            eyesClosedCount = 0 // Reseta para o próximo ciclo de alarme
                            soundManager.playSound()
                            Log.i(TAG, "ALARME DE SONOLÊNCIA!")

                            if (!piSignalStateIsStop && bluetoothCommander.connectionState == BluetoothCommander.STATE_CONNECTED) {
                                Log.d(TAG, "Sonolência confirmada! Enviando 'parar' para a Pi.")
                                bluetoothCommander.sendCommand("D") // Adicionar \n
                                piSignalStateIsStop = true // Assume que foi enviado, idealmente esperar ACK
                            } else if (bluetoothCommander.connectionState != BluetoothCommander.STATE_CONNECTED) {
                                Log.w(TAG, "Sonolência detectada, mas Bluetooth não conectado. Tentando reconectar...")
                                bluetoothCommander.connect() // Tenta reconectar
                            }
                        }
                    }
                } else {
                    // Olhos abertos (pelo menos um) para este rosto
                    // Se este era o único rosto ou todos os outros também estão alertas, resetar o contador de frames
                    // Esta lógica simplifica: se *qualquer* rosto detectado neste frame está alerta,
                    // e o sinal da Pi estava em "parar", ele será mudado para "liberar".
                    // Se houver múltiplos rostos, e um estiver sonolento e outro não, a lógica
                    // de 'isAnyFaceDrowsyThisFrame' e o 'else' abaixo tratarão disso.
                }
            }
        } // Fim do loop for (face in faces)

        if (!isAnyFaceDrowsyThisFrame && piSignalStateIsStop && bluetoothCommander.connectionState == BluetoothCommander.STATE_CONNECTED) {
            // Nenhum rosto neste frame estava sonolento, e o sinal estava em "parar"
            Log.d(TAG, "Motorista(s) alerta(s)! Enviando 'liberar' para a Pi.")
            bluetoothCommander.sendCommand("N") // Adicionar \n
            piSignalStateIsStop = false
            eyesClosedFrameCount = 0 // Reseta contador de frames se todos estão alertas
            // Considerar resetar eyesClosedCount também se o alarme não deve persistir
            // eyesClosedCount = 0;
        } else if (!isAnyFaceDrowsyThisFrame) {
            eyesClosedFrameCount = 0 // Reseta contador de frames se todos estão alertas
        }
    }

    override fun onFailure(e: Exception) {
        Log.e(TAG, "Detecção de rosto falhou: $e")
        if (piSignalStateIsStop && bluetoothCommander.connectionState == BluetoothCommander.STATE_CONNECTED) {
            Log.d(TAG, "Falha na detecção, enviando 'liberar' para a Pi como precaução.")
            bluetoothCommander.sendCommand("N") // Adicionar \n
            piSignalStateIsStop = false
        }
    }
}