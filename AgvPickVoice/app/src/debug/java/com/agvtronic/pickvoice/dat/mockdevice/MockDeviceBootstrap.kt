package com.agvtronic.pickvoice.dat.mockdevice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import com.meta.wearable.dat.mockdevice.MockDeviceKit
import com.meta.wearable.dat.mockdevice.api.GlassesModel
import com.meta.wearable.dat.mockdevice.api.MockGlasses

/**
 * Sobe um óculos simulado pelo MockDeviceKit e o deixa pronto para uso, antes de qualquer
 * chamada de registro.
 *
 * Variante **debug**. A contraparte em `app/src/release/` tem a mesma assinatura e não faz
 * nada — é assim que a troca "dispositivo simulado → óculos real" fica restrita à seleção de
 * build (doc §13.3), sem `if (BuildConfig.DEBUG)` espalhado pelo código de produção. O
 * `mwdat-mockdevice` só entra no APK de debug (`debugImplementation` em `build.gradle.kts`),
 * então este arquivo é o único lugar do app que pode importá-lo.
 *
 * Diferente do sample `CameraAccess`, que expõe um menu manual para explorar a API, aqui o
 * bootstrap é automático: o objetivo é que o fluxo completo suba sem fricção toda vez que o
 * app abre em debug.
 *
 * Idempotente: chamar de novo com o kit já habilitado não pareia um segundo dispositivo.
 */
fun prepararDispositivoSimulado(context: Context) {
  val mockDeviceKit = MockDeviceKit.getInstance(context)

  if (mockDeviceKit.isEnabled) {
    Log.d(TAG, "MockDeviceKit já habilitado; nada a preparar")
    return
  }

  // A configuração padrão registra o app automaticamente, ou seja, `Wearables.registrationState`
  // já parte de REGISTERED. O DatSessionController conta com isso: ele só chama
  // `startRegistration` quando o estado observado for AVAILABLE.
  mockDeviceKit.enable()

  mockDeviceKit
      .pairGlasses(GlassesModel.RAYBAN_META)
      .onSuccess { oculos ->
        // A ordem importa: só um óculos ligado, aberto e vestido aparece como elegível para o
        // AutoDeviceSelector — é o equivalente simulado de tirar os óculos do estojo.
        oculos.powerOn()
        oculos.unfold()
        oculos.don()
        Log.d(TAG, "Óculos simulado pronto: ${oculos.deviceIdentifier}")
        registrarHookDeDepuracao(context, oculos)
      }
      .onFailure { erro, _ ->
        // Sem dispositivo simulado, `createSession` falha com NO_ELIGIBLE_DEVICE e o
        // controlador publica SessaoFalhou — o app degrada para Erro em vez de travar.
        Log.e(TAG, "Falha ao parear óculos simulado: ${erro.description}")
      }
}

/**
 * Permite derrubar e restaurar o óculos simulado por `adb`, sem tocar na tela:
 *
 * ```
 * adb shell am broadcast -a com.agvtronic.pickvoice.DEBUG_DESLIGAR_OCULOS
 * adb shell am broadcast -a com.agvtronic.pickvoice.DEBUG_LIGAR_OCULOS
 * ```
 *
 * É como se verifica, sem óculos físico, o caminho de perda e retomada de conexão do doc
 * §3.3 — o único jeito de exercitar `ConexaoBluetoothPerdida`/`ConexaoBluetoothRestabelecida`
 * numa bancada. Só existe na variante debug.
 */
private fun registrarHookDeDepuracao(context: Context, oculos: MockGlasses) {
  val receiver =
      object : BroadcastReceiver() {
        override fun onReceive(contexto: Context?, intent: Intent?) {
          when (intent?.action) {
            ACAO_DESLIGAR -> {
              Log.d(TAG, "Hook de depuração: desligando óculos simulado")
              oculos.doff()
              oculos.fold()
              oculos.powerOff()
            }
            ACAO_LIGAR -> {
              // Repareia quando o dispositivo foi removido por ACAO_DESPAREAR — só ligar o
              // objeto antigo não o traz de volta para `Wearables.devices`.
              val kit = MockDeviceKit.getInstance(context)
              val alvo =
                  if (kit.pairedDevices.isEmpty()) {
                    Log.d(TAG, "Hook de depuração: repareando óculos simulado")
                    kit.pairGlasses(GlassesModel.RAYBAN_META).getOrNull()
                  } else {
                    oculos
                  }
              Log.d(TAG, "Hook de depuração: religando óculos simulado")
              alvo?.powerOn()
              alvo?.unfold()
              alvo?.don()
            }
            ACAO_DESPAREAR -> {
              Log.d(TAG, "Hook de depuração: despareando óculos simulado")
              MockDeviceKit.getInstance(context).unpairDevice(oculos)
            }
          }
        }
      }

  val filtro =
      IntentFilter().apply {
        addAction(ACAO_DESLIGAR)
        addAction(ACAO_LIGAR)
        addAction(ACAO_DESPAREAR)
      }
  // EXPORTED porque o emissor é o shell do adb, um processo externo.
  context.registerReceiver(receiver, filtro, Context.RECEIVER_EXPORTED)
}

private const val ACAO_DESLIGAR = "com.agvtronic.pickvoice.DEBUG_DESLIGAR_OCULOS"
private const val ACAO_LIGAR = "com.agvtronic.pickvoice.DEBUG_LIGAR_OCULOS"
private const val ACAO_DESPAREAR = "com.agvtronic.pickvoice.DEBUG_DESPAREAR_OCULOS"

private const val TAG = "MockDeviceBootstrap"
