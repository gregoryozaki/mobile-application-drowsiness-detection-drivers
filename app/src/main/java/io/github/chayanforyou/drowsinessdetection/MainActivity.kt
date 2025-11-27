package io.github.chayanforyou.drowsinessdetection

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.camera.core.Preview
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.face.FaceDetectorOptions
import io.github.chayanforyou.drowsinessdetection.overlay.GraphicOverlay
import io.github.chayanforyou.drowsinessdetection.processors.FaceDetectorProcessor
import io.github.chayanforyou.drowsinessdetection.processors.VisionImageProcessor
import io.github.chayanforyou.drowsinessdetection.utils.BluetoothCommander // Importe sua classe
import io.github.chayanforyou.drowsinessdetection.viewmodels.CameraXViewModel


class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var graphicOverlay: GraphicOverlay
    private var cameraProvider: ProcessCameraProvider? = null
    private var analysisUseCase: ImageAnalysis? = null
    private var imageProcessor: VisionImageProcessor? = null
    private var needUpdateOverlayInfo = false
    private var cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

    private lateinit var bluetoothCommander: BluetoothCommander

    companion object {
        private const val TAG = "MainActivity"
        private const val PERMISSIONS_REQUEST_CODE = 123
    }

    private val requiredPermissions = mutableListOf(
        Manifest.permission.CAMERA
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            add(Manifest.permission.BLUETOOTH)
            add(Manifest.permission.BLUETOOTH_ADMIN)
        }
        add(Manifest.permission.ACCESS_FINE_LOCATION)
    }.toTypedArray()

    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions.entries.all { it.value }) {
                Log.d(TAG, "Todas as permissões concedidas após solicitação.")
                initBluetoothAndCamera()
            } else {
                Log.e(TAG, "Permissões não concedidas.")
                Toast.makeText(this, "Permissões são necessárias para o app funcionar.", Toast.LENGTH_LONG).show()
                // Lidar com a negação de permissões (ex: fechar o app ou desabilitar funcionalidades)
            }
        }

    // Launcher para solicitar habilitação do Bluetooth
    private val requestEnableBluetoothLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                Log.d(TAG, "Bluetooth habilitado pelo usuário.")
                tryConnectBluetoothAndStartCamera()
            } else {
                Log.e(TAG, "Usuário não habilitou o Bluetooth.")
                Toast.makeText(this, "Bluetooth precisa ser habilitado.", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.preview_view)
        graphicOverlay = findViewById(R.id.graphic_overlay)

        // Instancia o BluetoothCommander
        bluetoothCommander = BluetoothCommander(applicationContext, lifecycleScope)
        bluetoothCommander.onConnectionStateChanged = { state, message ->
            Log.i(TAG, "Bluetooth State Changed: $message (code: $state)")
            // Você pode atualizar a UI aqui se necessário
            if (state == BluetoothCommander.STATE_CONNECTED) {
                Toast.makeText(this, "Conectado à Raspberry Pi", Toast.LENGTH_SHORT).show()
            } else if (state == BluetoothCommander.STATE_DISCONNECTED && message.startsWith("Falha na conexão")) {
                Toast.makeText(this, "Falha ao conectar à Pi: $message", Toast.LENGTH_LONG).show()
            }
        }
        bluetoothCommander.onMessageReceived = { message ->
            Log.i(TAG, "Mensagem recebida da Pi: $message")
            // Processar ACKs ou outras mensagens da Pi se necessário
        }


        // Verifica e solicita permissões
        checkAndRequestPermissions()

        ViewModelProvider(
            this,
            ViewModelProvider.AndroidViewModelFactory.getInstance(application)
        )[CameraXViewModel::class.java]
            .processCameraProvider
            .observe(this) { provider: ProcessCameraProvider? ->
                cameraProvider = provider
                // A inicialização da câmera agora acontece após as permissões e habilitação do BT
                // Se as permissões já foram concedidas, initBluetoothAndCamera() já pode ter chamado bindAllCameraUseCases()
            }
    }

    private fun allPermissionsGranted() = requiredPermissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun checkAndRequestPermissions() {
        if (allPermissionsGranted()) {
            Log.d(TAG, "Todas as permissões já estão concedidas.")
            initBluetoothAndCamera()
        } else {
            Log.d(TAG, "Solicitando permissões: ${requiredPermissions.joinToString()}")
            requestPermissionsLauncher.launch(requiredPermissions)
        }
    }

    private fun initBluetoothAndCamera() {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Este dispositivo não suporta Bluetooth.", Toast.LENGTH_LONG).show()
            // Ainda podemos tentar iniciar a câmera se o Bluetooth não for hard-dependency
            bindAllCameraUseCases()
            return
        }

        if (!bluetoothAdapter.isEnabled) {
            Log.d(TAG, "Bluetooth não está habilitado. Solicitando habilitação.")
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            // Verificar se temos permissão BLUETOOTH_CONNECT antes de lançar (já verificado em REQUIRED_PERMISSIONS para S+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                    requestEnableBluetoothLauncher.launch(enableBtIntent)
                } else {
                    Toast.makeText(this, "Permissão BLUETOOTH_CONNECT não concedida para habilitar Bluetooth.", Toast.LENGTH_LONG).show()
                    bindAllCameraUseCases() // Prosseguir com a câmera, BT não funcionará
                }
            } else {
                requestEnableBluetoothLauncher.launch(enableBtIntent)
            }
        } else {
            Log.d(TAG, "Bluetooth já está habilitado.")
            tryConnectBluetoothAndStartCamera()
        }
    }

    private fun tryConnectBluetoothAndStartCamera() {
        if (bluetoothCommander.connectionState != BluetoothCommander.STATE_CONNECTED) {
            Log.d(TAG, "Tentando conectar Bluetooth...")
            // A conexão é assíncrona, não bloqueia a UI
            bluetoothCommander.connect() // connect() já usa o lifecycleScope internamente ou o escopo passado
        }
        bindAllCameraUseCases() // Inicia a câmera independentemente do resultado imediato da conexão BT
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray // Este método é obsoleto, use o launcher
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // A lógica agora está no callback do requestPermissionsLauncher
        // No entanto, se você tiver outras solicitações de permissão com ActivityCompat.requestPermissions,
        // este método ainda será chamado.
        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            Log.d(TAG, "onRequestPermissionsResult invocado para PERMISSIONS_REQUEST_CODE (Code: $requestCode).")
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                Log.d(TAG, "Fallback em onRequestPermissionsResult: Todas as permissões necessárias parecem concedidas.")
                initBluetoothAndCamera()
            } else {
                Log.w(TAG, "Fallback em onRequestPermissionsResult: Nem todas as permissões necessárias foram concedidas.")
                if (!isFinishing && !isChangingConfigurations) {
                    Toast.makeText(
                        this,
                        "Algumas permissões ainda são necessárias (manipulador de fallback).",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Se as permissões foram concedidas e o BT está habilitado, mas não conectado,
        // você pode tentar reconectar aqui se desejar, ou deixar o usuário fazer isso.
        // A lógica em onCreate e nos callbacks dos launchers já cobre a inicialização.
        // Se a câmera já está configurada, não precisa chamar bindAllCameraUseCases() de novo
        // a menos que algo tenha sido liberado em onPause de forma específica.
        if (allPermissionsGranted() && cameraProvider != null && analysisUseCase == null) {
            initBluetoothAndCamera() // Garante que tudo está pronto se a activity foi recriada
        }
    }

    override fun onPause() {
        super.onPause()
        imageProcessor?.run { this.stop() }
        // Não desconecte o Bluetooth aqui, a menos que seja um requisito específico.
        // A conexão pode ser mantida em background se o app for para segundo plano brevemente.
    }

    override fun onDestroy() {
        super.onDestroy()
        imageProcessor?.run { this.stop() }
        Log.d(TAG, "MainActivity onDestroy, desconectando BluetoothCommander.")
        bluetoothCommander.disconnect() // Importante desconectar para liberar recursos
    }

    private fun bindAllCameraUseCases() {
        if (cameraProvider == null) {
            Log.w(TAG, "CameraProvider não está disponível para bind.")
            return
        }

        // Verifica se o analysisUseCase já existe E está vinculado.
        // Se o analysisUseCase for o principal indicador de que a câmera está configurada,
        // esta verificação é suficiente para evitar re-binding desnecessário.
        // O Preview é vinculado através do previewView diretamente no bindToLifecycle.
        if (analysisUseCase != null && cameraProvider!!.isBound(analysisUseCase!!)) {
            Log.d(TAG, "AnalysisUseCase já está vinculado. Nenhum re-binding necessário.")
            return
        }

        Log.d(TAG, "Vinculando/Revinculando casos de uso da câmera.")
        // É uma boa prática desvincular tudo antes de vincular novamente para garantir um estado limpo,
        // especialmente se a configuração da câmera (como o seletor) puder mudar.
        cameraProvider!!.unbindAll()
        bindAnalysisUseCase() // Esta função criará e vinculará os novos casos de uso
    }

    private fun bindAnalysisUseCase() {
        if (cameraProvider == null) {
            Log.w(TAG, "CameraProvider não está disponível para bindAnalysisUseCase.")
            return
        }

        if (imageProcessor != null) { // Se já existe um processador
            Log.d(TAG, "Parando processador de imagem anterior.")
            imageProcessor!!.stop()
        }

        imageProcessor = try {
            val optionsBuilder = FaceDetectorOptions.Builder()
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setMinFaceSize(0.1f)
            val faceDetectorOptions = optionsBuilder.build()
            // Passe a instância de bluetoothCommander para o processador
            FaceDetectorProcessor(applicationContext, faceDetectorOptions, bluetoothCommander)
        } catch (e: Exception) {
            Toast.makeText(applicationContext, "Não foi possível criar o processador de imagem: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            Log.e(TAG, "Erro ao criar processador de imagem", e)
            return
        }

        // 1. Criar o caso de uso Preview
        val preview = Preview.Builder()
            .build()
            .also {
                // Conectar o SurfaceProvider do PreviewView ao caso de uso Preview
                it.surfaceProvider = previewView.surfaceProvider
            }

        // 2. Criar o caso de uso ImageAnalysis (você já faz isso)
        analysisUseCase = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        needUpdateOverlayInfo = true // Resetar para configurar o graphicOverlay com as dimensões corretas

        analysisUseCase?.setAnalyzer(
            ContextCompat.getMainExecutor(this)
        ) { imageProxy: ImageProxy ->
            if (needUpdateOverlayInfo) {
                val isImageFlipped = cameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA
                val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                if (rotationDegrees == 0 || rotationDegrees == 180) {
                    graphicOverlay.setImageSourceInfo(imageProxy.width, imageProxy.height, isImageFlipped)
                } else {
                    graphicOverlay.setImageSourceInfo(imageProxy.height, imageProxy.width, isImageFlipped)
                }
                needUpdateOverlayInfo = false
            }
            try {
                imageProcessor?.processImageProxy(imageProxy, graphicOverlay)
            } catch (e: MlKitException) {
                Log.e(TAG, "MlKitException no processamento de imagem: ${e.localizedMessage}", e)
            } catch (e: Exception) {
                Log.e(TAG, "Exceção geral no processamento de imagem: ${e.localizedMessage}", e)
            }
        }

        try {
            // 3. Desvincular todos os casos de uso anteriores para evitar conflitos
            cameraProvider!!.unbindAll() // Movido para antes de bindToLifecycle para garantir um estado limpo

            // 4. Vincular os casos de uso Preview e ImageAnalysis ao ciclo de vida
            // Agora estamos passando duas instâncias de UseCase: 'preview' e 'analysisUseCase'
            cameraProvider!!.bindToLifecycle(
                this, // LifecycleOwner
                cameraSelector, // CameraSelector
                preview, // Primeiro UseCase (Preview)
                analysisUseCase // Segundo UseCase (ImageAnalysis)
            )
            Log.d(TAG, "Casos de uso da câmera (Preview e Analysis) vinculados ao ciclo de vida.")
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao vincular casos de uso da câmera", e)
            Toast.makeText(this, "Falha ao iniciar a câmera.", Toast.LENGTH_SHORT).show()
        }
    }

}