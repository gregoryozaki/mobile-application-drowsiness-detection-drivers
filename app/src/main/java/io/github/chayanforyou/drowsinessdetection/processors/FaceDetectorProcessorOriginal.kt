package io.github.chayanforyou.drowsinessdetection.processors

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import io.github.chayanforyou.drowsinessdetection.overlay.GraphicOverlay
import io.github.chayanforyou.drowsinessdetection.FaceGraphic
import io.github.chayanforyou.drowsinessdetection.utils.SoundPoolManager
import io.github.chayanforyou.drowsinessdetection.utils.BluetoothDeviceSender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class FaceDetectorProcessor2(context: Context, detectorOptions: FaceDetectorOptions?) :
  VisionProcessorBase<List<Face>>(context) {

  private val detector: FaceDetector
  private val soundManager: SoundPoolManager
  private var eyesClosedFrameCount = 0
  private val bluetoothSender: BluetoothDeviceSender // Novo

  init {
    val options = detectorOptions
      ?: FaceDetectorOptions.Builder()
        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
        .enableTracking()
        .build()

    detector = FaceDetection.getClient(options)
    soundManager = SoundPoolManager.getInstance(context)
    bluetoothSender = BluetoothDeviceSender(context) // Novo

    // Tentar conectar ao iniciar (ou em um momento mais apropriado)
    // Idealmente, isso seria acionado por uma ação do usuário ou configuração
    CoroutineScope(Dispatchers.Main).launch { // Usar Main para iniciar, mas connectToDevice usa IO
      val connected = bluetoothSender.connectToDevice()
      if (connected) {
        Log.i(TAG, "Conectado ao Arduino via Bluetooth para envio de alertas.")
      } else {
        Log.w(TAG, "Falha ao conectar ao Arduino via Bluetooth.")
      }
    }
    Log.v(MANUAL_TESTING_LOG, "Face detector options: $options")
  }

  override fun stop() {
    super.stop()
    detector.close()
    soundManager.stop()
    bluetoothSender.closeConnection() // Novo
  }

  override fun detectInImage(image: InputImage): Task<List<Face>> {
    return detector.process(image)
  }

  override fun onSuccess(faces: List<Face>, graphicOverlay: GraphicOverlay) {
    for (face in faces) {
      val leftEyeOpen = face.leftEyeOpenProbability
      val rightEyeOpen = face.rightEyeOpenProbability

      if (leftEyeOpen != null && rightEyeOpen != null) {
        if (leftEyeOpen < EYES_CLOSED_THRESHOLD && rightEyeOpen < EYES_CLOSED_THRESHOLD) {
          eyesClosedFrameCount++

          if (eyesClosedFrameCount >= framesPerSecond / 2) {
            eyesClosedCount++
            eyesClosedFrameCount = 0

            if (eyesClosedCount >= ALARM_COUNT_THRESHOLD) {
              alarmCount++
              eyesClosedCount = 0
              soundManager.playSound()

              CoroutineScope(Dispatchers.Main).launch {
                val sent = bluetoothSender.sendData("CMD_ARDUINO_PARAR\n")

                if (sent) {
                  Log.i(TAG, "Comando de alerta enviado à Respbarry Pi.")
                } else {
                  Log.i(TAG, "Falha ao enviar comando de alerta à Respbarry Pi.")
                }

              }
            }
          }
        } else {
          eyesClosedFrameCount = 0
        }
      }

      graphicOverlay.add(FaceGraphic(graphicOverlay, face))
    }
  }

  override fun onFailure(e: Exception) {
    Log.e(TAG, "Face detection failed $e")
  }

  companion object {
    private const val TAG = "FaceDetectorProcessor"
    private const val EYES_CLOSED_THRESHOLD = 0.50f
    private const val ALARM_COUNT_THRESHOLD = 3
  }
}
